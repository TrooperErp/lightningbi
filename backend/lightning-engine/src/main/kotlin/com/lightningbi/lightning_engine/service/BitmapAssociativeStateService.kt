package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.VersionSnapshot
import com.lightningbi.lightning_engine.repository.RegistryRepository
import kotlinx.coroutines.Dispatchers
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

/**
 * Motore associativo basato sull'indice bitmap (ch_lbi_assoc_bitmap,
 * costruito da BitmapIndexBuilder). Calcola gli stati verde/grigio di
 * tutte le dimensioni richieste con UNA sola query, invece di una
 * SELECT DISTINCT per dimensione come AssociativeStateService.
 *
 * Semantica IDENTICA al motore a query (requisito per il confronto):
 * - per ogni dimensione D, i verdi si calcolano rispetto alle selezioni
 *   di tutte le ALTRE dimensioni (omitSelf): se nessun'altra dimensione
 *   ha selezioni, verdi = dominio intero di D;
 * - grigi = dominio - verdi;
 * - selezionati = selezione su D ∩ dominio di D.
 *
 * Come si traduce in bitmap: per ogni dimensione selezionata i si
 * calcola B_i = OR delle bitmap dei valori scelti. La bitmap "ammessa"
 * per una dimensione D è l'AND di tutti i B_i con i ≠ D. Un valore di D
 * è verde se la sua bitmap interseca quella ammessa (cardinalità > 0).
 * Le dimensioni senza selezioni usano l'AND di TUTTI i B_i, calcolato
 * una volta sola.
 *
 * dimensioniDaCalcolare limita QUALI dimensioni ricevono uno stato
 * (null = tutte): serve alla pagina "Configura Analisi", che calcola
 * solo le card aperte. Le selezioni invece si applicano SEMPRE tutte,
 * anche quelle su dimensioni non richieste, altrimenti il verde/grigio
 * sarebbe sbagliato.
 */
@Service
class BitmapAssociativeStateService(
    private val jdbcTemplate: JdbcTemplate,
    private val registryRepository: RegistryRepository,
    private val versionService: VersionService,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(BitmapAssociativeStateService::class.java)
    private val cacheTtl = Duration.ofHours(6)
    private val indexTable = "ch_lbi_assoc_bitmap"

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

        // Selezioni ripulite su TUTTE le dimensioni dell'area, non solo
        // su quelle da calcolare.
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
                log.warn("Bitmap cache deserialization failed for key $cacheKey, recomputing", e)
            }
        }

        val computed = withContext(Dispatchers.IO) { computeStates(areaId, cleanSelections, target) }
        safeSet(cacheKey, serialize(computed))
        TimingRecorder.recordTotalCompute(
            areaId, target.size, cardinalitaSelezione,
            System.currentTimeMillis() - startTotal, cacheHit = false
        )
        return computed
    }

    private fun computeStates(
        areaId: UUID,
        selections: Map<UUID, Set<Long>>,
        target: Set<UUID>
    ): Map<UUID, DimensionState> {
        // Tutti i valori interpolati hanno tipo controllato (UUID, Long):
        // nessun testo libero finisce nella query.
        val area = "toUUID('$areaId')"
        val selectedDims = selections.keys.toList()
        val alias = selectedDims.mapIndexed { i, dimId -> dimId to "b$i" }.toMap()

        val withClauses = mutableListOf<String>()
        selectedDims.forEach { dimId ->
            val valori = selections.getValue(dimId).joinToString(",")
            withClauses += """
                (SELECT groupBitmapOrState(righe) FROM $indexTable
                  WHERE area_id = $area AND dimensione_id = toUUID('$dimId') AND valore_id IN ($valori)) AS ${alias.getValue(dimId)}
            """.trimIndent()
        }

        fun andOf(aliases: List<String>): String? =
            if (aliases.isEmpty()) null
            else aliases.drop(1).fold(aliases.first()) { acc, a -> "bitmapAnd($acc, $a)" }

        // Ammessa per le dimensioni senza selezione: AND di tutti i B_i.
        andOf(alias.values.toList())?.let { withClauses += "$it AS ammessa_tutte" }

        val verdeExpr = if (selectedDims.isEmpty()) {
            "1"
        } else {
            val branches = selectedDims.flatMap { dimId ->
                val altri = alias.filterKeys { it != dimId }.values.toList()
                val cond = andOf(altri)?.let { "bitmapAndCardinality(righe, $it) > 0" } ?: "1"
                listOf("dimensione_id = toUUID('$dimId')", cond)
            }
            "multiIf(${branches.joinToString(", ")}, bitmapAndCardinality(righe, ammessa_tutte) > 0)"
        }

        val targetList = target.joinToString(",") { "toUUID('$it')" }
        val withPart = if (withClauses.isEmpty()) "" else "WITH ${withClauses.joinToString(",\n")}\n"

        val sql = """
            ${withPart}SELECT dimensione_id, valore_id, toUInt8($verdeExpr) AS verde
            FROM $indexTable
            WHERE area_id = $area AND dimensione_id IN ($targetList)
        """.trimIndent()

        val dominio = mutableMapOf<UUID, MutableSet<Long>>()
        val verdi = mutableMapOf<UUID, MutableSet<Long>>()

        jdbcTemplate.query(sql) { rs ->
            val dimId = UUID.fromString(rs.getString("dimensione_id"))
            val valore = rs.getLong("valore_id")
            dominio.getOrPut(dimId) { mutableSetOf() }.add(valore)
            if (rs.getInt("verde") != 0) verdi.getOrPut(dimId) { mutableSetOf() }.add(valore)
        }

        if (dominio.isEmpty()) {
            log.warn("Indice bitmap vuoto per area {}: serve una sincronizzazione per popolarlo", areaId)
        }

        return target.associateWith { dimId ->
            val dom = dominio[dimId] ?: emptySet()
            val v = verdi[dimId] ?: emptySet()
            DimensionState(
                verdi = v,
                grigi = dom - v,
                selezionati = (selections[dimId] ?: emptySet()).intersect(dom)
            )
        }
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
        return "assoc-bitmap:" + HexFormat.of().formatHex(digest)
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
            log.warn("Redis GET failed for key $key, falling back to compute", e)
            null
        }

    private fun safeSet(key: String, value: String) {
        try {
            redisTemplate.opsForValue().set(key, value, cacheTtl)
        } catch (e: Exception) {
            log.warn("Redis SET failed for key $key", e)
        }
    }
}