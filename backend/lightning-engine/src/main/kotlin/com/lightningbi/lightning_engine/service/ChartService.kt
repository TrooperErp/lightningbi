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
     * Tutti i grafici di un'Analisi, calcolati sulle LORO PROPRIE Righe/
     * Colonne (chart.pivotRows/chart.pivotColumns), non più su quelle
     * della pagina. pivotRowsPagina/pivotColumnsPagina/pivotValuesPagina
     * servono solo a sapere quali campi sono "ammessi" in questo momento
     * (per la potatura): un grafico che referenzia un campo non più
     * presente nel pivot pagina viene escluso dalla dashboard finché non
     * viene riallineato (vedi getChartData).
     *
     * selections resta l'unica cosa che la pagina passa e che il grafico
     * applica sempre, indipendentemente dalla propria struttura fissa.
     */
    fun getChartsData(
        areaId: UUID,
        pivotRowsPagina: List<UUID>,
        pivotColumnsPagina: List<UUID>,
        pivotValuesPagina: List<UUID>,
        selections: Map<UUID, Set<Long>>
    ): List<ChartData> {
        val campiAmmessi = (pivotRowsPagina + pivotColumnsPagina + pivotValuesPagina).toSet()

        return areaChartRepository.findByArea(areaId).mapNotNull { chart ->
            try {
                getChartData(chart, campiAmmessi, selections)
            } catch (e: Exception) {
                log.warn("Grafico '{}' ({}) non calcolabile, escluso dalla dashboard", chart.titolo, chart.id, e)
                null
            }
        }
    }

    /**
     * Dati di un singolo grafico, calcolati sulle sue proprie Righe/
     * Colonne (chart.pivotRows/chart.pivotColumns).
     *
     * campiAmmessi: insieme dei campi attualmente presenti nel pivot
     * della pagina (Righe+Colonne+Valori). Se il grafico referenzia
     * campi non più ammessi, vengono rimossi al volo (potatura) prima di
     * calcolare - per coerenza col fatto che nella UI di edit quei campi
     * sarebbero già mostrati come non selezionabili. La potatura qui non
     * salva la modifica su DB: il grafico configurato resta quello
     * salvato, solo il calcolo la applica finché l'utente non lo modifica
     * esplicitamente nel dialog (dove il PivotPanel del grafico glielo
     * mostrerà già ripulito, invitandolo a salvare la versione corretta).
     */
    fun getChartData(
        chart: AreaChart,
        campiAmmessi: Set<UUID>,
        selections: Map<UUID, Set<Long>>
    ): ChartData {
        val pivotRows = chart.pivotRows.filter { it in campiAmmessi }
        val pivotColumns = chart.pivotColumns.filter { it in campiAmmessi }

        require(pivotRows.isNotEmpty()) {
            "Il grafico '${chart.titolo}' non ha Righe valide nel pivot corrente"
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

        // Mappa dimensione -> colonna fisica, calcolata UNA VOLTA prima di
        // ogni uso di labelFor (mai dentro un loop): serve a provare la
        // libreria di transcodifica DimensionFormatters (oggi solo mesi,
        // domani altre dimensioni) prima del fallback alla label standard
        // risolta da AggregateService/SymbolLookupService.
        val dimensioni = registryRepository.findDimensioniByArea(chart.areaId)
        val colonnaFisicaByDim: Map<UUID, String> = dimensioni.associate { it.dimensioneId to it.colonnaFisica }

        // followsColumns richiede almeno una dimensione in Colonne PROPRIE
        // del grafico: se non ce ne sono (mai impostate, o rimosse dalla
        // potatura), il grafico si comporta come se followsColumns fosse
        // false, non fallisce.
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
            result.rows.sortedBy { row -> labelFor(row, pivotRows, colonnaFisicaByDim) }
        } else {
            result.rows
        }

        val labels = rows.map { row -> labelFor(row, pivotRows, colonnaFisicaByDim) }

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
     * highlightDecline: attivo solo se ci sono ESATTAMENTE 2 valori-colonna.
     * Con 1 o 3+ colonne il confronto "ultima vs penultima" è ambiguo/
     * fuorviante (es. con 3 anni il più recente appariva sempre rosso a
     * prescindere dall'andamento reale) - in quel caso si usa la palette
     * Tableau standard in sequenza (stessa di ChartsView.chartTypeIcon).
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

        val paletteStandard = listOf(
            coloreCorrente, colorePrecedente, "#59A14F", "#76B7B2", coloreCalo, "#EDC948"
        )

        if (!highlightDecline || nomiColonna.size != 2) {
            return nomiColonna.mapIndexed { i, nome ->
                ChartSeries(
                    metricaNome = nome,
                    values = serieValues[i],
                    pointColors = List(serieValues[i].size) { paletteStandard[i % paletteStandard.size] }
                )
            }
        }

        // Esattamente 2 colonne: confronto puntuale precedente/corrente,
        // rosso dove il valore è sceso, blu altrove.
        val previousValues = serieValues[0]
        val currentValues = serieValues[1]

        val serieprecedente = ChartSeries(
            metricaNome = nomiColonna[0],
            values = previousValues,
            pointColors = List(previousValues.size) { colorePrecedente }
        )
        val pointColors = currentValues.mapIndexed { idx, value ->
            val previous = previousValues.getOrNull(idx) ?: 0.0
            if (value < previous) coloreCalo else coloreCorrente
        }
        val serieCorrente = ChartSeries(metricaNome = nomiColonna[1], values = currentValues, pointColors = pointColors)

        return listOf(serieprecedente, serieCorrente)
    }

    /**
     * Etichetta composita per una riga: se le Righe del pivot hanno più di
     * una dimensione, le etichette si concatenano (es. "Azienda 1 - 2023").
     *
     * Prova prima DimensionFormatters (libreria di transcodifica per
     * colonna fisica, es. mese_numero -> "Marzo"): se non c'è un
     * formatter per quella colonna, o manca il valore, ripiega sulla
     * label già risolta da AggregateService/SymbolLookupService. Se
     * manca anche quella (symbol table disallineata), si mostra l'id
     * grezzo invece di far sparire la barra silenziosamente.
     */
    private fun labelFor(
        row: com.lightningbi.lightning_engine.model.AggregateRow,
        pivotRows: List<UUID>,
        colonnaFisicaByDim: Map<UUID, String>
    ): String =
        pivotRows.joinToString(" - ") { dimId ->
            val valueId = row.groupKeys[dimId]
            val formatted = colonnaFisicaByDim[dimId]?.let { colonna ->
                valueId?.let { DimensionFormatters.formatOrNull(colonna, it) }
            }
            formatted ?: row.labels[dimId] ?: valueId?.let { "#$it" } ?: "—"
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
     *
     * pivotRows/pivotColumns sono ora obbligatoriamente scelte in
     * creazione (dal PivotPanel dedicato nel dialog): pivotRows non può
     * essere vuoto, un grafico senza dimensione di raggruppamento propria
     * non ha assi su cui disegnarsi.
     */
    fun create(
        areaId: UUID,
        titolo: String,
        tipo: ChartType,
        metricaIds: List<UUID>,
        pivotRows: List<UUID>,
        pivotColumns: List<UUID> = emptyList(),
        orderBy: AggregateOrder = AggregateOrder.DIMENSION,
        maxItems: Int? = null,
        followsColumns: Boolean = false,
        highlightDecline: Boolean = false
    ): AreaChart {
        validate(areaId, titolo, metricaIds)
        require(pivotRows.isNotEmpty()) { "Il grafico deve avere almeno una dimensione in Righe" }
        val chart = AreaChart(
            id = UUID.randomUUID(),
            areaId = areaId,
            titolo = titolo.trim(),
            tipo = tipo,
            orderBy = orderBy,
            maxItems = maxItems,
            posizione = areaChartRepository.nextPosizione(areaId),
            followsColumns = followsColumns,
            highlightDecline = highlightDecline,
            pivotRows = pivotRows,
            pivotColumns = pivotColumns
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
        require(chart.pivotRows.isNotEmpty()) { "Il grafico deve avere almeno una dimensione in Righe" }
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