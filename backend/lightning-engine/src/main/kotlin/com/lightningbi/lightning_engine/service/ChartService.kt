package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.AggregateOrder
import com.lightningbi.lightning_engine.model.AggregateRequest
import com.lightningbi.lightning_engine.model.AreaChart
import com.lightningbi.lightning_engine.model.ChartData
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

    /**
     * Oltre questa soglia un grafico smette di essere leggibile: 200 barre
     * su uno schermo sono pixel, non informazione. È un limite di
     * presentazione, non di sistema: il limite di AggregateService resta
     * molto più alto per la grid.
     */
    private val maxPunti = 100

    /** Un PIE con troppe fette è illeggibile: sotto questa soglia resta usabile. */
    private val maxFettePie = 12

    // ===================== lettura dati =====================

    /** Tutti i grafici di un'Analisi, con i dati calcolati sulle selezioni correnti. */
    fun getChartsData(areaId: UUID, selections: Map<UUID, Set<Long>>): List<ChartData> =
        areaChartRepository.findByArea(areaId).mapNotNull { chart ->
            try {
                getChartData(chart, selections)
            } catch (e: Exception) {
                // Un grafico rotto (dimensione rimossa, metrica cancellata)
                // non deve far fallire l'intera dashboard: si logga e si
                // mostrano gli altri.
                log.warn("Grafico '{}' ({}) non calcolabile, escluso dalla dashboard", chart.titolo, chart.id, e)
                null
            }
        }

    /** Dati di un singolo grafico, calcolati sulle selezioni correnti della sessione. */
    fun getChartData(chart: AreaChart, selections: Map<UUID, Set<Long>>): ChartData {
        val metrica = registryRepository.findMetricheByArea(chart.areaId)
            .find { it.id == chart.metricaId }
            ?: error("Metrica ${chart.metricaId} non più presente nell'area ${chart.areaId}")

        val dimensionePresente = registryRepository.findDimensioniByArea(chart.areaId)
            .any { it.dimensioneId == chart.groupByDimId }
        require(dimensionePresente) {
            "Dimensione ${chart.groupByDimId} non più collegata all'area ${chart.areaId}"
        }

        // Il limite effettivo è il più stretto fra quello scelto dall'utente
        // e quello di leggibilità: un grafico che chiede 5000 punti va
        // comunque tagliato, altrimenti il browser si pianta.
        val limiteLeggibilita = if (chart.tipo == ChartType.PIE) maxFettePie else maxPunti
        val limit = minOf(chart.maxItems ?: limiteLeggibilita, limiteLeggibilita)

        // Il top-N deve essere calcolato dal database sulla metrica, non
        // tagliando in memoria: con ordinamento per dimensione si otterrebbero
        // le prime N in ordine alfabetico, non le N più grandi.
        val serve = chart.maxItems != null && chart.maxItems < Int.MAX_VALUE
        val ordinePerQuery = if (serve && chart.orderBy == AggregateOrder.DIMENSION) {
            AggregateOrder.METRIC_DESC
        } else {
            chart.orderBy
        }

        val result = aggregateService.getAggregates(
            AggregateRequest(
                areaId = chart.areaId,
                selections = selections,
                groupBy = listOf(chart.groupByDimId),
                metricIds = listOf(chart.metricaId),
                order = ordinePerQuery,
                orderMetricId = if (ordinePerQuery != AggregateOrder.DIMENSION) chart.metricaId else null,
                limit = limit,
                resolveLabels = true
            )
        )

        // Se il top-N è stato estratto per metrica ma l'utente voleva le
        // etichette in ordine alfabetico/cronologico, si riordina ora: sono
        // al massimo maxPunti righe, il costo è trascurabile.
        val rows = if (serve && chart.orderBy == AggregateOrder.DIMENSION) {
            result.rows.sortedBy { it.labels[chart.groupByDimId] ?: "" }
        } else {
            result.rows
        }

        val labels = rows.map { row ->
            // Se la symbol table è disallineata rispetto ai fatti l'etichetta
            // manca: si mostra l'id grezzo invece di far sparire la barra,
            // così il problema è visibile invece che silenzioso.
            row.labels[chart.groupByDimId]
                ?: row.groupKeys[chart.groupByDimId]?.let { "#$it" }
                ?: "—"
        }

        val values = rows.map { row ->
            (row.values[metrica.nome] ?: java.math.BigDecimal.ZERO).toDouble()
        }

        return ChartData(
            chart = chart,
            labels = labels,
            values = values,
            metricaNome = metrica.nome,
            truncated = result.truncated
        )
    }

    // ===================== gestione configurazione =====================

    fun findByArea(areaId: UUID): List<AreaChart> = areaChartRepository.findByArea(areaId)

    fun findById(id: UUID): AreaChart? = areaChartRepository.findById(id)

    /**
     * Crea un grafico validando che dimensione e metrica appartengano
     * davvero all'area: senza FK nel registry, il controllo sta qui.
     */
    fun create(
        areaId: UUID,
        titolo: String,
        tipo: ChartType,
        groupByDimId: UUID,
        metricaId: UUID,
        orderBy: AggregateOrder = AggregateOrder.DIMENSION,
        maxItems: Int? = null
    ): AreaChart {
        validate(areaId, titolo, groupByDimId, metricaId, maxItems)
        val chart = AreaChart(
            id = UUID.randomUUID(),
            areaId = areaId,
            titolo = titolo.trim(),
            tipo = tipo,
            groupByDimId = groupByDimId,
            metricaId = metricaId,
            orderBy = orderBy,
            maxItems = maxItems,
            posizione = areaChartRepository.nextPosizione(areaId)
        )
        return areaChartRepository.save(chart)
    }

    fun update(chart: AreaChart): AreaChart {
        validate(chart.areaId, chart.titolo, chart.groupByDimId, chart.metricaId, chart.maxItems)
        return areaChartRepository.update(chart)
    }

    fun delete(id: UUID): Boolean = areaChartRepository.delete(id)

    /** Da chiamare quando si elimina un'Analisi: senza FK nessuno lo fa al posto nostro. */
    fun deleteByArea(areaId: UUID): Int = areaChartRepository.deleteByArea(areaId)

    /**
     * Riordina i grafici nella dashboard.
     * Riceve gli id nell'ordine desiderato e riassegna le posizioni.
     */
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

    private fun validate(areaId: UUID, titolo: String, groupByDimId: UUID, metricaId: UUID, maxItems: Int?) {
        require(titolo.isNotBlank()) { "Il titolo del grafico è obbligatorio" }
        require(registryRepository.findAreaById(areaId) != null) { "Analisi $areaId inesistente" }
        require(registryRepository.findDimensioniByArea(areaId).any { it.dimensioneId == groupByDimId }) {
            "La dimensione scelta non appartiene a questa Analisi"
        }
        require(registryRepository.findMetricheByArea(areaId).any { it.id == metricaId }) {
            "La metrica scelta non appartiene a questa Analisi"
        }
        maxItems?.let { require(it > 0) { "Il numero massimo di elementi deve essere positivo" } }
    }
}