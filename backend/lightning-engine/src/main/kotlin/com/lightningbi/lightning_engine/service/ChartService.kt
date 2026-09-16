package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.AggregateOrder
import com.lightningbi.lightning_engine.model.AggregateRequest
import com.lightningbi.lightning_engine.model.AreaChart
import com.lightningbi.lightning_engine.model.AreaChartMetrica
import com.lightningbi.lightning_engine.model.ChartData
import com.lightningbi.lightning_engine.model.ChartSeries
import com.lightningbi.lightning_engine.model.ChartType
import com.lightningbi.lightning_engine.repository.AreaChartRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
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

    // ===================== lettura dati =====================

    /**
     * Tutti i grafici di un'Analisi, calcolati sulle Righe del pivot
     * correnti e sulle selezioni della sessione.
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
        selections: Map<UUID, Set<Long>>
    ): List<ChartData> {
        if (pivotRows.isEmpty()) return emptyList()

        return areaChartRepository.findByArea(areaId).mapNotNull { chart ->
            try {
                getChartData(chart, pivotRows, selections)
            } catch (e: Exception) {
                log.warn("Grafico '{}' ({}) non calcolabile, escluso dalla dashboard", chart.titolo, chart.id, e)
                null
            }
        }
    }

    /** Dati di un singolo grafico, calcolati sulle Righe del pivot correnti. */
    fun getChartData(
        chart: AreaChart,
        pivotRows: List<UUID>,
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

        // Il limite di leggibilità è il più stretto fra quello scelto
        // dall'utente e quello del tipo di grafico: una torta con 5000
        // fette è illeggibile quanto una barra con 5000 colonne, ma la
        // soglia di leggibilità è diversa per i due casi.
        val limiteLeggibilita = if (chart.tipo == ChartType.PIE) maxFettePie else maxPunti
        val limit = minOf(chart.maxItems ?: limiteLeggibilita, limiteLeggibilita)

        val ordinePerQuery = if (chart.orderBy == AggregateOrder.DIMENSION && limit < Int.MAX_VALUE) {
            // Con top-N e ordinamento per dimensione richiesto, si estrae
            // comunque per la prima metrica decrescente (altrimenti il
            // taglio prenderebbe le prime N in ordine alfabetico, non le
            // N più rilevanti), poi si riordina in memoria dopo.
            AggregateOrder.METRIC_DESC
        } else {
            chart.orderBy
        }

        val result = aggregateService.getAggregates(
            AggregateRequest(
                areaId = chart.areaId,
                selections = selections,
                groupBy = pivotRows,
                metricIds = metricheOrdinate.map { it.id },
                order = ordinePerQuery,
                orderMetricId = if (ordinePerQuery != AggregateOrder.DIMENSION) metricheOrdinate.first().id else null,
                limit = limit,
                resolveLabels = true
            )
        )

        val rows = if (ordinePerQuery != chart.orderBy) {
            // Riordino per etichetta dopo l'estrazione per metrica, come
            // sopra: le righe risultanti sono al massimo "limit", il costo
            // è trascurabile.
            result.rows.sortedBy { row -> labelFor(row, pivotRows) }
        } else {
            result.rows
        }

        val labels = rows.map { row -> labelFor(row, pivotRows) }

        // Una serie per metrica, nell'ordine dichiarato in AreaChartMetrica:
        // è questo che permette le barre affiancate (costo/ricavo) di cui
        // parlavamo, non una singola serie come nella versione precedente.
        val series = metricheOrdinate.map { metrica ->
            ChartSeries(
                metricaNome = metrica.nome,
                values = rows.map { row -> (row.values[metrica.nome] ?: java.math.BigDecimal.ZERO).toDouble() }
            )
        }

        return ChartData(
            chart = chart,
            labels = labels,
            series = series,
            truncated = result.truncated
        )
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
     * Tipi di grafico ammessi in base al contesto corrente: PIE ha senso
     * solo con esattamente una dimensione di raggruppamento e una sola
     * metrica, altrimenti sparisce dalle opzioni proposte invece di
     * restare selezionabile con un risultato senza senso.
     */
    fun tipiAmmessi(pivotRowsCount: Int, metricheCount: Int): List<ChartType> {
        val base = listOf(ChartType.BAR, ChartType.LINE, ChartType.AREA)
        return if (pivotRowsCount == 1 && metricheCount == 1) base + ChartType.PIE else base
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
        maxItems: Int? = null
    ): AreaChart {
        validate(areaId, titolo, metricaIds)
        val chart = AreaChart(
            id = UUID.randomUUID(),
            areaId = areaId,
            titolo = titolo.trim(),
            tipo = tipo,
            orderBy = orderBy,
            maxItems = maxItems,
            posizione = areaChartRepository.nextPosizione(areaId)
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