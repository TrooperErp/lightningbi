package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.ChartResult
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import java.util.UUID

/** Pannello grafici dell'Analisi: un EChartComponent per grafico pronto, un placeholder per ogni grafico non coerente col pivot corrente. */
class ChartsPanelUi {

    val root = VerticalLayout().apply {
        className = "lbi-charts-panel"
        isPadding = false
    }

    /** @param alClic riceve gli id da selezionare quando si clicca un grafico */
    fun render(chartsData: List<ChartResult>, rows: List<UUID>, alClic: (Map<UUID, Long>) -> Unit = {}) {
        root.removeAll()

        if (rows.isEmpty()) {
            root.add(Span("Aggiungi almeno una dimensione in Righe per vedere i grafici.").apply {
                className = "lbi-wizard-label"
            })
            return
        }
        if (chartsData.isEmpty()) return

        root.add(Span("Grafici").apply { className = "lbi-qv-panel-title" })

        val grid = HorizontalLayout().apply {
            className = "lbi-charts-grid"
            isPadding = false
        }
        chartsData.forEach { result ->
            when (result) {
                is ChartResult.Ready -> {
                    val chartComponent = EChartComponent(alClic)
                    grid.add(chartComponent)
                    chartComponent.render(result.data)
                }
                is ChartResult.Incoherent -> {
                    grid.add(buildIncoherentPlaceholder(result.chart.titolo, result.reason))
                }
            }
        }
        root.add(grid)
    }

    fun clearAll() {
        root.removeAll()
    }

    private fun buildIncoherentPlaceholder(titolo: String, reason: String): VerticalLayout =
        VerticalLayout(
            Span(titolo).apply { className = "lbi-chart-type-label" },
            Span(reason).apply { className = "lbi-wizard-label" },
            Span("Vai in \"Gestisci grafici\" per correggerlo").apply { className = "lbi-wizard-label" }
        ).apply {
            className = "lbi-chart-placeholder"
            isPadding = true
            width = "300px"
        }
}