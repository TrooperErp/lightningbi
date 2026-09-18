package com.lightningbi.lightning_engine.service

import tools.jackson.databind.ObjectMapper
import com.lightningbi.lightning_engine.model.AggregateOrder
import com.lightningbi.lightning_engine.model.AggregateRequest
import com.lightningbi.lightning_engine.model.AggregateResult
import com.lightningbi.lightning_engine.model.AggregateRow
import com.lightningbi.lightning_engine.model.AreaDimensione
import com.lightningbi.lightning_engine.model.AreaMetrica
import com.lightningbi.lightning_engine.model.TipoAggregazione
import com.lightningbi.lightning_engine.model.VersionSnapshot
import com.lightningbi.lightning_engine.repository.RegistryRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
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
        val cleanColumnBy = req.columnBy.filter { it in validDimIds && it !in cleanGroupBy }.distinct()

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

        val cacheKey = buildCacheKey(req, cleanSelections, cleanGroupBy, cleanColumnBy, metriche, effectiveLimit, versions)

        // DEBUG TEMPORANEO: bypassa la cache per essere sicuri di vedere
        // dati freschi durante l'indagine sul bug anno 2026 mancante.
        println("DEBUG AGGREGATE: cleanGroupBy=$cleanGroupBy cleanColumnBy=$cleanColumnBy")

        safeGet(cacheKey)?.let { cached ->
            try {
                return deserialize(cached)
            } catch (e: Exception) {
                log.warn("Aggregate cache deserialization failed for $cacheKey, recomputing", e)
            }
        }

        var flat = computeFlatAggregates(
            table = area.tabellaFisica,
            selections = cleanSelections,
            groupBy = cleanGroupBy,
            columnBy = cleanColumnBy,
            dimById = dimById,
            metriche = metriche,
            order = if (cleanColumnBy.isEmpty()) req.order else null,
            orderMetrica = orderMetrica,
            limit = if (cleanColumnBy.isEmpty()) effectiveLimit else rowLimit
        )

        // DEBUG TEMPORANEO
        println("DEBUG AGGREGATE: flat.rows.size=${flat.rows.size} truncated=${flat.truncated}")
        if (cleanColumnBy.isNotEmpty()) {
            val colDim = cleanColumnBy.first()
            val valoriDistinti = flat.rows.mapNotNull { it.groupKeys[colDim] }.distinct().sorted()
            println("DEBUG AGGREGATE: valori distinti per columnBy[0] ($colDim) = $valoriDistinti")
        }

        var result = if (cleanColumnBy.isEmpty()) {
            flat
        } else {
            pivotByColumns(flat, cleanGroupBy, cleanColumnBy, dimById, metriche, req.showVariationPercent, effectiveLimit)
        }

        if (req.resolveLabels || req.order == AggregateOrder.DIMENSION) {
            result = withLabels(result, cleanGroupBy, dimById)
            if (req.order == AggregateOrder.DIMENSION) {
                result = sortByLabel(result, cleanGroupBy)
            }
            if (!req.resolveLabels) {
                result = AggregateResult(result.rows.map { it.copy(labels = emptyMap()) }, result.truncated)
            }
        }

        // DEBUG TEMPORANEO: quante chiavi distinte finiscono nel risultato finale
        val allKeysFinal = result.rows.flatMap { it.values.keys }.distinct()
        println("DEBUG AGGREGATE: chiavi finali distinte (prime 30) = ${allKeysFinal.take(30)}")
        println("DEBUG AGGREGATE: chiavi finali totali = ${allKeysFinal.size}")

        safeSet(cacheKey, serialize(result))
        return result
    }

    fun buildRowHierarchy(
        areaId: UUID,
        result: AggregateResult,
        groupBy: List<UUID>,
        metricIds: List<UUID>
    ): List<PivotEngine.PivotNode> {
        if (groupBy.isEmpty() || result.rows.isEmpty()) return emptyList()

        val tutteMetriche = registryRepository.findMetricheByArea(areaId)
        val metriche = if (metricIds.isEmpty()) tutteMetriche
        else tutteMetriche.filter { it.id in metricIds.toSet() }

        val dimensioni = registryRepository.findDimensioniByArea(areaId)
        val colonnaFisicaByDim = dimensioni.associate { it.dimensioneId to it.colonnaFisica }

        fun labelFor(dimId: UUID, valueId: Long): String {
            val colonna = colonnaFisicaByDim[dimId]
            if (colonna != null) {
                DimensionFormatters.formatOrNull(colonna, valueId)?.let { return it }
            }
            return result.rows.firstOrNull { it.groupKeys[dimId] == valueId }?.labels?.get(dimId) ?: "#$valueId"
        }

        val dims = registryRepository.findDimensioniByArea(areaId)
        val dimByIdLocal = dims.associateBy { it.dimensioneId }

        val tree = PivotEngine.buildHierarchy(result.rows, groupBy, metriche, ::labelFor) { dimId -> dimByIdLocal[dimId]?.colonnaFisica }

        // DEBUG TEMPORANEO: quante chiavi distinte esistono nell'intero
        // albero delle righe, per confronto con quelle viste in UI.
        val allKeysInTree = LinkedHashSet<String>()
        fun visit(nodes: List<PivotEngine.PivotNode>) {
            nodes.forEach { n -> allKeysInTree.addAll(n.values.keys); visit(n.children) }
        }
        visit(tree)
        println("DEBUG ROWHIERARCHY: chiavi distinte nell'albero righe (prime 30) = ${allKeysInTree.take(30)}")
        println("DEBUG ROWHIERARCHY: chiavi distinte totali nell'albero righe = ${allKeysInTree.size}")

        return tree
    }

    // ================= Query piatta =================

    private fun computeFlatAggregates(
        table: String,
        selections: Map<UUID, Set<Long>>,
        groupBy: List<UUID>,
        columnBy: List<UUID>,
        dimById: Map<UUID, AreaDimensione>,
        metriche: List<AreaMetrica>,
        order: AggregateOrder?,
        orderMetrica: AreaMetrica?,
        limit: Int
    ): AggregateResult {
        val t = requireIdentifier(table, "table")

        metriche.forEach { m ->
            require(m.colonnaFisica != null || m.tipoAggregazione == TipoAggregazione.COUNT) {
                "Metrica '${m.nome}': colonnaFisica nulla non consentita per ${m.tipoAggregazione}"
            }
            m.colonnaFisica?.let { requireIdentifier(it, "metric column") }
        }

        val allGroupDims = groupBy + columnBy
        val groupCols = allGroupDims.mapNotNull { dimById[it]?.colonnaFisica }
        groupCols.forEach { requireIdentifier(it, "group column") }

        val (where, args) = buildWhere(selections, dimById)

        val metricAliases = metriche.mapIndexed { i, m -> m to "m_$i" }
        val selectCols = groupCols +
                metricAliases.map { (m, alias) -> "${sqlExpression(m)} AS $alias" }

        val groupClause = if (groupCols.isEmpty()) "" else "GROUP BY ${groupCols.joinToString(",")}"
        val whereClause = if (where.isEmpty()) "" else "WHERE $where"

        val orderClause = when (order) {
            AggregateOrder.METRIC_DESC -> "ORDER BY ${aliasOf(metricAliases, orderMetrica)} DESC"
            AggregateOrder.METRIC_ASC -> "ORDER BY ${aliasOf(metricAliases, orderMetrica)} ASC"
            else -> ""
        }

        val sql = "SELECT ${selectCols.joinToString(",")} FROM $t $whereClause $groupClause $orderClause LIMIT ${limit + 1}"

        // DEBUG TEMPORANEO: la query esatta generata, per verificare che
        // includa davvero la colonna anno nel SELECT e nel GROUP BY.
        println("DEBUG SQL: $sql")

        val rawRows = jdbcTemplate.query(sql, { rs, _ ->
            val groupKeys = allGroupDims.zip(groupCols).associate { (dimId, col) -> dimId to rs.getLong(col) }
            val values = metricAliases.associate { (m, alias) ->
                m.nome to (rs.getBigDecimal(alias) ?: BigDecimal.ZERO)
            }
            AggregateRow(groupKeys, values)
        }, *args.toTypedArray())

        val truncated = rawRows.size > limit
        return AggregateResult(if (truncated) rawRows.take(limit) else rawRows, truncated)
    }

    private fun pivotByColumns(
        flat: AggregateResult,
        groupBy: List<UUID>,
        columnBy: List<UUID>,
        dimById: Map<UUID, AreaDimensione>,
        metriche: List<AreaMetrica>,
        showVariationPercent: Boolean,
        limit: Int
    ): AggregateResult {
        if (flat.rows.isEmpty()) return flat

        val colonnaFisicaByDim: Map<UUID, String> = columnBy.associateWith { dimId ->
            dimById[dimId]?.colonnaFisica ?: ""
        }
        val labelsByColumnDim: Map<UUID, Map<Long, String>> = columnBy.associateWith { dimId ->
            val dimensione = registryRepository.findDimensione(dimId) ?: return@associateWith emptyMap()
            val ids = flat.rows.mapNotNull { it.groupKeys[dimId] }.toSet()
            symbolLookupService.resolveLabels(dimensione.nome, ids)
        }

        // DEBUG TEMPORANEO
        println("DEBUG PIVOT: labelsByColumnDim = $labelsByColumnDim")

        fun labelFor(dimId: UUID, valueId: Long): String {
            val colonna = colonnaFisicaByDim[dimId]
            if (colonna != null) {
                DimensionFormatters.formatOrNull(colonna, valueId)?.let { return it }
            }
            return labelsByColumnDim[dimId]?.get(valueId) ?: "#$valueId"
        }

        val grouped = flat.rows.groupBy { row -> groupBy.associateWith { row.groupKeys[it] } }

        // DEBUG TEMPORANEO
        println("DEBUG PIVOT: numero gruppi (righe pivot attese) = ${grouped.size}")

        var debugPrinted = 0

        val pivotRows = grouped.entries.take(limit).map { (groupKeyPartial, flatRowsInGroup) ->
            val groupKeys = groupKeyPartial.mapNotNull { (dimId, v) -> v?.let { dimId to it } }.toMap()

            val tree = PivotEngine.buildHierarchy(flatRowsInGroup, columnBy, metriche, ::labelFor) { dimId -> dimById[dimId]?.colonnaFisica }
            val leafPaths = PivotEngine.flattenLeafPaths(tree)

            // DEBUG TEMPORANEO: stampa il dettaglio solo per i primi 3
            // gruppi, per non inondare i log.
            if (debugPrinted < 3) {
                println("DEBUG PIVOT: gruppo=$groupKeys flatRowsInGroup.size=${flatRowsInGroup.size}")
                println("DEBUG PIVOT:   anni presenti nel gruppo = ${flatRowsInGroup.map { it.groupKeys[columnBy.first()] }}")
                println("DEBUG PIVOT:   leafPaths = ${leafPaths.map { it.first }}")
                debugPrinted++
            }

            val values = mutableMapOf<String, BigDecimal>()
            leafPaths.forEach { (path, node) ->
                val suffix = path.joinToString("|")
                metriche.forEach { m ->
                    val v = node.values[m.nome] ?: BigDecimal.ZERO
                    values["${m.nome}|$suffix"] = v
                }
            }

            if (showVariationPercent && columnBy.isNotEmpty()) {
                addVariationColumns(values, flatRowsInGroup, columnBy, metriche, ::labelFor)
            }

            AggregateRow(groupKeys = groupKeys, values = values)
        }

        return AggregateResult(pivotRows, flat.truncated || grouped.size > limit)
    }

    private fun addVariationColumns(
        values: MutableMap<String, BigDecimal>,
        flatRowsInGroup: List<AggregateRow>,
        columnBy: List<UUID>,
        metriche: List<AreaMetrica>,
        labelFor: (UUID, Long) -> String
    ) {
        val outerDims = columnBy.dropLast(1)
        val innerDim = columnBy.last()

        val byOuter = flatRowsInGroup.groupBy { row ->
            outerDims.map { dimId -> row.groupKeys[dimId]?.let { labelFor(dimId, it) } ?: "—" }
        }

        byOuter.forEach { (outerLabels, rowsInOuter) ->
            val ordered = rowsInOuter.sortedBy { row ->
                row.groupKeys[innerDim]?.let { labelFor(innerDim, it) } ?: ""
            }
            for (i in 1 until ordered.size) {
                val prevRow = ordered[i - 1]
                val currRow = ordered[i]
                val prevLabel = prevRow.groupKeys[innerDim]?.let { labelFor(innerDim, it) } ?: continue
                val currLabel = currRow.groupKeys[innerDim]?.let { labelFor(innerDim, it) } ?: continue

                metriche.forEach { m ->
                    val prevVal = prevRow.values[m.nome] ?: BigDecimal.ZERO
                    val currVal = currRow.values[m.nome] ?: BigDecimal.ZERO
                    val variation = if (prevVal.compareTo(BigDecimal.ZERO) == 0) {
                        null
                    } else {
                        currVal.subtract(prevVal)
                            .divide(prevVal, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal(100))
                    }
                    if (variation != null) {
                        val outerPrefix = if (outerLabels.isEmpty()) "" else outerLabels.joinToString("|") + "|"
                        val key = "${m.nome}|${outerPrefix}$prevLabel→$currLabel|Variaz.%"
                        values[key] = variation
                    }
                }
            }
        }
    }

    private fun sqlExpression(m: AreaMetrica): String {
        val col = m.colonnaFisica
        return when (m.tipoAggregazione) {
            TipoAggregazione.COUNT -> if (col != null) "COUNT($col)" else "COUNT(*)"
            TipoAggregazione.COUNT_DISTINCT -> "COUNT(DISTINCT $col)"
            TipoAggregazione.SUM -> "SUM($col)"
            TipoAggregazione.AVG -> "AVG($col)"
            TipoAggregazione.MIN -> "MIN($col)"
            TipoAggregazione.MAX -> "MAX($col)"
        }
    }

    private fun aliasOf(pairs: List<Pair<AreaMetrica, String>>, metrica: AreaMetrica?): String =
        pairs.first { it.first.id == metrica?.id }.second

    private fun withLabels(
        result: AggregateResult,
        groupBy: List<UUID>,
        dimById: Map<UUID, AreaDimensione>
    ): AggregateResult {
        if (groupBy.isEmpty() || result.rows.isEmpty()) return result

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
        columnBy: List<UUID>,
        metriche: List<AreaMetrica>,
        limit: Int,
        versions: VersionSnapshot
    ): String {
        val canonicalSelections = selections.entries
            .sortedBy { it.key.toString() }
            .joinToString(";") { (dimId, values) -> "$dimId=${values.sorted().joinToString(",")}" }
        val canonicalGroupBy = groupBy.joinToString(",")
        val canonicalColumnBy = columnBy.joinToString(",")
        val canonicalMetrics = metriche.map { it.id.toString() }.sorted().joinToString(",")

        val raw = buildString {
            append(req.areaId); append('|')
            append(canonicalSelections); append('|')
            append(canonicalGroupBy); append('|')
            append(canonicalColumnBy); append('|')
            append(canonicalMetrics); append('|')
            append(req.order); append('|')
            append(req.orderMetricId); append('|')
            append(limit); append('|')
            append(req.resolveLabels); append('|')
            append(req.showVariationPercent); append('|')
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