package com.lightningbi.lightning_engine.service

import tools.jackson.databind.ObjectMapper
import com.lightningbi.lightning_engine.model.AggregateOrder
import com.lightningbi.lightning_engine.model.AggregateRequest
import com.lightningbi.lightning_engine.model.AggregateResult
import com.lightningbi.lightning_engine.model.AggregateRow
import com.lightningbi.lightning_engine.model.AreaDimensione
import com.lightningbi.lightning_engine.model.AreaMetrica
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
    private val objectMapper: ObjectMapper,
    private val symbolLookupService: SymbolLookupService
) {
    private val log = LoggerFactory.getLogger(AggregateService::class.java)
    private val cacheTtl = Duration.ofHours(6)
    private val rowLimit = 10_000

    fun getAggregates(req: AggregateRequest): AggregateResult =
        getAggregates(req, versionService.snapshotVersions(req.areaId))

    fun getAggregates(req: AggregateRequest, versions: VersionSnapshot): AggregateResult {
        val area = registryRepository.findAreaById(req.areaId) ?: error("Area not found: ${req.areaId}")
        val dims = registryRepository.findDimensioniByArea(req.areaId)
        val dimById = dims.associateBy { it.dimensioneId }
        val tutteMetriche = registryRepository.findMetricheByArea(req.areaId)

        val validDimIds = dims.map { it.dimensioneId }.toSet()
        val cleanSelections = req.selections
            .filterKeys { it in validDimIds }
            .filterValues { it.isNotEmpty() }
        val cleanGroupBy = req.groupBy.filter { it in validDimIds }.distinct()

        val metriche = if (req.metricIds.isEmpty()) tutteMetriche
        else tutteMetriche.filter { it.id in req.metricIds.toSet() }
        require(metriche.isNotEmpty()) { "Nessuna metrica valida per l'area ${req.areaId}" }

        val orderMetrica = req.orderMetricId?.let { id -> metriche.find { it.id == id } }
        if (req.order == AggregateOrder.METRIC_DESC || req.order == AggregateOrder.METRIC_ASC) {
            requireNotNull(orderMetrica) {
                "order=${req.order} richiede orderMetricId fra le metriche richieste"
            }
        }

        val effectiveLimit = (req.limit ?: rowLimit).coerceIn(1, rowLimit)

        val cacheKey = buildCacheKey(req, cleanSelections, cleanGroupBy, metriche, effectiveLimit, versions)

        safeGet(cacheKey)?.let { cached ->
            try {
                return deserialize(cached)
            } catch (e: Exception) {
                log.warn("Aggregate cache deserialization failed for $cacheKey, recomputing", e)
            }
        }

        var result = computeAggregates(
            table = area.tabellaFisica,
            selections = cleanSelections,
            groupBy = cleanGroupBy,
            dimById = dimById,
            metriche = metriche,
            order = req.order,
            orderMetrica = orderMetrica,
            limit = effectiveLimit
        )

        if (req.resolveLabels || req.order == AggregateOrder.DIMENSION) {
            result = withLabels(result, cleanGroupBy, dimById)
            if (req.order == AggregateOrder.DIMENSION) {
                result = sortByLabel(result, cleanGroupBy)
            }
            if (!req.resolveLabels) {
                // Le etichette servivano solo per ordinare: non le si trascina
                // in cache se il chiamante non le ha chieste.
                result = AggregateResult(result.rows.map { it.copy(labels = emptyMap()) }, result.truncated)
            }
        }

        safeSet(cacheKey, serialize(result))
        return result
    }

    private fun computeAggregates(
        table: String,
        selections: Map<UUID, Set<Long>>,
        groupBy: List<UUID>,
        dimById: Map<UUID, AreaDimensione>,
        metriche: List<AreaMetrica>,
        order: AggregateOrder?,
        orderMetrica: AreaMetrica?,
        limit: Int
    ): AggregateResult {
        val t = requireIdentifier(table, "table")
        metriche.forEach { requireIdentifier(it.colonnaFisica, "metric column") }

        val groupCols = groupBy.mapNotNull { dimById[it]?.colonnaFisica }
        groupCols.forEach { requireIdentifier(it, "group column") }

        val (where, args) = buildWhere(selections, dimById)

        // Alias esplicito e stabile per ogni metrica: usare il nome colonna
        // come alias rompe se due metriche insistono sulla stessa colonna
        // (es. stessa colonna con aggregazioni diverse).
        val metricAliases = metriche.mapIndexed { i, m -> m to "m_$i" }
        val selectCols = groupCols +
                metricAliases.map { (m, alias) -> "${m.tipoAggregazione}(${m.colonnaFisica}) AS $alias" }

        val groupClause = if (groupCols.isEmpty()) "" else "GROUP BY ${groupCols.joinToString(",")}"
        val whereClause = if (where.isEmpty()) "" else "WHERE $where"

        // L'ORDER BY per metrica va fatto dal database: è l'unico modo per
        // avere un vero top-N. L'ordinamento per dimensione invece NON si può
        // fare qui, perché in SQL ordinerebbe per value_id - cioè per ordine
        // di primo caricamento nella symbol table, che non ha alcun rapporto
        // con l'ordine alfabetico o cronologico atteso dall'utente. Quello si
        // fa in memoria dopo aver risolto le etichette.
        val orderClause = when (order) {
            AggregateOrder.METRIC_DESC -> "ORDER BY ${aliasOf(metricAliases, orderMetrica)} DESC"
            AggregateOrder.METRIC_ASC -> "ORDER BY ${aliasOf(metricAliases, orderMetrica)} ASC"
            else -> ""
        }

        val sql = "SELECT ${selectCols.joinToString(",")} FROM $t $whereClause $groupClause $orderClause LIMIT ${limit + 1}"

        val rawRows = jdbcTemplate.query(sql, { rs, _ ->
            val groupKeys = groupBy.zip(groupCols).associate { (dimId, col) -> dimId to rs.getLong(col) }
            val values = metricAliases.associate { (m, alias) ->
                m.nome to (rs.getBigDecimal(alias) ?: BigDecimal.ZERO)
            }
            AggregateRow(groupKeys, values)
        }, *args.toTypedArray())

        val truncated = rawRows.size > limit
        return AggregateResult(if (truncated) rawRows.take(limit) else rawRows, truncated)
    }

    private fun aliasOf(pairs: List<Pair<AreaMetrica, String>>, metrica: AreaMetrica?): String =
        pairs.first { it.first.id == metrica?.id }.second

    /** Aggiunge a ogni riga le etichette leggibili delle chiavi di raggruppamento. */
    private fun withLabels(
        result: AggregateResult,
        groupBy: List<UUID>,
        dimById: Map<UUID, AreaDimensione>
    ): AggregateResult {
        if (groupBy.isEmpty() || result.rows.isEmpty()) return result

        // Una chiamata per dimensione con tutti gli id in blocco, non una
        // per riga: con 10.000 righe la differenza è fra una query e 10.000.
        val labelsByDim: Map<UUID, Map<Long, String>> = groupBy.mapNotNull { dimId ->
            val dimensione = registryRepository.findDimensione(dimId) ?: return@mapNotNull null
            val ids = result.rows.mapNotNull { it.groupKeys[dimId] }.toSet()
            dimId to symbolLookupService.resolveLabels(dimensione.nome, ids)
        }.toMap()

        return AggregateResult(
            result.rows.map { row ->
                row.copy(labels = row.groupKeys.mapNotNull { (dimId, valueId) ->
                    labelsByDim[dimId]?.get(valueId)?.let { dimId to it }
                }.toMap())
            },
            result.truncated
        )
    }

    private fun sortByLabel(result: AggregateResult, groupBy: List<UUID>): AggregateResult {
        if (groupBy.isEmpty()) return result
        val comparator = compareBy<AggregateRow> { row ->
            groupBy.joinToString("\u0000") { dimId ->
                row.labels[dimId] ?: row.groupKeys[dimId]?.toString() ?: ""
            }
        }
        return AggregateResult(result.rows.sortedWith(comparator), result.truncated)
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
            val safeCol = requireIdentifier(col, "column")
            whereClauses += "$safeCol IN (${values.joinToString(",") { "?" }})"
            args.addAll(values)
        }

        return whereClauses.joinToString(" AND ") to args
    }

    /**
     * Gli identificatori arrivano dal registry, dove Naming li ha già
     * normalizzati alla creazione. Qui si verifica soltanto, come difesa
     * contro SQL injection su dati preesistenti o migrati a mano.
     */
    private fun requireIdentifier(value: String, what: String): String {
        val normalized = Naming.slug(value)
        require(normalized == value) {
            "Identificatore $what non normalizzato nel registry: '$value' (atteso '$normalized')."
        }
        return value
    }

    private fun buildCacheKey(
        req: AggregateRequest,
        selections: Map<UUID, Set<Long>>,
        groupBy: List<UUID>,
        metriche: List<AreaMetrica>,
        limit: Int,
        versions: VersionSnapshot
    ): String {
        val canonicalSelections = selections.entries
            .sortedBy { it.key.toString() }
            .joinToString(";") { (dimId, values) -> "$dimId=${values.sorted().joinToString(",")}" }
        // groupBy NON va ordinato: l'ordine delle colonne di raggruppamento
        // è significativo (cambia la gerarchia del risultato).
        val canonicalGroupBy = groupBy.joinToString(",")
        val canonicalMetrics = metriche.map { it.id.toString() }.sorted().joinToString(",")

        val raw = buildString {
            append(req.areaId); append('|')
            append(canonicalSelections); append('|')
            append(canonicalGroupBy); append('|')
            append(canonicalMetrics); append('|')
            append(req.order); append('|')
            append(req.orderMetricId); append('|')
            append(limit); append('|')
            append(req.resolveLabels); append('|')
            append(versions.registryVersion); append('|')
            append(versions.dataVersion)
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return "agg-state:" + HexFormat.of().formatHex(digest)
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