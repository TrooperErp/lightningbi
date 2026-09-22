package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.AggregateOrder
import com.lightningbi.lightning_engine.model.AggregateRequest
import com.lightningbi.lightning_engine.model.AreaChart
import com.lightningbi.lightning_engine.model.AreaChartMetrica
import com.lightningbi.lightning_engine.model.AreaMetrica
import com.lightningbi.lightning_engine.model.ChartData
import com.lightningbi.lightning_engine.model.ChartSeries
import com.lightningbi.lightning_engine.model.ChartType
import com.lightningbi.lightning_engine.repository.AreaChartRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

@Service
class ChartService(
    private val areaChartRepository: AreaChartRepository,
    private val registryRepository: RegistryRepository,
    private val aggregateService: AggregateService
) {
    private val log = LoggerFactory.getLogger(ChartService::class.java)

    private val maxPunti = 100
    private val maxFettePie = 12

    /** Palette Tableau: blu (serie corrente), arancio (serie di confronto), rosso (calo). */
    private val colorePrecedente = "#F28E2B"
    private val coloreCorrente = "#4E79A7"
    private val coloreCalo = "#E15759"

    // ===================== lettura dati =====================

    /**
     * Tutti i grafici di un'Analisi, calcolati sulle Righe (e, se il
     * grafico lo richiede, sulle Colonne) del pivot correnti e sulle
     * selezioni della sessione.
     *
     * pivotRows non è più letto dal grafico stesso: il grafico non ha una
     * propria dimensione di raggruppamento, la eredita sempre da qui. Se
     * pivotRows è vuoto (nessuna dimensione in Righe, il caso "totale
     * unico"), non c'è nessun asse su cui disegnare un grafico a barre o
     * linee: la lista torna vuota, la UI mostra il messaggio invece di
     * provare a disegnare qualcosa privo di senso.
     */
    fun getChartsData(
        areaId: UUID,
        pivotRows: List<UUID>,
        pivotColumns: List<UUID>,
        selections: Map<UUID, Set<Long>>
    ): List<ChartData> {
        if (pivotRows.isEmpty()) return emptyList()

        return areaChartRepository.findByArea(areaId).mapNotNull { chart ->
            try {
                getChartData(chart, pivotRows, pivotColumns, selections)
            } catch (e: Exception) {
                log.warn("Grafico '{}' ({}) non calcolabile, escluso dalla dashboard", chart.titolo, chart.id, e)
                null
            }
        }
    }

    /** Dati di un singolo grafico, calcolati sulle Righe (e, se richiesto, Colonne) del pivot correnti. */
    fun getChartData(
        chart: AreaChart,
        pivotRows: List<UUID>,
        pivotColumns: List<UUID>,
        selections: Map<UUID, Set<Long>>
    ): ChartData {
        require(pivotRows.isNotEmpty()) {
            "Il grafico '${chart.titolo}' richiede almeno una dimensione in Righe"
        }

        val chartMetriche = areaChartRepository.findMetricheByChart(chart.id)
        require(chartMetriche.isNotEmpty()) {
            "Il grafico '${chart.titolo}' non ha metriche configurate"
        }

        val tutteMetriche = registryRepository.findMetricheByArea(chart.areaId)
        val metricheOrdinate = chartMetriche.mapNotNull { cm ->
            tutteMetriche.find { it.id == cm.metricaId }
        }
        require(metricheOrdinate.size == chartMetriche.size) {
            "Il grafico '${chart.titolo}' referenzia metriche non più presenti nell'area"
        }

        // followsColumns richiede almeno una dimensione in Colonne: se il
        // pivot corrente non ne ha, il grafico si comporta come se
        // followsColumns fosse false (nessuna colonna da seguire), non
        // fallisce - evita che aprire l'area senza Colonne impostate
        // rompa un grafico configurato per seguirle.
        val columnsEffettive = if (chart.followsColumns) pivotColumns else emptyList()

        val limiteLeggibilita = if (chart.tipo == ChartType.PIE) maxFettePie else maxPunti
        val limit = minOf(chart.maxItems ?: limiteLeggibilita, limiteLeggibilita)

        val ordinePerQuery = if (chart.orderBy == AggregateOrder.DIMENSION && limit < Int.MAX_VALUE) {
            AggregateOrder.METRIC_DESC
        } else {
            chart.orderBy
        }

        val result = aggregateService.getAggregates(
            AggregateRequest(
                areaId = chart.areaId,
                selections = selections,
                groupBy = pivotRows,
                columnBy = columnsEffettive,
                metricIds = metricheOrdinate.map { it.id },
                order = if (columnsEffettive.isEmpty()) ordinePerQuery else AggregateOrder.DIMENSION,
                orderMetricId = if (columnsEffettive.isEmpty() && ordinePerQuery != AggregateOrder.DIMENSION) metricheOrdinate.first().id else null,
                limit = limit,
                resolveLabels = true
            )
        )

        val rows = if (columnsEffettive.isEmpty() && ordinePerQuery != chart.orderBy) {
            result.rows.sortedBy { row -> labelFor(row, pivotRows) }
        } else {
            result.rows
        }

        val labels = rows.map { row -> labelFor(row, pivotRows) }

        val series = if (columnsEffettive.isEmpty()) {
            buildSeriesPerMetrica(rows, metricheOrdinate)
        } else {
            buildSeriesPerColonna(rows, metricheOrdinate, chart.highlightDecline)
        }

        return ChartData(
            chart = chart,
            labels = labels,
            series = series,
            truncated = result.truncated
        )
    }

    /**
     * Comportamento storico: una serie per metrica, colore di default
     * della palette (nessun pointColors).
     */
    private fun buildSeriesPerMetrica(
        rows: List<com.lightningbi.lightning_engine.model.AggregateRow>,
        metricheOrdinate: List<AreaMetrica>
    ): List<ChartSeries> =
        metricheOrdinate.map { metrica ->
            ChartSeries(
                metricaNome = metrica.nome,
                values = rows.map { row -> (row.values[metrica.nome] ?: BigDecimal.ZERO).toDouble() }
            )
        }

    /**
     * Una serie per ogni valore-colonna distinto (es. "2025", "2026"),
     * per la prima metrica del grafico - un grafico che segue le Colonne
     * ha senso con una sola metrica alla volta, altrimenti il numero di
     * serie esploderebbe (metriche × valori-colonna) diventando
     * illeggibile. Le chiavi in AggregateRow.values sono già nella forma
     * "nomeMetrica|v1|v2..." prodotta da AggregateService.pivotByColumns:
     * qui si estrae solo il suffisso dopo il nome metrica come nome
     * serie.
     *
     * highlightDecline colora di rosso ogni punto dell'ULTIMA serie
     * (ordinata per label) il cui valore è inferiore al punto
     * corrispondente della serie precedente. Le altre serie restano nei
     * due colori fissi (arancio = confronto, blu = corrente) invece dei
     * colori di palette di default, per rendere leggibile a colpo
     * d'occhio quale sia il periodo "nuovo" rispetto al "vecchio".
     */
    private fun buildSeriesPerColonna(
        rows: List<com.lightningbi.lightning_engine.model.AggregateRow>,
        metricheOrdinate: List<AreaMetrica>,
        highlightDecline: Boolean
    ): List<ChartSeries> {
        val metrica = metricheOrdinate.first()
        val prefix = "${metrica.nome}|"

        val nomiColonna = rows
            .flatMap { it.values.keys }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .distinct()
            .sorted()

        if (nomiColonna.isEmpty()) return emptyList()

        val serieValues = nomiColonna.map { nomeColonna ->
            val key = "$prefix$nomeColonna"
            rows.map { row -> (row.values[key] ?: BigDecimal.ZERO).toDouble() }
        }

        if (!highlightDecline || nomiColonna.size < 2) {
            return nomiColonna.mapIndexed { i, nome ->
                ChartSeries(metricaNome = nome, values = serieValues[i])
            }
        }

        // Ultima serie (per ordine di label) confrontata con la
        // penultima: rosso dove il valore è sceso, blu altrove. Le serie
        // precedenti (se più di due, es. 2024/2025/2026) restano tutte
        // arancio: solo il confronto sull'ultimo gradino è quello
        // rilevante da evidenziare.
        return nomiColonna.mapIndexed { i, nome ->
            if (i < nomiColonna.size - 1) {
                ChartSeries(
                    metricaNome = nome,
                    values = serieValues[i],
                    pointColors = List(serieValues[i].size) { colorePrecedente }
                )
            } else {
                val previousValues = serieValues[i - 1]
                val currentValues = serieValues[i]
                val pointColors = currentValues.mapIndexed { idx, value ->
                    val previous = previousValues.getOrNull(idx) ?: 0.0
                    if (value < previous) coloreCalo else coloreCorrente
                }
                ChartSeries(metricaNome = nome, values = currentValues, pointColors = pointColors)
            }
        }
    }

    /**
     * Etichetta composita per una riga: se le Righe del pivot hanno più di
     * una dimensione, le etichette si concatenano (es. "Azienda 1 - 2023").
     * Se manca l'etichetta per un value_id (symbol table disallineata), si
     * mostra l'id grezzo invece di far sparire la barra silenziosamente.
     */
    private fun labelFor(row: com.lightningbi.lightning_engine.model.AggregateRow, pivotRows: List<UUID>): String =
        pivotRows.joinToString(" - ") { dimId ->
            row.labels[dimId] ?: row.groupKeys[dimId]?.let { "#$it" } ?: "—"
        }

    // ===================== gestione configurazione =====================

    fun findByArea(areaId: UUID): List<AreaChart> = areaChartRepository.findByArea(areaId)

    fun findById(id: UUID): AreaChart? = areaChartRepository.findById(id)

    fun getMetricheDelGrafico(chartId: UUID): List<AreaChartMetrica> =
        areaChartRepository.findMetricheByChart(chartId)


    /**
     * Tipi di grafico ammessi in base al contesto corrente.
     *
     * - PIE/DONUT: solo con esattamente una dimensione e una metrica,
     *   altrimenti il risultato non ha interpretazione sensata.
     * - SCATTER: richiede almeno due metriche, perché usa due metriche
     *   come assi X/Y invece della dimensione di raggruppamento.
     * - RADAR: richiede almeno tre metriche sulla stessa categoria,
     *   con una o due non c'è una forma poligonale da disegnare.
     * - MAP: sempre proposta quando c'è almeno una dimensione, perché il
     *   sistema non può verificare in anticipo se i suoi valori sono nomi
     *   geografici riconoscibili - se non lo sono, fallisce a runtime con
     *   un messaggio esplicito invece di essere preventivamente vietata.
     * - BAR/BAR_HORIZONTAL/LINE/AREA: nessun vincolo oltre al minimo
     *   comune (almeno una dimensione, almeno una metrica).
     */
    fun tipiAmmessi(pivotRowsCount: Int, metricheCount: Int): List<ChartType> {
        if (pivotRowsCount == 0 || metricheCount == 0) return emptyList()

        val tipi = mutableListOf(ChartType.BAR, ChartType.BAR_HORIZONTAL, ChartType.LINE, ChartType.AREA)

        if (pivotRowsCount == 1 && metricheCount == 1) {
            tipi += ChartType.PIE
            tipi += ChartType.DONUT
        }
        if (metricheCount >= 2) {
            tipi += ChartType.SCATTER
        }
        if (metricheCount >= 3) {
            tipi += ChartType.RADAR
        }
        tipi += ChartType.MAP

        return tipi
    }

    /**
     * Crea un grafico validando che le metriche appartengano davvero
     * all'area: senza FK nel registry, il controllo sta qui.
     */
    fun create(
        areaId: UUID,
        titolo: String,
        tipo: ChartType,
        metricaIds: List<UUID>,
        orderBy: AggregateOrder = AggregateOrder.DIMENSION,
        maxItems: Int? = null,
        followsColumns: Boolean = false,
        highlightDecline: Boolean = false
    ): AreaChart {
        validate(areaId, titolo, metricaIds)
        val chart = AreaChart(
            id = UUID.randomUUID(),
            areaId = areaId,
            titolo = titolo.trim(),
            tipo = tipo,
            orderBy = orderBy,
            maxItems = maxItems,
            posizione = areaChartRepository.nextPosizione(areaId),
            followsColumns = followsColumns,
            highlightDecline = highlightDecline
        )
        areaChartRepository.save(chart)
        areaChartRepository.replaceMetriche(
            chart.id,
            metricaIds.mapIndexed { i, mid -> AreaChartMetrica(chart.id, mid, i) }
        )
        return chart
    }

    fun update(chart: AreaChart, metricaIds: List<UUID>): AreaChart {
        validate(chart.areaId, chart.titolo, metricaIds)
        areaChartRepository.update(chart)
        areaChartRepository.replaceMetriche(
            chart.id,
            metricaIds.mapIndexed { i, mid -> AreaChartMetrica(chart.id, mid, i) }
        )
        return chart
    }

    fun delete(id: UUID): Boolean = areaChartRepository.delete(id)

    fun deleteByArea(areaId: UUID): Int = areaChartRepository.deleteByArea(areaId)

    fun reorder(areaId: UUID, idsInOrder: List<UUID>) {
        val existing = areaChartRepository.findByArea(areaId).associateBy { it.id }
        idsInOrder.forEachIndexed { index, id ->
            existing[id]?.let { chart ->
                if (chart.posizione != index) {
                    areaChartRepository.update(chart.copy(posizione = index))
                }
            }
        }
    }

    private fun validate(areaId: UUID, titolo: String, metricaIds: List<UUID>) {
        require(titolo.isNotBlank()) { "Il titolo del grafico è obbligatorio" }
        require(registryRepository.findAreaById(areaId) != null) { "Analisi $areaId inesistente" }
        require(metricaIds.isNotEmpty()) { "Il grafico deve avere almeno una metrica" }
        val metricheArea = registryRepository.findMetricheByArea(areaId).map { it.id }.toSet()
        require(metricaIds.all { it in metricheArea }) { "Una o più metriche non appartengono a questa Analisi" }
    }
}