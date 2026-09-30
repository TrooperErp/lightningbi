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
    private val symbolLookupService: SymbolLookupService,
    private val starQueryBuilder: StarQueryBuilder
) {
    private val log = LoggerFactory.getLogger(AggregateService::class.java)
    private val cacheTtl = Duration.ofHours(6)
    private val rowLimit = 10_000
    private val maxColonnePivot = 50

    fun getAggregates(req: AggregateRequest): AggregateResult =
        getAggregates(req, versionService.snapshotVersions(req.areaId))

    fun getAggregates(req: AggregateRequest, versions: VersionSnapshot): AggregateResult {
        registryRepository.findAreaById(req.areaId) ?: error("Area not found: ${req.areaId}")
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

        safeGet(cacheKey)?.let { cached ->
            try {
                return deserialize(cached)
            } catch (e: Exception) {
                log.warn("Aggregate cache deserialization failed for $cacheKey, recomputing", e)
            }
        }

        var flat = computeFlatAggregates(
            areaId = req.areaId,
            selections = cleanSelections,
            groupBy = cleanGroupBy,
            columnBy = cleanColumnBy,
            dimById = dimById,
            metriche = metriche,
            order = if (cleanColumnBy.isEmpty()) req.order else null,
            orderMetrica = orderMetrica,
            limit = if (cleanColumnBy.isEmpty()) effectiveLimit else rowLimit
        )

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
        val colonnaFisicaByDim = dimensioni.filter { it.valoreGrezzo }.associate { it.dimensioneId to it.colonnaFisica }

        fun labelFor(dimId: UUID, valueId: Long): String {
            val colonna = colonnaFisicaByDim[dimId]
            if (colonna != null) {
                DimensionFormatters.formatOrNull(colonna, valueId)?.let { return it }
            }
            return result.rows.firstOrNull { it.groupKeys[dimId] == valueId }?.labels?.get(dimId) ?: "#$valueId"
        }

        val tree = PivotEngine.buildHierarchy(result.rows, groupBy, metriche, ::labelFor) { dimId -> colonnaFisicaByDim[dimId] }

        return tree
    }

    // ================= Query piatta =================

    private fun computeFlatAggregates(
        areaId: UUID,
        selections: Map<UUID, Set<Long>>,
        groupBy: List<UUID>,
        columnBy: List<UUID>,
        dimById: Map<UUID, AreaDimensione>,
        metriche: List<AreaMetrica>,
        order: AggregateOrder?,
        orderMetrica: AreaMetrica?,
        limit: Int
    ): AggregateResult {
        // Dimensioni realmente usate dalla richiesta: solo le loro tabelle
        // entrano nel JOIN (schema a stella).
        val allGroupDims = groupBy + columnBy
        val dimsUsate = allGroupDims.toSet() + selections.filterValues { it.isNotEmpty() }.keys
        val plan = starQueryBuilder.plan(areaId, dimsUsate, dimById.values.toList())
        // Il nome tabella è già validato dal builder (i nomi
        // <motore>_<db>__<nome> non passano da Naming.slug).
        val t = plan.fromClause

        metriche.forEach { m ->
            require(m.colonnaFisica != null || m.tipoAggregazione == TipoAggregazione.COUNT) {
                "Metrica '${m.nome}': colonnaFisica nulla non consentita per ${m.tipoAggregazione}"
            }
            m.colonnaFisica?.let { requireIdentifier(it, "metric column") }
        }

        allGroupDims.mapNotNull { dimById[it]?.colonnaFisica }.forEach { requireIdentifier(it, "group column") }
        val groupCols = allGroupDims.mapNotNull { plan.dimColumn(it) }

        // Le colonne sono qualificate (d0.x, f.y): si danno alias espliciti
        // per leggerle per nome dal risultato.
        val groupAliases = groupCols.indices.map { "g_$it" }
        val groupSelect = groupCols.mapIndexed { i, c -> "$c AS g_$i" }

        val (where, args) = buildWhere(selections, dimById, plan)

        val metricAliases = metriche.mapIndexed { i, m -> m to "m_$i" }
        val selectCols = groupSelect +
                metricAliases.map { (m, alias) -> "${sqlExpression(m, plan)} AS $alias" }

        val groupClause = if (groupCols.isEmpty()) "" else "GROUP BY ${groupCols.joinToString(",")}"
        val whereClause = if (where.isEmpty()) "" else "WHERE $where"

        val orderClause = when (order) {
            AggregateOrder.METRIC_DESC -> "ORDER BY ${aliasOf(metricAliases, orderMetrica)} DESC"
            AggregateOrder.METRIC_ASC -> "ORDER BY ${aliasOf(metricAliases, orderMetrica)} ASC"
            else -> ""
        }

        val sql = "SELECT ${selectCols.joinToString(",")} FROM $t $whereClause $groupClause $orderClause LIMIT ${limit + 1}"

        val rawRows = jdbcTemplate.query(sql, { rs, _ ->
            val groupKeys = allGroupDims.zip(groupAliases).associate { (dimId, col) -> dimId to rs.getLong(col) }
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
        // Blocco preventivo: una dimensione ad alta cardinalità (es. un id
        // documento) in Colonne genererebbe una colonna Vaadin per ogni
        // valore distinto - con migliaia di valori la UI tenta di costruire
        // altrettante migliaia di colonne, bloccando il browser e il server
        // senza un errore chiaro. Va fermato qui, prima di costruire
        // l'albero, non lasciato esplodere silenziosamente.
        columnBy.forEach { dimId ->
            val nomeColonna = dimById[dimId]?.colonnaFisica ?: dimId.toString()
            val valoriDistinti = flat.rows.mapNotNull { it.groupKeys[dimId] }.distinct().size
            require(valoriDistinti <= maxColonnePivot) {
                "La dimensione '$nomeColonna' ha $valoriDistinti valori distinti: troppi per essere usata in Colonne " +
                        "(massimo $maxColonnePivot). Usa questa dimensione in Righe, oppure applica un filtro prima."
            }
        }

        val colonnaFisicaByDim: Map<UUID, String> = columnBy.mapNotNull { dimId ->
            dimById[dimId]?.takeIf { it.valoreGrezzo }?.let { dimId to it.colonnaFisica }
        }.toMap()
        val labelsByColumnDim: Map<UUID, Map<Long, String>> = columnBy.associateWith { dimId ->
            val dimensione = registryRepository.findDimensione(dimId) ?: return@associateWith emptyMap()
            val ids = flat.rows.mapNotNull { it.groupKeys[dimId] }.toSet()
            symbolLookupService.resolveLabels(dimensione.nome, ids)
        }

        fun labelFor(dimId: UUID, valueId: Long): String {
            val colonna = colonnaFisicaByDim[dimId]
            if (colonna != null) {
                DimensionFormatters.formatOrNull(colonna, valueId)?.let { return it }
            }
            return labelsByColumnDim[dimId]?.get(valueId) ?: "#$valueId"
        }

        val grouped = flat.rows.groupBy { row -> groupBy.associateWith { row.groupKeys[it] } }

        val pivotRows = grouped.entries.take(limit).map { (groupKeyPartial, flatRowsInGroup) ->
            val groupKeys = groupKeyPartial.mapNotNull { (dimId, v) -> v?.let { dimId to it } }.toMap()

            val tree = PivotEngine.buildHierarchy(flatRowsInGroup, columnBy, metriche, ::labelFor) { dimId -> colonnaFisicaByDim[dimId] }
            val leafPaths = PivotEngine.flattenLeafPaths(tree)

            val values = mutableMapOf<String, BigDecimal>()
            leafPaths.forEach { (path, node) ->
                val suffix = path.filter { it.isNotEmpty() }.joinToString("|")
                metriche.forEach { m ->
                    val v = node.values[m.nome] ?: BigDecimal.ZERO
                    values["${m.nome}|$suffix"] = v
                }
            }

            if (showVariationPercent && columnBy.isNotEmpty()) {
                addVariationColumns(values, flatRowsInGroup, columnBy, metriche, ::labelFor, colonnaFisicaByDim)
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
        labelFor: (UUID, Long) -> String,
        colonnaFisicaByDim: Map<UUID, String>
    ) {
        val outerDims = columnBy.dropLast(1)
        val innerDim = columnBy.last()
        val ordineNaturale = colonnaFisicaByDim[innerDim]?.let { DimensionSortOrders.usesNaturalOrder(it) } == true

        val byOuter = flatRowsInGroup.groupBy { row ->
            outerDims.map { dimId -> row.groupKeys[dimId]?.let { labelFor(dimId, it) } ?: "—" }
        }

        byOuter.forEach { (outerLabels, rowsInOuter) ->
            // Precedente/corrente: per le dimensioni con ordine naturale (es.
            // mese_numero) si ordina sul valore grezzo; altrimenti sull'etichetta
            // con confronto naturale (i numeri si confrontano come numeri:
            // "9" prima di "10"), non in ordine alfabetico puro.
            val ordered = if (ordineNaturale) {
                rowsInOuter.sortedBy { row -> row.groupKeys[innerDim] ?: Long.MAX_VALUE }
            } else {
                rowsInOuter.sortedWith { r1, r2 ->
                    confrontoNaturale(
                        r1.groupKeys[innerDim]?.let { labelFor(innerDim, it) } ?: "",
                        r2.groupKeys[innerDim]?.let { labelFor(innerDim, it) } ?: ""
                    )
                }
            }
            // La % di variazione ha senso solo per un confronto A/B netto fra
            // esattamente due valori (es. 2025 vs 2026): con 1 o 3+ valori il
            // confronto "ultimo vs penultimo" sarebbe parziale e fuorviante,
            // quindi la colonna Variaz.% non viene generata affatto.
            if (ordered.size != 2) return@forEach

            val prevRow = ordered[0]
            val currRow = ordered[1]
            val prevLabel = prevRow.groupKeys[innerDim]?.let { labelFor(innerDim, it) } ?: return@forEach
            val currLabel = currRow.groupKeys[innerDim]?.let { labelFor(innerDim, it) } ?: return@forEach

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

    /**
     * Confronto "naturale" tra etichette: le sequenze di cifre si confrontano
     * come numeri (9 < 10), il resto carattere per carattere senza distinguere
     * maiuscole e minuscole.
     */
    private fun confrontoNaturale(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var ie = i
                while (ie < a.length && a[ie].isDigit()) ie++
                var je = j
                while (je < b.length && b[je].isDigit()) je++
                val na = a.substring(i, ie).trimStart('0')
                val nb = b.substring(j, je).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ie
                j = je
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    private fun sqlExpression(m: AreaMetrica, plan: StarQueryBuilder.Plan): String {
        val col = m.colonnaFisica?.let { plan.metricColumn(it) }
        return when (m.tipoAggregazione) {
            TipoAggregazione.COUNT -> if (col != null) "COUNT($col)" else "COUNT(*)"
            TipoAggregazione.COUNT_DISTINCT -> "COUNT(DISTINCT $col)"
            TipoAggregazione.SUM -> "SUM($col)"
            TipoAggregazione.AVG -> "toDecimal64(AVG($col), 4)"
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
        dimById: Map<UUID, AreaDimensione>,
        plan: StarQueryBuilder.Plan
    ): Pair<String, List<Any>> {
        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<Any>()

        selections.forEach { (dimId, values) ->
            if (values.isEmpty()) return@forEach
            val grezza = dimById[dimId]?.colonnaFisica ?: return@forEach
            requireIdentifier(grezza, "column")
            val col = plan.dimColumn(dimId) ?: return@forEach
            whereClauses += "$col IN (${values.joinToString(",") { "?" }})"
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