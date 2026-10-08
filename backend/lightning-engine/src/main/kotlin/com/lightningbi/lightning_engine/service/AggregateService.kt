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
import org.springframework.jdbc.core.RowMapper

@Service
class AggregateService(
    private val jdbcTemplate: JdbcTemplate,
    private val registryRepository: RegistryRepository,
    private val versionService: VersionService,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val symbolLookupService: SymbolLookupService,
    private val starQueryBuilder: StarQueryBuilder,
    private val modelloDatasetCache: ModelloDatasetCache,
    private val sezioneAccessoService: SezioneAccessoService
) {

    private val log = LoggerFactory.getLogger(AggregateService::class.java)
    private val cacheTtl = Duration.ofHours(6)
    private val rowLimit = 10_000
    private val maxColonnePivot = 50
    /** Righe massime per Fatti quando i risultati di più Fatti si uniscono in memoria. */
    private val maxRigheUnione = 100_000

    fun getAggregates(req: AggregateRequest): AggregateResult =
        getAggregates(req, versionService.snapshotVersions(req.areaId))

    fun getAggregates(req: AggregateRequest, versions: VersionSnapshot): AggregateResult {
        registryRepository.findAreaById(req.areaId) ?: error("Area not found: ${req.areaId}")
        val dims = registryRepository.findDimensioniByArea(req.areaId)
        val dimById = dims.associateBy { it.dimensioneId }
        val tutteMetriche = registryRepository.findMetricheByArea(req.areaId) + req.misure

        val validDimIds = dims.map { it.dimensioneId }.toSet()
        // Section access: per un non-admin la selezione sull'azienda è forzata.
        val vincolo = sezioneAccessoService.vincolo(req.areaId)
        val selezioniEffettive =
            if (vincolo == null) req.selections else req.selections + (vincolo.dimensioneId to vincolo.ids)
        val cleanSelections = selezioniEffettive
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
            limit = if (cleanColumnBy.isEmpty()) effectiveLimit else rowLimit,
            registryVersion = versions.registryVersion,
            forzata = vincolo?.dimensioneId
        )

        var result = if (cleanColumnBy.isEmpty()) {
            flat
        } else {
            pivotByColumns(flat, cleanGroupBy, cleanColumnBy, dimById, metriche, req.showVariationPercent, effectiveLimit)
        }

        if (req.resolveLabels || req.order == AggregateOrder.DIMENSION) {
            result = withLabels(result, cleanGroupBy, dimById)
            if (req.order == AggregateOrder.DIMENSION) {
                result = ordinaPerDimensione(result, cleanGroupBy, dimById)
            }
            if (!req.resolveLabels) {
                result = result.copy(rows = result.rows.map { it.copy(labels = emptyMap()) })
            }
        }

        safeSet(cacheKey, serialize(result))
        return result
    }

    fun buildRowHierarchy(
        areaId: UUID,
        result: AggregateResult,
        groupBy: List<UUID>,
        metricIds: List<UUID>,
        misure: List<AreaMetrica> = emptyList(),
        totali: Map<List<Long>, Map<String, BigDecimal>> = emptyMap()
    ): List<PivotEngine.PivotNode> {
        if (groupBy.isEmpty() || result.rows.isEmpty()) return emptyList()

        val tutteMetriche = registryRepository.findMetricheByArea(areaId) + misure
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


        val numeri: Map<UUID, Map<Long, BigDecimal>> = groupBy.associateWith { dimId ->
            val colonna = dimensioni.firstOrNull { it.dimensioneId == dimId }?.colonnaFisica
                ?: return@associateWith emptyMap<Long, BigDecimal>()
            symbolLookupService.resolveNumeri(colonna, result.rows.mapNotNull { it.groupKeys[dimId] }.toSet())
        }
        val tree = PivotEngine.buildHierarchy(
            result.rows, groupBy, metriche, ::labelFor, totali,
            ordineFor = { dimId, valueId -> numeri[dimId]?.get(valueId) }
        ) { dimId -> colonnaFisicaByDim[dimId] }

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
        limit: Int,
        registryVersion: Long,
        forzata: UUID? = null
    ): AggregateResult {
        val modello = modelloDatasetCache.get(areaId, registryVersion)
        val allGroupDims = groupBy + columnBy

        metriche.forEach { m ->
            require(m.colonnaFisica != null || m.tipoAggregazione == TipoAggregazione.COUNT) {
                "Metrica '${m.nome}': colonnaFisica nulla non consentita per ${m.tipoAggregazione}"
            }
            m.colonnaFisica?.let { requireIdentifier(it, "metric column") }
        }
        allGroupDims.mapNotNull { dimById[it]?.colonnaFisica }.forEach { requireIdentifier(it, "group column") }

        // Nome del campo di ogni dimensione, come sta nell'indice.
        val nomi = registryRepository.findDimensioniByIds((allGroupDims + selections.keys).distinct())
            .associate { it.id to Naming.column(it.nome) }
        val selezioniPerCampo = selections.mapNotNull { (dimId, valori) ->
            nomi[dimId]?.takeIf { modello.grafo.tabelleCon(it).isNotEmpty() }?.let { it to valori }
        }.toMap()

        if (forzata != null) {
            val nomeForzato = nomi[forzata]
            if (nomeForzato == null || nomeForzato !in selezioniPerCampo) {
                throw SecurityException("Il campo azienda non è nell'indice del dataset")
            }
        }

        // Ogni metrica si calcola sul PROPRIO Fatti; i risultati dei vari Fatti
        // si affiancano sulle dimensioni in comune, senza mai sommare righe di Fatti diversi.
        val perFatti = metriche.groupBy { m ->
            m.areaTabellaId ?: error("La metrica '${m.nome}' non ha una tabella Fatti: salva di nuovo il dataset")
        }
        val unico = perFatti.size == 1
        val tetto = if (unico) limit else maxRigheUnione

        var troncato = false
        val parziali = perFatti.map { (fattiId, metricheFatti) ->
            val righe = aggregaFatti(
                modello, fattiId, metricheFatti, allGroupDims, dimById, nomi, selezioniPerCampo,
                if (unico) order else null, if (unico) orderMetrica else null, tetto
            )
            if (righe.size > tetto) troncato = true
            righe.take(tetto)
        }
        if (unico) return AggregateResult(parziali.first(), troncato)

        // Più Fatti: unione sulle chiavi di raggruppamento. Una metrica senza riga per
        // quella chiave vale 0 ("non definito").
        val unite = LinkedHashMap<Map<UUID, Long>, MutableMap<String, BigDecimal>>()
        parziali.forEach { righe ->
            righe.forEach { r -> unite.getOrPut(r.groupKeys) { mutableMapOf() }.putAll(r.values) }
        }
        var righe = unite.map { (chiavi, valori) ->
            AggregateRow(chiavi, metriche.associate { m -> m.nome to (valori[m.nome] ?: BigDecimal.ZERO) })
        }
        if (orderMetrica != null) {
            righe = when (order) {
                AggregateOrder.METRIC_DESC -> righe.sortedByDescending { it.values[orderMetrica.nome] ?: BigDecimal.ZERO }
                AggregateOrder.METRIC_ASC -> righe.sortedBy { it.values[orderMetrica.nome] ?: BigDecimal.ZERO }
                else -> righe
            }
        }
        return AggregateResult(righe.take(limit), troncato || righe.size > limit)
    }

    /** Aggrega le metriche di UN Fatti sulle sue righe vive; restituisce fino a limit + 1 righe. */
    private fun aggregaFatti(
        modello: ModelloDataset,
        fattiId: UUID,
        metriche: List<AreaMetrica>,
        allGroupDims: List<UUID>,
        dimById: Map<UUID, AreaDimensione>,
        nomi: Map<UUID, String>,
        selezioniPerCampo: Map<String, Set<Long>>,
        order: AggregateOrder?,
        orderMetrica: AreaMetrica?,
        limit: Int
    ): List<AggregateRow> {
        val usate = allGroupDims.map { dimId ->
            StarQueryBuilder.DimensioneUsata(
                dimId, nomi[dimId] ?: error("Dimensione $dimId senza nome"), dimById[dimId]?.areaTabellaId
            )
        }
        val plan = starQueryBuilder.plan(modello, fattiId, usate, selezioniPerCampo)

        val groupCols = allGroupDims.map { plan.dimColumn(it) }
        val groupAliases = groupCols.indices.map { "g_$it" }
        val groupSelect = groupCols.mapIndexed { i, c -> "$c AS g_$i" }

        // Righe del Fatti compatibili con le selezioni (propagate lungo il grafo).
        val viva = modello.grafo.righeVive(fattiId, selezioniPerCampo)
        val whereClause = if (viva == null) "" else "WHERE bitmapContains($viva, f.${Naming.RID_COLUMN})"

        val metricAliases = metriche.mapIndexed { i, m -> m to "m_$i" }
        val selectCols = groupSelect +
                metricAliases.map { (m, alias) -> "${sqlExpression(m, plan)} AS $alias" }
        val groupClause = if (groupAliases.isEmpty()) "" else "GROUP BY ${groupAliases.joinToString(",")}"
        val orderClause = when (order) {
            AggregateOrder.METRIC_DESC -> "ORDER BY ${aliasOf(metricAliases, orderMetrica)} DESC"
            AggregateOrder.METRIC_ASC -> "ORDER BY ${aliasOf(metricAliases, orderMetrica)} ASC"
            else -> ""
        }

        val sql = "SELECT ${selectCols.joinToString(",")} FROM ${plan.fromClause} $whereClause $groupClause $orderClause LIMIT ${limit + 1}"

        return jdbcTemplate.query(sql, RowMapper<AggregateRow> { rs, _ ->
            val groupKeys = allGroupDims.zip(groupAliases).associate { (dimId, col) -> dimId to rs.getLong(col) }
            val values = metricAliases.associate { (m, alias) ->
                m.nome to (rs.getBigDecimal(alias) ?: BigDecimal.ZERO)
            }
            AggregateRow(groupKeys, values)
        })
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
            // La symbol table è quella del CAMPO: si chiama come la colonna fisica.
            val colonna = dimById[dimId]?.colonnaFisica ?: return@associateWith emptyMap()
            val ids = flat.rows.mapNotNull { it.groupKeys[dimId] }.toSet()
            symbolLookupService.resolveLabels(colonna, ids)
        }

        fun labelFor(dimId: UUID, valueId: Long): String {
            val colonna = colonnaFisicaByDim[dimId]
            if (colonna != null) {
                DimensionFormatters.formatOrNull(colonna, valueId)?.let { return it }
            }
            return labelsByColumnDim[dimId]?.get(valueId) ?: "#$valueId"
        }
        val numeriColonne: Map<UUID, Map<Long, BigDecimal>> = columnBy.associateWith { dimId ->
            val colonna = dimById[dimId]?.colonnaFisica ?: return@associateWith emptyMap<Long, BigDecimal>()
            symbolLookupService.resolveNumeri(colonna, flat.rows.mapNotNull { it.groupKeys[dimId] }.toSet())
        }
        val ordineColonne: (UUID, Long) -> BigDecimal? = { dimId, valueId -> numeriColonne[dimId]?.get(valueId) }

        // Ordine unico delle colonne per tutte le righe (mesi Gen…Dic, non alfabetici).
        val colonneInOrdine = PivotEngine.flattenLeafPaths(
            PivotEngine.buildHierarchy(flat.rows, columnBy, metriche, ::labelFor, ordineFor = ordineColonne) { dimId -> colonnaFisicaByDim[dimId] }
        ).map { (path, _) -> path.filter { it.isNotEmpty() }.joinToString("|") }.distinct()

        val grouped = flat.rows.groupBy { row -> groupBy.associateWith { row.groupKeys[it] } }

        val pivotRows = grouped.entries.take(limit).map { (groupKeyPartial, flatRowsInGroup) ->
            val groupKeys = groupKeyPartial.mapNotNull { (dimId, v) -> v?.let { dimId to it } }.toMap()

            val tree = PivotEngine.buildHierarchy(flatRowsInGroup, columnBy, metriche, ::labelFor, ordineFor = ordineColonne) { dimId -> colonnaFisicaByDim[dimId] }
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

        return AggregateResult(pivotRows, flat.truncated || grouped.size > limit, colonneInOrdine)
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
                    DimensionSortOrders.confrontoNaturale(
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

    private fun sqlExpression(m: AreaMetrica, plan: StarQueryBuilder.Plan): String {
        val colonna = m.colonnaFisica
        // Somma, media, minimo e massimo lavorano sulla copia NUMERICA del campo
        // (i valori mancanti sono null e le aggregazioni li ignorano). I
        // conteggi lavorano sull'id e valgono per qualunque campo, anche di
        // testo; l'id 0 è "non definito" e non si conta.
        val numerica = colonna?.let { plan.metricColumn(Naming.numericColumn(it)) }
        val id = colonna?.let { plan.metricColumn(it) }
        return when (m.tipoAggregazione) {
            TipoAggregazione.COUNT -> if (id != null) "countIf($id != 0)" else "COUNT(*)"
            TipoAggregazione.COUNT_DISTINCT -> "uniqExactIf($id, $id != 0)"
            TipoAggregazione.SUM -> "SUM($numerica)"
            TipoAggregazione.AVG -> "toDecimal64(AVG($numerica), 4)"
            TipoAggregazione.MIN -> "MIN($numerica)"
            TipoAggregazione.MAX -> "MAX($numerica)"
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
            val colonna = dimById[dimId]?.colonnaFisica ?: return@mapNotNull null
            val ids = result.rows.mapNotNull { it.groupKeys[dimId] }.toSet()
            dimId to symbolLookupService.resolveLabels(colonna, ids)
        }.toMap()

        return result.copy(
            rows = result.rows.map { row ->
                row.copy(labels = row.groupKeys.mapNotNull { (dimId, valueId) ->
                    labelsByDim[dimId]?.get(valueId)?.let { dimId to it }
                }.toMap())
            }

        )
    }

    /** Ordina le righe per i valori delle dimensioni di raggruppamento, come in Qlik (vedi OrdinamentoValori). */
    fun ordinaPerDimensione(
        result: AggregateResult,
        groupBy: List<UUID>,
        dimById: Map<UUID, AreaDimensione>
    ): AggregateResult {
        if (groupBy.isEmpty() || result.rows.isEmpty()) return result
        val numeri: Map<UUID, Map<Long, BigDecimal>> = groupBy.associateWith { dimId ->
            val colonna = dimById[dimId]?.colonnaFisica ?: return@associateWith emptyMap<Long, BigDecimal>()
            symbolLookupService.resolveNumeri(colonna, result.rows.mapNotNull { it.groupKeys[dimId] }.toSet())
        }
        val confronto = Comparator<AggregateRow> { a, b ->
            for (dimId in groupBy) {
                val va = a.groupKeys[dimId]
                val vb = b.groupKeys[dimId]
                val c = OrdinamentoValori.confronta(
                    va?.let { numeri[dimId]?.get(it) }, a.labels[dimId] ?: va?.toString() ?: "",
                    vb?.let { numeri[dimId]?.get(it) }, b.labels[dimId] ?: vb?.toString() ?: ""
                )
                if (c != 0) return@Comparator c
            }
            0
        }
        return result.copy(rows = result.rows.sortedWith(confronto))
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
        // Per le misure di un'analisi conta la definizione, non solo l'id: se si cambia campo o aggregazione l'id resta.
        val canonicalMetrics = metriche
            .map { "${it.id}:${it.tipoAggregazione}:${it.colonnaFisica}:${it.areaTabellaId}" }
            .sorted().joinToString(",")

        val raw = buildString {
            append("ord2|")
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