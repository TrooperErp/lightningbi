package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.AggregateResult
import com.lightningbi.lightning_engine.model.ChartData
import com.lightningbi.lightning_engine.service.PivotEngine
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.listbox.MultiSelectListBox
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.Scroller
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.progressbar.ProgressBar
import com.vaadin.flow.component.treegrid.TreeGrid
import com.vaadin.flow.data.provider.hierarchy.TreeData
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.lightningbi.lightning_engine.service.DimensionState
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

/**
 * View: possiede ogni componente Vaadin della pagina e sa solo "come
 * disegnare quello che le viene passato". Non conosce UUID di business
 * al di là di quelli necessari per costruire i componenti (dimId come
 * chiave di mappa), non fa query, non chiama service o repository.
 *
 * SOLO la griglia risultati scrolla al proprio interno (scrollableGrid):
 * molte righe non devono costringere l'utente a scrollare oltre per
 * trovare i grafici, che restano sempre visibili in un'area fissa sotto
 * la griglia, non condivisa con lo stesso Scroller.
 *
 * resultsGrid è un TreeGrid: le Righe del pivot (es. Agente > Mese)
 * arrivano già come gerarchia pronta da AggregateService.buildRowHierarchy
 * (stesso principio di Excel/Qlik: il motore di aggregazione produce la
 * struttura, la UI si limita a disegnarla con espandi/collassa). Nessuna
 * logica di raggruppamento vive qui.
 */
class AssociativeExplorerUi(
    private val onFilterSelectionChanged: (dimId: UUID, values: Set<Long>) -> Unit,
    private val onRemoveSelection: (dimId: UUID, valueId: Long) -> Unit,
    private val onPivotChanged: (rows: List<UUID>, columns: List<UUID>, values: List<UUID>) -> Unit
) {
    val pivotPanel = PivotPanel { rows, columns, values -> onPivotChanged(rows, columns, values) }

    val resultsGrid = TreeGrid<PivotEngine.PivotNode>().apply {
        className = "lbi-results-grid"
        setWidthFull()
        height = "420px"
    }

    val filtersColumn = VerticalLayout().apply {
        className = "lbi-filters-column"
        width = "300px"
        height = "100%"
    }

    val activeSelectionsBar = Div().apply { className = "lbi-active-selections" }

    val sourceStatusLabel = Span().apply { className = "lbi-source-status" }

    val chartsPanel = VerticalLayout().apply {
        className = "lbi-charts-panel"
        isPadding = false
    }

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

    private val dimensionBoxes = mutableMapOf<UUID, MultiSelectListBox<Long>>()
    private val currentItems = mutableMapOf<UUID, List<Long>>()

    /**
     * Formattazione italiana per le metriche numeriche in griglia:
     * punto per le migliaia, virgola per i decimali, sempre 2 cifre
     * decimali fisse (es. 1.234,50).
     */
    private val itNumberFormat = NumberFormat.getNumberInstance(Locale.ITALY).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    val root: HorizontalLayout

    init {
        filtersColumn.add(activeSelectionsBar)

        val statusRow = HorizontalLayout(sourceStatusLabel).apply {
            className = "lbi-action-bar"
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            isPadding = false
            setWidthFull()
        }

        // Solo la griglia è dentro lo Scroller: i grafici restano fuori,
        // in un'area fissa sotto, sempre visibili senza dover scrollare.
        val scrollableGrid = Scroller(resultsGrid).apply {
            setWidthFull()
            height = "320px"
        }

        val centerArea = VerticalLayout(
            statusRow,
            pivotPanel,
            Span("Risultati").apply { className = "lbi-section-title" },
            scrollableGrid,
            chartsPanel
        ).apply {
            className = "lbi-center"
            isPadding = true
            setWidthFull()
            setHeightFull()
            setFlexGrow(0.0, statusRow)
            setFlexGrow(0.0, pivotPanel)
            setFlexGrow(1.0, scrollableGrid)
            setFlexGrow(0.0, chartsPanel)
        }

        root = HorizontalLayout(centerArea, filtersColumn).apply {
            setSizeFull()
            isPadding = false
            isSpacing = true
            setFlexGrow(1.0, centerArea)
            setFlexGrow(0.0, filtersColumn)
        }
    }

    fun setSourceStatusText(text: String) {
        sourceStatusLabel.text = text
    }

    fun rebuildFilterCards(
        rows: List<UUID>,
        dimensionNames: Map<UUID, String>,
        columnFor: (UUID) -> String?,
        countSameName: (String) -> Int
    ) {
        filtersColumn.removeAll()
        filtersColumn.add(activeSelectionsBar)
        dimensionBoxes.clear()
        currentItems.clear()

        rows.forEach { dimId ->
            val dimName = dimensionNames[dimId] ?: return@forEach

            val box = MultiSelectListBox<Long>()
            box.width = "100%"
            box.setRenderer(neutralRenderer())
            box.addSelectionListener { event ->
                if (!event.isFromClient) return@addSelectionListener
                onFilterSelectionChanged(dimId, event.value.toSet())
            }
            dimensionBoxes[dimId] = box

            val colonna = columnFor(dimId)
            val etichetta = if (countSameName(dimName) > 1 && colonna != null) {
                "$dimName ($colonna)"
            } else {
                dimName
            }

            val title = Span(etichetta).apply { className = "lbi-filter-title" }
            val card = VerticalLayout(title, box).apply { className = "lbi-filter-card" }
            filtersColumn.add(card)
        }
    }

    fun deselectValue(dimId: UUID, valueId: Long) {
        dimensionBoxes[dimId]?.deselect(valueId)
    }

    fun deselectAll(dimId: UUID) {
        dimensionBoxes[dimId]?.deselectAll()
    }

    fun renderActiveSelections(
        selections: Map<UUID, Set<Long>>,
        dimensionNames: Map<UUID, String>,
        labels: Map<UUID, Map<Long, String>>,
        labelOrFallback: (Map<Long, String>, Long?) -> String
    ) {
        activeSelectionsBar.removeAll()
        selections.forEach { (dimId, values) ->
            if (values.isEmpty()) return@forEach
            val dimName = dimensionNames[dimId] ?: return@forEach
            val dimLabels = labels[dimId] ?: emptyMap()

            values.sorted().forEach { valueId ->
                val valueLabel = labelOrFallback(dimLabels, valueId)
                val removeIcon = Span("×").apply {
                    className = "lbi-active-chip-remove"
                    addClickListener { onRemoveSelection(dimId, valueId) }
                }
                val chip = Span().apply {
                    className = "lbi-active-chip"
                    add(Span("$dimName: $valueLabel"), removeIcon)
                }
                activeSelectionsBar.add(chip)
            }
        }
    }

    fun renderStates(
        states: Map<UUID, DimensionState>,
        labels: Map<UUID, Map<Long, String>>,
        labelOrFallback: (Map<Long, String>, Long?) -> String
    ) {
        states.forEach { (dimId, state) ->
            val box = dimensionBoxes[dimId] ?: return@forEach
            val dimLabels = labels[dimId] ?: emptyMap()

            val allValues = (state.verdi + state.grigi + state.selezionati)
                .distinct()
                .sortedBy { labelOrFallback(dimLabels, it) }

            if (currentItems[dimId] != allValues) {
                box.setItems(allValues)
                currentItems[dimId] = allValues
            }

            box.setRenderer(ComponentRenderer { valueId ->
                Span(labelOrFallback(dimLabels, valueId)).apply {
                    className = when {
                        valueId in state.selezionati -> "state-selected"
                        valueId in state.verdi -> "state-possible"
                        else -> "state-excluded"
                    }
                }
            })

            if (box.value != state.selezionati) {
                box.value = state.selezionati
            }
        }
    }

    /**
     * Disegna il TreeGrid a partire dalla gerarchia già costruita da
     * AggregateService.buildRowHierarchy (rowHierarchy): un nodo per ogni
     * livello di "rows" (es. Agente > Mese), con espandi/collassa nativo
     * di Vaadin TreeGrid. "result" resta necessario solo per derivare
     * l'insieme completo delle chiavi metrica/colonna da mostrare come
     * colonne (allValueKeys) e per il messaggio di troncamento.
     */
    fun renderResultsGrid(
        result: AggregateResult,
        rowHierarchy: List<PivotEngine.PivotNode>,
        rows: List<UUID>,
        dimensionNames: Map<UUID, String>
    ) {
        resultsGrid.removeAllColumns()

        if (rowHierarchy.isEmpty()) {
            resultsGrid.setDataProvider(TreeDataProvider(TreeData()))
            return
        }

        val treeData = TreeData<PivotEngine.PivotNode>()
        addNodesRecursively(treeData, null, rowHierarchy)
        resultsGrid.setDataProvider(TreeDataProvider(treeData))

        // Colonna gerarchica: mostra la label del nodo (Agente, poi Mese
        // nei figli) con la freccia di espansione nativa di TreeGrid.
        val rowHeader = rows.mapNotNull { dimensionNames[it] }.joinToString(" / ").ifEmpty { "Righe" }
        resultsGrid.addHierarchyColumn { node -> node.label }
            .setHeader(rowHeader)
            .setAutoWidth(true)

        // Con columnBy valorizzato le chiavi sono "Metrica|v1|v2..." invece
        // di "Metrica" semplice: l'intestazione mostra la chiave intera per
        // ora (leggibile, es. "Fatturato|2025"). Annidamento visivo vero
        // (intestazioni multi-riga stile Excel) resta un miglioramento
        // futuro - Vaadin Grid non supporta header multi-livello nativamente
        // senza componenti custom.
        //
        // Le chiavi si prendono dall'intero albero (nodi foglia E
        // intermedi), non solo da result.rows: i nodi intermedi possono
        // avere un sottoinsieme di chiavi (solo le metriche sommabili,
        // vedi PivotEngine.sumChildValues) e vanno comunque mostrate.
        val allValueKeys = collectAllValueKeys(rowHierarchy)
        allValueKeys.forEach { key ->
            resultsGrid.addColumn { node -> formatMetricValue(node.values[key]) }
                .setHeader(key.replace("|", " · "))
                .setAutoWidth(true)
        }

        if (result.truncated) {
            val messaggio = if (rows.isEmpty())
                "Risultato troncato: troppe righe da mostrare"
            else
                "Troppe combinazioni da mostrare: prova a togliere una dimensione dalle Righe"
            Notification.show(messaggio, 5000, Notification.Position.BOTTOM_END)
        }
    }

    private fun addNodesRecursively(
        treeData: TreeData<PivotEngine.PivotNode>,
        parent: PivotEngine.PivotNode?,
        nodes: List<PivotEngine.PivotNode>
    ) {
        treeData.addItems(parent, nodes)
        nodes.forEach { node ->
            if (node.children.isNotEmpty()) {
                addNodesRecursively(treeData, node, node.children)
            }
        }
    }

    private fun collectAllValueKeys(nodes: List<PivotEngine.PivotNode>): List<String> {
        val keys = LinkedHashSet<String>()
        fun visit(list: List<PivotEngine.PivotNode>) {
            list.forEach { node ->
                keys.addAll(node.values.keys)
                if (node.children.isNotEmpty()) visit(node.children)
            }
        }
        visit(nodes)
        return keys.toList()
    }

    /**
     * Formatta un valore metrica in stile italiano: punto per le migliaia,
     * virgola per i decimali, sempre 2 cifre decimali fisse. Se il valore
     * non è numerico (es. già stringa non convertibile), torna il toString
     * grezzo come fallback per non far sparire il dato.
     */
    private fun formatMetricValue(value: Any?): String = when (value) {
        null -> ""
        is Number -> itNumberFormat.format(value)
        is String -> value.toDoubleOrNull()?.let { itNumberFormat.format(it) } ?: value
        else -> value.toString()
    }

    /** Un EChartComponent per grafico, sostituisce il vecchio placeholder testuale. */
    fun renderCharts(chartsData: List<ChartData>, rows: List<UUID>) {
        chartsPanel.removeAll()

        if (rows.isEmpty()) {
            chartsPanel.add(Span("Aggiungi almeno una dimensione in Righe per vedere i grafici.").apply {
                className = "lbi-wizard-label"
            })
            return
        }
        if (chartsData.isEmpty()) return

        chartsPanel.add(Span("Grafici").apply { className = "lbi-section-title" })

        val grid = HorizontalLayout().apply {
            className = "lbi-charts-grid"
            isPadding = false
        }
        chartsData.forEach { chartData ->
            val chartComponent = EChartComponent()
            grid.add(chartComponent)
            chartComponent.render(chartData)
        }
        chartsPanel.add(grid)
    }

    fun clearAll() {
        filtersColumn.removeAll()
        filtersColumn.add(activeSelectionsBar)
        activeSelectionsBar.removeAll()
        resultsGrid.setDataProvider(TreeDataProvider(TreeData()))
        resultsGrid.removeAllColumns()
        chartsPanel.removeAll()
        dimensionBoxes.clear()
        currentItems.clear()
    }

    private fun neutralRenderer() = ComponentRenderer<Span, Long> { valueId ->
        Span(valueId.toString()).apply { className = "state-possible" }
    }
}