package com.lightningbi.lightning_engine.service

import tools.jackson.databind.ObjectMapper
import com.lightningbi.lightning_engine.model.AggregateRequest
import com.lightningbi.lightning_engine.model.AggregateResult
import com.lightningbi.lightning_engine.model.AggregateRow
import com.lightningbi.lightning_engine.model.AreaDimensione
import com.lightningbi.lightning_engine.model.VersionSnapshot
import com.lightningbi.lightning_engine.repository.RegistryRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.UUID

@Service
class AggregateService(
    private val jdbcTemplate: JdbcTemplate,
    private val registryRepository: RegistryRepository,
    private val versionService: VersionService,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(AggregateService::class.java)
    private val validIdentifier = Regex("^[a-z0-9_]+$")
    private val cacheTtl = Duration.ofHours(6)
    private val rowLimit = 10_000

    fun getAggregates(req: AggregateRequest): AggregateResult =
        getAggregates(req, versionService.snapshotVersions(req.areaId))

    fun getAggregates(req: AggregateRequest, versions: VersionSnapshot): AggregateResult {
        val area = registryRepository.findAreaById(req.areaId) ?: error("Area not found")
        val dims = registryRepository.findDimensioniByArea(req.areaId)
        val dimById = dims.associateBy { it.dimensioneId }
        val metriche = registryRepository.findMetricheByArea(req.areaId)

        val validDimIds = dims.map { it.dimensioneId }.toSet()
        val cleanSelections = req.selections
            .filterKeys { it in validDimIds }
            .filterValues { it.isNotEmpty() }
        val cleanGroupBy = req.groupBy.filter { it in validDimIds }.distinct()

        val cacheKey = buildCacheKey(req.areaId, cleanSelections, cleanGroupBy, versions)

        val cached = safeGet(cacheKey)
        if (cached != null) {
            try {
                return deserialize(cached)
            } catch (e: Exception) {
                log.warn("Aggregate cache deserialization failed for $cacheKey, recomputing", e)
            }
        }

        val result = computeAggregates(area.tabellaFisica, cleanSelections, cleanGroupBy, dimById, metriche)
        safeSet(cacheKey, serialize(result))
        return result
    }

    private fun computeAggregates(
        table: String,
        selections: Map<UUID, Set<Long>>,
        groupBy: List<UUID>,
        dimById: Map<UUID, AreaDimensione>,
        metriche: List<com.lightningbi.lightning_engine.model.AreaMetrica>
    ): AggregateResult {
        require(validIdentifier.matches(table)) { "Invalid table: $table" }
        metriche.forEach { require(validIdentifier.matches(it.colonnaFisica)) { "Invalid metric column: ${it.colonnaFisica}" } }

        val groupCols = groupBy.mapNotNull { dimById[it]?.colonnaFisica }
        groupCols.forEach { require(validIdentifier.matches(it)) { "Invalid group column: $it" } }

        val (where, args) = buildWhere(selections, dimById)

        val selectCols = groupCols + metriche.map { "SUM(${it.colonnaFisica}) AS ${it.colonnaFisica}" }
        val groupClause = if (groupCols.isEmpty()) "" else "GROUP BY ${groupCols.joinToString(",")}"
        val whereClause = if (where.isEmpty()) "" else "WHERE $where"

        val sql = "SELECT ${selectCols.joinToString(",")} FROM $table $whereClause $groupClause LIMIT ${rowLimit + 1}"

        val rawRows = jdbcTemplate.query(sql, { rs, _ ->
            val groupKeys = groupBy.zip(groupCols).associate { (dimId, col) ->
                dimId to rs.getLong(col)
            }
            val values = metriche.associate { m ->
                m.nome to (rs.getBigDecimal(m.colonnaFisica) ?: BigDecimal.ZERO)
            }
            AggregateRow(groupKeys, values)
        }, *args.toTypedArray())

        val truncated = rawRows.size > rowLimit
        val rows = if (truncated) rawRows.take(rowLimit) else rawRows

        return AggregateResult(rows, truncated)
    }

    private fun buildWhere(
        selections: Map<UUID, Set<Long>>,
        dimById: Map<UUID, AreaDimensione>
    ): Pair<String, List<Any>> {
        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<Any>()

        selections.forEach { (dimId, values) ->
            if (values.isEmpty()) return@forEach
            val col = dimById[dimId]?.colonnaFisica ?: return@forEach
            require(validIdentifier.matches(col)) { "Invalid column: $col" }
            whereClauses += "$col IN (${values.joinToString(",") { "?" }})"
            args.addAll(values)
        }

        return whereClauses.joinToString(" AND ") to args
    }

    private fun buildCacheKey(
        areaId: UUID, selections: Map<UUID, Set<Long>>, groupBy: List<UUID>, versions: VersionSnapshot
    ): String {
        val canonicalSelections = selections.entries
            .sortedBy { it.key.toString() }
            .joinToString(";") { (dimId, values) -> "$dimId=${values.sorted().joinToString(",")}" }
        val canonicalGroupBy = groupBy.map { it.toString() }.sorted().joinToString(",")

        val raw = "$areaId|$canonicalSelections|$canonicalGroupBy|${versions.registryVersion}|${versions.dataVersion}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val hash = HexFormat.of().formatHex(digest)
        return "agg-state:$hash"
    }

    private fun serialize(result: AggregateResult): String = objectMapper.writeValueAsString(result)

    private fun deserialize(json: String): AggregateResult =
        objectMapper.readValue(json, AggregateResult::class.java)

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