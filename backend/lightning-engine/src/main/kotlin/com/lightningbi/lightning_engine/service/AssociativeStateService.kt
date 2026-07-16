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
    private val validIdentifier = Regex("^[a-z0-9_]+$")
    private val cacheTtl = Duration.ofHours(6)
    private val symbolCacheTtl = Duration.ofHours(24)
    private val querySemaphore = Semaphore(4)

    suspend fun getStates(areaId: UUID, selections: Map<UUID, Set<Long>>): Map<UUID, DimensionState> =
        getStates(areaId, selections, versionService.snapshotVersions(areaId))

    suspend fun getStates(
        areaId: UUID,
        selections: Map<UUID, Set<Long>>,
        versions: VersionSnapshot
    ): Map<UUID, DimensionState> {
        val dims = registryRepository.findDimensioniByArea(areaId)
        val validDimIds = dims.map { it.dimensioneId }.toSet()

        val cleanSelections = selections
            .filterKeys { it in validDimIds }
            .filterValues { it.isNotEmpty() }

        val cacheKey = buildCacheKey(areaId, cleanSelections, versions)

        val cached = safeGet(cacheKey)
        if (cached != null) {
            try {
                return deserialize(cached)
            } catch (e: Exception) {
                log.warn("Cache deserialization failed for key $cacheKey, recomputing", e)
            }
        }

        val computed = computeStates(areaId, dims, cleanSelections, versions.dataVersion)
        safeSet(cacheKey, serialize(computed))
        return computed
    }

    private suspend fun computeStates(
        areaId: UUID,
        dims: List<AreaDimensione>,
        selections: Map<UUID, Set<Long>>,
        dataVersion: Long
    ): Map<UUID, DimensionState> = coroutineScope {
        val area = registryRepository.findAreaById(areaId) ?: error("Area not found")
        val dimById = dims.associateBy { it.dimensioneId }

        dims.map { dim ->
            async {
                querySemaphore.withPermit {
                    val omitSelf = selections.filterKeys { it != dim.dimensioneId }
                    val tutti = allValuesCached(dim, dataVersion)

                    val verdi = if (omitSelf.isEmpty()) tutti
                    else withContext(Dispatchers.IO) {
                        queryDistinct(area.tabellaFisica, dim.colonnaFisica, omitSelf, dimById)
                    }

                    dim.dimensioneId to DimensionState(
                        verdi = verdi,
                        grigi = tutti - verdi,
                        selezionati = selections[dim.dimensioneId] ?: emptySet()
                    )
                }
            }
        }.awaitAll().toMap()
    }

    private fun queryDistinct(
        table: String,
        column: String,
        filters: Map<UUID, Set<Long>>,
        dimById: Map<UUID, AreaDimensione>
    ): Set<Long> {
        require(validIdentifier.matches(table)) { "Invalid table: $table" }
        require(validIdentifier.matches(column)) { "Invalid column: $column" }

        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<Any>()

        filters.forEach { (dimId, values) ->
            if (values.isEmpty()) return@forEach
            val col = dimById[dimId]?.colonnaFisica ?: return@forEach
            require(validIdentifier.matches(col)) { "Invalid column: $col" }
            whereClauses += "$col IN (${values.joinToString(",") { "?" }})"
            args.addAll(values)
        }

        val where = if (whereClauses.isEmpty()) "" else "WHERE ${whereClauses.joinToString(" AND ")}"
        val sql = "SELECT DISTINCT $column FROM $table $where"

        return jdbcTemplate.query(sql, { rs, _ -> rs.getLong(1) }, *args.toTypedArray()).toSet()
    }

    private suspend fun allValuesCached(dim: AreaDimensione, dataVersion: Long): Set<Long> {
        val dimensione = registryRepository.findDimensione(dim.dimensioneId) ?: return emptySet()
        val nome = dimensione.nome
        require(validIdentifier.matches(nome)) { "Invalid dimension name: $nome" }

        val cacheKey = "symbol:$nome:$dataVersion"
        val cached = safeGet(cacheKey)
        if (cached != null) {
            return try {
                cached.split(",").filter { it.isNotBlank() }.map { it.toLong() }.toSet()
            } catch (e: Exception) {
                log.warn("Symbol cache deserialization failed for $cacheKey, recomputing", e)
                withContext(Dispatchers.IO) { fetchAllValues(nome) }
            }
        }

        val values = withContext(Dispatchers.IO) { fetchAllValues(nome) }
        safeSet(cacheKey, values.joinToString(","), symbolCacheTtl)
        return values
    }

    private fun fetchAllValues(nome: String): Set<Long> {
        val table = "ch_lbi_symbol_$nome"
        return jdbcTemplate.query("SELECT value_id FROM $table", { rs, _ -> rs.getLong(1) }).toSet()
    }

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
            json, objectMapper.typeFactory.constructMapType(Map::class.java, String::class.java, DimensionState::class.java)
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