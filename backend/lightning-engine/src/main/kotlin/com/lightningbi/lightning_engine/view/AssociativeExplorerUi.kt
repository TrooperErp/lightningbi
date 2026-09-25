package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.AggregateResult
import com.lightningbi.lightning_engine.model.ChartResult
import com.lightningbi.lightning_engine.service.DimensionState
import com.lightningbi.lightning_engine.service.PivotEngine
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.progressbar.ProgressBar
import java.util.UUID

/**
 * Coordinatore: possiede il layout generale della pagina Analisi e
 * delega ogni area a una classe Ui dedicata:
 * - FilterCardsUi: pannello associativo (card filtro)
 * - ResultsGridUi: griglia risultati (TreeGrid)
 * - ChartsPanelUi: grafici
 *
 * Non fa query, non chiama service o repository - stessa responsabilità
 * di sempre, solo divisa in più file per manutenibilità (era diventata
 * troppo grande in un unico file).
 */
class AssociativeExplorerUi(
    private val onFilterSelectionChanged: (dimId: UUID, values: Set<Long>) -> Unit,
    private val onRemoveSelection: (dimId: UUID, valueId: Long) -> Unit,
    private val onPivotChanged: (rows: List<UUID>, columns: List<UUID>, values: List<UUID>) -> Unit
) {
    val pivotPanel = PivotPanel { rows, columns, values -> onPivotChanged(rows, columns, values) }

    private val filterCardsUi = FilterCardsUi(onFilterSelectionChanged)
    private val resultsGridUi = ResultsGridUi()
    private val chartsPanelUi = ChartsPanelUi()

    val filtersColumn: VerticalLayout get() = filterCardsUi.root
    val resultsGrid get() = resultsGridUi.resultsGrid
    val chartsPanel: VerticalLayout get() = chartsPanelUi.root

    val loadingDialog = com.vaadin.flow.component.dialog.Dialog().apply {
        isCloseOnEsc = false
        isCloseOnOutsideClick = false
        headerTitle = "Caricamento"
        add(
            VerticalLayout(
                ProgressBar().apply { isIndeterminate = true; setWidthFull() },
                Span("Aggiornamento dati in corso...").apply { className = "lbi-wizard-label" }
            ).apply { isPadding = false }
        )
    }

    val root: HorizontalLayout

    init {
        val centerArea = VerticalLayout(
            pivotPanel,
            Span("Risultati").apply { className = "lbi-section-title" },
            resultsGridUi.root,
            chartsPanelUi.root
        ).apply {
            className = "lbi-center"
            isPadding = true
            setWidthFull()
            setHeightFull()
            setFlexGrow(0.0, pivotPanel)
            setFlexGrow(1.0, resultsGridUi.root)
            setFlexGrow(0.0, chartsPanelUi.root)
        }

        root = HorizontalLayout(centerArea, filterCardsUi.root).apply {
            width = "100%"
            isPadding = false
            isSpacing = true
            setFlexGrow(1.0, centerArea)
            setFlexGrow(0.0, filterCardsUi.root)
        }
    }

    fun rebuildFilterCards(
        allDimIds: List<UUID>,
        dimensionNames: Map<UUID, String>,
        columnFor: (UUID) -> String?,
        countSameName: (String) -> Int,
        hiddenDimIds: Set<UUID> = emptySet()
    ) = filterCardsUi.rebuildFilterCards(allDimIds, dimensionNames, columnFor, countSameName, hiddenDimIds)

    fun deselectValue(dimId: UUID, valueId: Long) = filterCardsUi.deselectValue(dimId, valueId)

    fun deselectAll(dimId: UUID) = filterCardsUi.deselectAll(dimId)

    fun renderStates(
        states: Map<UUID, DimensionState>,
        labels: Map<UUID, Map<Long, String>>,
        labelOrFallback: (Map<Long, String>, Long?) -> String,
        colonnaFisicaFor: (UUID) -> String? = { null }
    ) = filterCardsUi.renderStates(states, labels, labelOrFallback, colonnaFisicaFor)

    fun renderResultsGrid(
        result: AggregateResult,
        rowHierarchy: List<PivotEngine.PivotNode>,
        rows: List<UUID>,
        dimensionNames: Map<UUID, String>
    ) = resultsGridUi.render(result, rowHierarchy, rows, dimensionNames)

    fun renderCharts(chartsData: List<ChartResult>, rows: List<UUID>) = chartsPanelUi.render(chartsData, rows)

    fun clearAll() {
        filterCardsUi.clearAll()
        resultsGridUi.clearAll()
        chartsPanelUi.clearAll()
    }
}