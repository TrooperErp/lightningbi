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
        // columnBy è ammesso a N dimensioni (stile Qlik: annidamento per
        // livelli, ordine = ordine della lista). Nessun limite a 1: un
        // limite artificiale rende lo strumento inservibile per analisi
        // che domani vorranno più di un livello (es. Anno > Trimestre).
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

        // Query piatta: una riga per ogni combinazione (groupBy × columnBy).
        // Il pivot vero (righe → colonne annidate) avviene dopo, in Kotlin:
        // più semplice da mantenere e indipendente dal motore SQL sotto.
        var flat = computeFlatAggregates(
            table = area.tabellaFisica,
            selections = cleanSelections,
            groupBy = cleanGroupBy,
            columnBy = cleanColumnBy,
            dimById = dimById,
            metriche = metriche,
            order = if (cleanColumnBy.isEmpty()) req.order else null, // l'order SQL vale solo senza pivot
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

    /**
     * Costruisce la gerarchia per l'asse Righe (Agente > Mese, o
     * qualunque lista di dimensioni in "groupBy"), a partire da un
     * AggregateResult già calcolato da getAggregates() sulla stessa area.
     * Esattamente come Excel/Qlik: l'aggregazione produce la struttura
     * gerarchica pronta, la UI (TreeGrid) si limita a disegnarla.
     *
     * Richiede che il risultato passato abbia resolveLabels=true (le
     * label già risolte in ogni riga), altrimenti l'ordinamento e le
     * etichette dei nodi userebbero l'id grezzo come fallback.
     */
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
        return PivotEngine.buildHierarchy(result.rows, groupBy, metriche, ::labelFor) { dimId -> dimByIdLocal[dimId]?.colonnaFisica }
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

        // colonnaFisica è null SOLO per COUNT: ogni altra aggregazione la
        // richiede. Verificato qui perché è l'unico punto che genera SQL,
        // ma la regola vive già in RegistryService.addMetrica al momento
        // della creazione - qui è una difesa contro dati incoerenti scritti
        // altrove (migrazioni, inserimenti a mano).
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

    /**
     * Trasforma le righe piatte (una per combinazione groupBy × columnBy)
     * in righe pivot (una per combinazione groupBy, con le colonne di
     * columnBy annidate dentro le chiavi di "values").
     *
     * Usa PivotEngine.buildHierarchy sul SOLO asse columnBy per ogni
     * gruppo di groupBy: stesso motore ricorsivo generico usato (o da
     * usare) anche per l'asse Righe in TreeGrid, nessuna logica duplicata
     * per "come annidare N dimensioni".
     */
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

            // Alberatura del solo asse columnBy per questo gruppo di righe:
            // ogni percorso radice→foglia è una combinazione di valori di
            // columnBy, esattamente come prima ma ora via motore condiviso.
            val tree = PivotEngine.buildHierarchy(flatRowsInGroup, columnBy, metriche, ::labelFor) { dimId -> dimById[dimId]?.colonnaFisica }
            val leafPaths = PivotEngine.flattenLeafPaths(tree)

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

    /**
     * Aggiunge a "values" (in place) le colonne di variazione percentuale
     * fra coppie di valori consecutivi dell'ultimo livello di columnBy,
     * ordinati per label. Chiave generata: "nomeMetrica|...livelliEsterni|
     * labelPrecedente→labelCorrente|Variaz.%".
     *
     * "Consecutivo" è per posizione nell'ordinamento alfabetico delle
     * label del livello più interno, a parità degli eventuali livelli
     * esterni - così "Anno=2025,Trim=Q4" si confronta con
     * "Anno=2026,Trim=Q1" solo se sono effettivamente adiacenti in
     * quell'ordinamento, non forzatamente anno-su-anno.
     */
    private fun addVariationColumns(
        values: MutableMap<String, BigDecimal>,
        flatRowsInGroup: List<AggregateRow>,
        columnBy: List<UUID>,
        metriche: List<AreaMetrica>,
        labelFor: (UUID, Long) -> String
    ) {
        val outerDims = columnBy.dropLast(1)
        val innerDim = columnBy.last()

        // Raggruppa per combinazione dei livelli esterni (se presenti),
        // poi ordina il livello interno per label dentro ogni gruppo.
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
                        null // divisione per zero: nessuna variazione calcolabile, colonna omessa per questa coppia
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

    /**
     * Traduce l'aggregazione nella sintassi SQL corretta.
     *
     * COUNT senza colonna diventa COUNT(*) letterale, non COUNT(null) che
     * ClickHouse rifiuterebbe. COUNT_DISTINCT non è una funzione SQL: va
     * tradotta come COUNT(DISTINCT colonna) - usare uniqExact darebbe un
     * conteggio esatto più veloce su ClickHouse, ma COUNT(DISTINCT ...) è
     * standard SQL e resta portabile se un domani cambia il motore fatti.
     */
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