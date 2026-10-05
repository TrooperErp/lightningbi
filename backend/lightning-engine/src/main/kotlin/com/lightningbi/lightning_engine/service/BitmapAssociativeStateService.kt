package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.VersionSnapshot
import com.lightningbi.lightning_engine.repository.RegistryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Motore associativo a propagazione, sull'indice per tabella (ch_lbi_idx,
 * costruito da BitmapIndexBuilder). Calcola gli stati verde/grigio/selezionato
 * delle dimensioni richieste come la logical inference di Qlik: la selezione si
 * propaga da una tabella alle altre lungo le associazioni (vedi GrafoDataset).
 *
 * Semantica:
 * - un valore di un campo è verde se è possibile rispetto alle selezioni di
 *   tutti gli ALTRI campi (la selezione del campo stesso non lo vincola);
 *   senza selezioni è tutto verde;
 * - grigi = dominio - verdi;
 * - selezionati = selezione sul campo ∩ dominio.
 *
 * Query: una per tutti i campi non selezionati (UNION ALL) e una per ogni campo
 * selezionato (che si calcola senza la propria selezione), in parallelo.
 *
 * Le dimensioni e le selezioni arrivano per id di dimensione (come le usano le
 * viste); nell'indice il campo ha il nome della dimensione. Le dimensioni
 * salvate prima della migrazione 030, che non sono nell'indice, ricevono uno
 * stato vuoto.
 *
 * dimensioniDaCalcolare limita QUALI dimensioni ricevono uno stato (null =
 * tutte): serve alla pagina "Configura Analisi". Le selezioni si applicano
 * SEMPRE tutte.
 */
@Service
class BitmapAssociativeStateService(
    private val jdbcTemplate: JdbcTemplate,
    private val registryRepository: RegistryRepository,
    private val datasetService: DatasetService,
    private val versionService: VersionService,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val modelloDatasetCache: ModelloDatasetCache,
) {
    private val log = LoggerFactory.getLogger(BitmapAssociativeStateService::class.java)
    private val cacheTtl = Duration.ofHours(6)



    private data class RigaStato(val campo: String, val valore: Long, val verde: Boolean)

    suspend fun getStates(
        areaId: UUID,
        selections: Map<UUID, Set<Long>>,
        dimensioniDaCalcolare: Set<UUID>? = null
    ): Map<UUID, DimensionState> =
        getStates(areaId, selections, versionService.snapshotVersions(areaId), dimensioniDaCalcolare)

    suspend fun getStates(
        areaId: UUID,
        selections: Map<UUID, Set<Long>>,
        versions: VersionSnapshot,
        dimensioniDaCalcolare: Set<UUID>? = null
    ): Map<UUID, DimensionState> {
        val startTotal = System.currentTimeMillis()
        val dims = registryRepository.findDimensioniByArea(areaId)
        val validDimIds = dims.map { it.dimensioneId }.toSet()

        // Selezioni ripulite su TUTTE le dimensioni dell'area, non solo su quelle da calcolare.
        val cleanSelections = selections
            .filterKeys { it in validDimIds }
            .filterValues { it.isNotEmpty() }

        val target = (dimensioniDaCalcolare?.filter { it in validDimIds } ?: validDimIds).toSet()
        if (target.isEmpty()) return emptyMap()

        val cardinalitaSelezione = cleanSelections.values.sumOf { it.size }
        val cacheKey = buildCacheKey(areaId, cleanSelections, target, versions)

        safeGet(cacheKey)?.let { cached ->
            try {
                val result = deserialize(cached)
                TimingRecorder.recordTotalCompute(
                    areaId, target.size, cardinalitaSelezione,
                    System.currentTimeMillis() - startTotal, cacheHit = true
                )
                return result
            } catch (e: Exception) {
                log.warn("Cache degli stati non leggibile per la chiave $cacheKey, ricalcolo", e)
            }
        }

        val computed = withContext(Dispatchers.IO) {
            computeStates(areaId, cleanSelections, target, versions.registryVersion)
        }
        safeSet(cacheKey, serialize(computed))
        TimingRecorder.recordTotalCompute(
            areaId, target.size, cardinalitaSelezione,
            System.currentTimeMillis() - startTotal, cacheHit = false
        )
        return computed
    }

    private suspend fun computeStates(
        areaId: UUID,
        selections: Map<UUID, Set<Long>>,
        target: Set<UUID>,
        registryVersion: Long
    ): Map<UUID, DimensionState> = coroutineScope {
        val grafo = modelloDatasetCache.get(areaId, registryVersion).grafo

        // Dimensione -> nome del campo nell'indice.
        val tutteLeDimensioni = (selections.keys + target).toList()
        val nomePerDimensione = registryRepository.findDimensioniByIds(tutteLeDimensioni)
            .associate { it.id to Naming.column(it.nome) }

        // Selezioni per nome di campo, solo su campi che esistono nel dataset.
        val selezioniPerCampo = selections.mapNotNull { (dimId, valori) ->
            nomePerDimensione[dimId]?.takeIf { grafo.tabelleCon(it).isNotEmpty() }?.let { it to valori }
        }.toMap()

        val campoPerDimensione = target.mapNotNull { dimId ->
            nomePerDimensione[dimId]?.takeIf { grafo.tabelleCon(it).isNotEmpty() }?.let { dimId to it }
        }.toMap()
        val senzaIndice = target - campoPerDimensione.keys
        if (senzaIndice.isNotEmpty()) {
            log.warn(
                "Dataset {}: {} dimensioni non sono nell'indice (salvate prima della migrazione 030?): " +
                        "il dataset va salvato di nuovo", areaId, senzaIndice.size
            )
        }

        val nonSelezionati = campoPerDimensione.values.filter { it !in selezioniPerCampo }.distinct()
        val selezionati = campoPerDimensione.values.filter { it in selezioniPerCampo }.distinct()

        val lavori = mutableListOf<kotlinx.coroutines.Deferred<List<RigaStato>>>()
        if (nonSelezionati.isNotEmpty()) {
            val sql = nonSelezionati.joinToString("\nUNION ALL\n") { campo ->
                "SELECT '$campo' AS campo, valore_id, verde FROM (${grafo.sqlStatoCampo(campo, selezioniPerCampo, null)})"
            }
            lavori += async { eseguiStati(sql) }
        }
        selezionati.forEach { campo ->
            val sql = "SELECT '$campo' AS campo, valore_id, verde FROM (${grafo.sqlStatoCampo(campo, selezioniPerCampo, campo)})"
            lavori += async { eseguiStati(sql) }
        }
        val righe = lavori.awaitAll().flatten()

        val dominio = mutableMapOf<String, MutableSet<Long>>()
        val verdi = mutableMapOf<String, MutableSet<Long>>()
        righe.forEach { r ->
            dominio.getOrPut(r.campo) { mutableSetOf() }.add(r.valore)
            if (r.verde) verdi.getOrPut(r.campo) { mutableSetOf() }.add(r.valore)
        }
        if (righe.isEmpty() && campoPerDimensione.isNotEmpty()) {
            log.warn("Indice vuoto per il dataset {}: serve una sincronizzazione per popolarlo", areaId)
        }

        target.associateWith { dimId ->
            val campo = campoPerDimensione[dimId]
            val dom = campo?.let { dominio[it] } ?: emptySet()
            val v = campo?.let { verdi[it] } ?: emptySet()
            DimensionState(
                verdi = v,
                grigi = dom - v,
                selezionati = (selections[dimId] ?: emptySet()).intersect(dom)
            )
        }
    }

    private fun eseguiStati(sql: String): List<RigaStato> {
        val risultato = mutableListOf<RigaStato>()
        jdbcTemplate.query(sql) { rs ->
            risultato += RigaStato(rs.getString("campo"), rs.getLong("valore_id"), rs.getInt("verde") != 0)
        }
        return risultato
    }



    private fun buildCacheKey(
        areaId: UUID,
        selections: Map<UUID, Set<Long>>,
        target: Set<UUID>,
        versions: VersionSnapshot
    ): String {
        val canonicalSel = selections.entries
            .sortedBy { it.key.toString() }
            .joinToString(";") { (dimId, values) -> "$dimId=${values.sorted().joinToString(",")}" }
        val canonicalTarget = target.map { it.toString() }.sorted().joinToString(",")

        val raw = "$areaId|$canonicalSel|$canonicalTarget|${versions.registryVersion}|${versions.dataVersion}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        // Prefisso nuovo: gli stati del vecchio motore (assoc-bitmap:) non si riusano.
        return "assoc-idx:" + HexFormat.of().formatHex(digest)
    }

    private fun serialize(states: Map<UUID, DimensionState>): String =
        objectMapper.writeValueAsString(states.mapKeys { it.key.toString() })

    private fun deserialize(json: String): Map<UUID, DimensionState> {
        val raw: Map<String, DimensionState> = objectMapper.readValue(
            json,
            objectMapper.typeFactory.constructMapType(
                Map::class.java, String::class.java, DimensionState::class.java
            )
        )
        return raw.mapKeys { UUID.fromString(it.key) }
    }

    private fun safeGet(key: String): String? =
        try {
            redisTemplate.opsForValue().get(key)
        } catch (e: Exception) {
            log.warn("Redis GET fallito per la chiave $key, calcolo senza cache", e)
            null
        }

    private fun safeSet(key: String, value: String) {
        try {
            redisTemplate.opsForValue().set(key, value, cacheTtl)
        } catch (e: Exception) {
            log.warn("Redis SET fallito per la chiave $key", e)
        }
    }
}