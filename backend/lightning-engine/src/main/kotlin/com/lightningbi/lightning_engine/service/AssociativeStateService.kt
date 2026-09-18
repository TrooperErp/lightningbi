package com.lightningbi.lightning_engine.service

import tools.jackson.databind.ObjectMapper
import com.lightningbi.lightning_engine.model.AreaDimensione
import com.lightningbi.lightning_engine.model.VersionSnapshot
import com.lightningbi.lightning_engine.repository.RegistryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

data class DimensionState(
    val verdi: Set<Long>,
    val grigi: Set<Long>,
    val selezionati: Set<Long>
)

@Service
class AssociativeStateService(
    private val jdbcTemplate: JdbcTemplate,
    private val registryRepository: RegistryRepository,
    private val versionService: VersionService,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(AssociativeStateService::class.java)
    private val cacheTtl = Duration.ofHours(6)
    private val domainCacheTtl = Duration.ofHours(24)
    private val querySemaphore = Semaphore(4)

    suspend fun getStates(areaId: UUID, selections: Map<UUID, Set<Long>>): Map<UUID, DimensionState> =
        getStates(areaId, selections, versionService.snapshotVersions(areaId))

    suspend fun getStates(
        areaId: UUID,
        selections: Map<UUID, Set<Long>>,
        versions: VersionSnapshot
    ): Map<UUID, DimensionState> {
        val startTotal = System.currentTimeMillis()
        val dims = registryRepository.findDimensioniByArea(areaId)
        val validDimIds = dims.map { it.dimensioneId }.toSet()

        val cleanSelections = selections
            .filterKeys { it in validDimIds }
            .filterValues { it.isNotEmpty() }

        val cardinalitaSelezione = cleanSelections.values.sumOf { it.size }
        val cacheKey = buildCacheKey(areaId, cleanSelections, versions)

        val cached = safeGet(cacheKey)
        if (cached != null) {
            try {
                val result = deserialize(cached)
                TimingRecorder.recordTotalCompute(
                    areaId, dims.size, cardinalitaSelezione,
                    System.currentTimeMillis() - startTotal, cacheHit = true
                )
                return result
            } catch (e: Exception) {
                log.warn("Cache deserialization failed for key $cacheKey, recomputing", e)
            }
        }

        val computed = computeStates(areaId, dims, cleanSelections, versions.dataVersion, areaId)
        safeSet(cacheKey, serialize(computed))
        TimingRecorder.recordTotalCompute(
            areaId, dims.size, cardinalitaSelezione,
            System.currentTimeMillis() - startTotal, cacheHit = false
        )
        return computed
    }

    private suspend fun computeStates(
        areaId: UUID,
        dims: List<AreaDimensione>,
        selections: Map<UUID, Set<Long>>,
        dataVersion: Long,
        areaIdForLogging: UUID
    ): Map<UUID, DimensionState> = coroutineScope {
        val area = registryRepository.findAreaById(areaId) ?: error("Area not found: $areaId")
        val dimById = dims.associateBy { it.dimensioneId }

        dims.map { dim ->
            async {
                val waitStart = System.currentTimeMillis()
                querySemaphore.withPermit {
                    val waitMs = System.currentTimeMillis() - waitStart
                    val omitSelf = selections.filterKeys { it != dim.dimensioneId }
                    val filtroSize = omitSelf.values.sumOf { it.size }

                    val dominio = areaDomainCached(area.tabellaFisica, dim.colonnaFisica, dataVersion)

                    val queryStart = System.currentTimeMillis()
                    val verdi = if (omitSelf.isEmpty()) dominio
                    else withContext(Dispatchers.IO) {
                        queryDistinct(area.tabellaFisica, dim.colonnaFisica, omitSelf, dimById)
                    }
                    val queryMs = System.currentTimeMillis() - queryStart

                    TimingRecorder.recordDimensionQuery(
                        areaId = areaIdForLogging,
                        dimensioneNome = dim.colonnaFisica,
                        dominioSize = dominio.size,
                        filtroSize = filtroSize,
                        tempoAttesaSemaforoMs = waitMs,
                        tempoQueryMs = queryMs,
                        cacheHit = omitSelf.isEmpty() // nessuna query DISTINCT lanciata: il dominio (già cachato) è servito com'è
                    )

                    val selezionati = (selections[dim.dimensioneId] ?: emptySet()).intersect(dominio)

                    dim.dimensioneId to DimensionState(
                        verdi = verdi,
                        grigi = dominio - verdi,
                        selezionati = selezionati
                    )
                }
            }
        }.awaitAll().toMap()
    }

    /**
     * Valori distinti di una colonna nella fact table dell'area, senza filtri.
     * È il denominatore per il calcolo dei grigi.
     *
     * Cambia solo quando cambia il dato, quindi è cachato per dataVersion:
     * a regime la query gira una volta per dimensione per ciclo ETL.
     */
    private suspend fun areaDomainCached(
        table: String,
        column: String,
        dataVersion: Long
    ): Set<Long> {
        val t = requireIdentifier(table, "table")
        val c = requireIdentifier(column, "column")

        val cacheKey = "domain:$t:$c:$dataVersion"
        safeGet(cacheKey)?.let { cached ->
            try {
                return parseIdList(cached)
            } catch (e: Exception) {
                log.warn("Domain cache deserialization failed for $cacheKey, recomputing", e)
            }
        }

        val values = withContext(Dispatchers.IO) {
            jdbcTemplate.query("SELECT DISTINCT $c FROM $t", { rs, _ -> rs.getLong(1) }).toSet()
        }
        safeSet(cacheKey, values.joinToString(","), domainCacheTtl)
        return values
    }

    private fun queryDistinct(
        table: String,
        column: String,
        filters: Map<UUID, Set<Long>>,
        dimById: Map<UUID, AreaDimensione>
    ): Set<Long> {
        val t = requireIdentifier(table, "table")
        val c = requireIdentifier(column, "column")

        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<Any>()

        filters.forEach { (dimId, values) ->
            if (values.isEmpty()) return@forEach
            val col = dimById[dimId]?.colonnaFisica ?: return@forEach
            val safeCol = requireIdentifier(col, "column")
            whereClauses += "$safeCol IN (${values.joinToString(",") { "?" }})"
            args.addAll(values)
        }

        val where = if (whereClauses.isEmpty()) "" else "WHERE ${whereClauses.joinToString(" AND ")}"
        val sql = "SELECT DISTINCT $c FROM $t $where"

        return jdbcTemplate.query(sql, { rs, _ -> rs.getLong(1) }, *args.toTypedArray()).toSet()
    }

    /**
     * Gli identificatori arrivano dal registry, dove sono già stati normalizzati
     * da Naming al momento della creazione. Qui si verifica soltanto, come
     * difesa contro SQL injection su dati preesistenti o migrati a mano.
     */
    private fun requireIdentifier(value: String, what: String): String {
        val normalized = Naming.slug(value)
        require(normalized == value) {
            "Identificatore $what non normalizzato nel registry: '$value' (atteso '$normalized'). " +
                    "Probabile dato creato prima dell'introduzione di Naming: va migrato."
        }
        return value
    }

    private fun parseIdList(raw: String): Set<Long> =
        raw.split(",").filter { it.isNotBlank() }.map { it.toLong() }.toSet()

    private fun buildCacheKey(
        areaId: UUID, selections: Map<UUID, Set<Long>>, versions: VersionSnapshot
    ): String {
        val canonical = selections.entries
            .sortedBy { it.key.toString() }
            .joinToString(";") { (dimId, values) -> "$dimId=${values.sorted().joinToString(",")}" }

        val raw = "$areaId|$canonical|${versions.registryVersion}|${versions.dataVersion}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val hash = HexFormat.of().formatHex(digest)
        return "assoc-state:$hash"
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

    private fun safeSet(key: String, value: String, ttl: Duration = cacheTtl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl)
        } catch (e: Exception) {
            log.warn("Redis SET failed for key $key", e)
        }
    }
}