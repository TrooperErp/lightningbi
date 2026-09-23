package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.AggregateResult
import com.lightningbi.lightning_engine.model.ChartData
import com.lightningbi.lightning_engine.service.PivotEngine
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.Scroller
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.progressbar.ProgressBar
import com.vaadin.flow.component.treegrid.TreeGrid
import com.vaadin.flow.data.provider.hierarchy.TreeData
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider
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
 *
 * Le card verde/grigio/escluso sono Span cliccabili dentro un Div puro a
 * griglia CSS (.lbi-filter-grid), non più MultiSelectListBox (il suo
 * layout interno/shadow DOM non era sovrascrivibile per ottenere due
 * colonne per riga) né Checkbox (introdotti per errore in un passaggio
 * intermedio, mai richiesti: l'aspetto voluto è il pulsante colorato
 * pieno, non un quadratino con etichetta a fianco).
 *
 * NON possiede più un PivotPanel: Righe/Colonne/Valori si costruiscono
 * ora solo in ConfigureAnalysisView. Questa vista mostra solo le card
 * filtro della struttura corrente (letta dalla PivotView attiva),
 * griglia e grafici - nessun drag&drop qui.
 *
 * Lo stato sorgente ("Sorgente verificata: X") non vive più qui: è
 * mostrato nella topbar (LbiAppShell.updateSourceStatus).
 */
class AssociativeExplorerUi(
    private val onFilterSelectionChanged: (dimId: UUID, values: Set<Long>) -> Unit,
    private val onRemoveSelection: (dimId: UUID, valueId: Long) -> Unit
) {
    val resultsGrid = TreeGrid<PivotEngine.PivotNode>().apply {
        className = "lbi-results-grid"
        setWidthFull()
        height = "280px"
    }

    val filtersColumn = VerticalLayout().apply {
        className = "lbi-filters-column"
        width = "420px"
        height = "100%"
    }

    val activeSelectionsBar = Div().apply { className = "lbi-active-selections" }

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

    /** Per ogni dimensione, il Div-griglia dove vengono aggiunti gli Span cliccabili. */
    private val filterGrids = mutableMapOf<UUID, Div>()

    /** Per ogni dimensione, mappa valueId -> Span, per leggere/scrivere selezioni e stati via className. */
    private val dimensionSpans = mutableMapOf<UUID, MutableMap<Long, Span>>()

    /** Ultimo insieme di valori disegnati per dimensione, per evitare di ricostruire la griglia se non è cambiato. */
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

        // Solo la griglia è dentro lo Scroller: i grafici restano fuori,
        // in un'area fissa sotto, sempre visibili senza dover scrollare.
        val scrollableGrid = Scroller(resultsGrid).apply {
            setWidthFull()
            height = "280px"
            style.set("flex-shrink", "0")
        }

        val centerArea = VerticalLayout(
            Span("Risultati").apply { className = "lbi-section-title" },
            scrollableGrid,
            chartsPanel
        ).apply {
            className = "lbi-center"
            isPadding = true
            setWidthFull()
            setHeightFull()
            setFlexGrow(1.0, scrollableGrid)
            setFlexGrow(0.0, chartsPanel)
        }

        root = HorizontalLayout(centerArea, filtersColumn).apply {
            width = "100%"
            isPadding = false
            isSpacing = true
            setFlexGrow(1.0, centerArea)
            setFlexGrow(0.0, filtersColumn)
        }
    }

    /**
     * Ricostruisce le card filtro per le dimensioni in Righe e in
     * Colonne, in due gruppi affiancati. Ogni card contiene un Div a
     * griglia (2 colonne) di Span cliccabili, popolato in seguito da
     * renderStates quando arrivano gli stati verde/grigio/escluso.
     */
    fun rebuildFilterCards(
        rowDims: List<UUID>,
        columnDims: List<UUID>,
        dimensionNames: Map<UUID, String>,
        columnFor: (UUID) -> String?,
        countSameName: (String) -> Int
    ) {
        filtersColumn.removeAll()
        filtersColumn.add(activeSelectionsBar)
        filterGrids.clear()
        dimensionSpans.clear()
        currentItems.clear()

        val allDims = rowDims + columnDims

        val rowsGroup = VerticalLayout().apply { isPadding = false; className = "lbi-filter-group" }
        val columnsGroup = VerticalLayout().apply { isPadding = false; className = "lbi-filter-group" }

        fun buildCard(dimId: UUID): VerticalLayout? {
            val dimName = dimensionNames[dimId] ?: return null

            val grid = Div().apply { className = "lbi-filter-grid" }
            filterGrids[dimId] = grid
            dimensionSpans[dimId] = mutableMapOf()

            val colonna = columnFor(dimId)
            val etichetta = if (countSameName(dimName) > 1 && colonna != null) "$dimName ($colonna)" else dimName

            val title = Span(etichetta).apply { className = "lbi-filter-title" }
            return VerticalLayout(title, grid).apply { className = "lbi-filter-card" }
        }

        rowDims.forEach { dimId -> buildCard(dimId)?.let { rowsGroup.add(it) } }
        columnDims.forEach { dimId -> buildCard(dimId)?.let { columnsGroup.add(it) } }

        val groupsRow = HorizontalLayout(rowsGroup, columnsGroup).apply {
            isPadding = false
            setWidthFull()
            setFlexGrow(1.0, rowsGroup)
            setFlexGrow(1.0, columnsGroup)
        }
        filtersColumn.add(groupsRow)
    }

    fun deselectValue(dimId: UUID, valueId: Long) {
        dimensionSpans[dimId]?.get(valueId)?.className = "state-possible"
    }

    fun deselectAll(dimId: UUID) {
        dimensionSpans[dimId]?.values?.forEach { it.className = "state-possible" }
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

    /**
     * Popola/aggiorna le griglie di Span con gli stati correnti.
     * Ricostruisce gli Span solo se l'insieme di valori è cambiato (nuova
     * sincronizzazione, nuovo dominio); altrimenti si limita ad
     * aggiornare la className (colore) degli Span esistenti, per non
     * perdere focus/scroll dell'utente ad ogni click.
     *
     * Il click su uno Span calcola la nuova selezione leggendo la
     * className CORRENTE (prima del cambio) per sapere se quel valore
     * era già selezionato, e la inverte: non serve stato separato, la
     * className stessa è la fonte di verità per "selezionato o no" nel
     * momento del click, aggiornata poi da questa stessa funzione al
     * giro di refresh successivo.
     */
    fun renderStates(
        states: Map<UUID, DimensionState>,
        labels: Map<UUID, Map<Long, String>>,
        labelOrFallback: (Map<Long, String>, Long?) -> String,
        colonnaFisicaFor: (UUID) -> String? = { null }
    )  {
        states.forEach { (dimId, state) ->
            val grid = filterGrids[dimId] ?: return@forEach
            val spanMap = dimensionSpans[dimId] ?: return@forEach
            val dimLabels = labels[dimId] ?: emptyMap()

            val colonna = colonnaFisicaFor(dimId)
            val naturalOrder = colonna != null && com.lightningbi.lightning_engine.service.DimensionSortOrders.usesNaturalOrder(colonna)
            val allValues = (state.verdi + state.grigi + state.selezionati)
                .distinct()
                .let { values -> if (naturalOrder) values.sorted() else values.sortedBy { labelOrFallback(dimLabels, it) } }

            if (currentItems[dimId] != allValues) {
                grid.removeAll()
                spanMap.clear()
                allValues.forEach { valueId ->
                    val fullLabel = colonna?.let { com.lightningbi.lightning_engine.service.DimensionFormatters.formatOrNull(it, valueId) } ?: labelOrFallback(dimLabels, valueId)
                    val shortLabel = if (fullLabel.length > 6) fullLabel.take(6) + "…" else fullLabel
                    val span = Span(shortLabel).apply {
                        element.setAttribute("title", fullLabel)
                        addClickListener {
                            val isCurrentlySelected = className == "state-selected"
                            val selectedNow = spanMap.filterValues { it.className == "state-selected" }.keys.toMutableSet()
                            if (isCurrentlySelected) selectedNow.remove(valueId) else selectedNow.add(valueId)
                            onFilterSelectionChanged(dimId, selectedNow)
                        }
                    }
                    spanMap[valueId] = span
                    grid.add(span)
                }
                currentItems[dimId] = allValues
            }

            spanMap.forEach { (valueId, span) ->
                val stateClass = when {
                    valueId in state.selezionati -> "state-selected"
                    valueId in state.verdi -> "state-possible"
                    else -> "state-excluded"
                }
                span.className = stateClass
                val (bg, color, border) = when (stateClass) {
                    "state-selected" -> Triple("#22c55e", "#ffffff", "none")
                    "state-possible" -> Triple("transparent", "#201f1e", "1px solid #e1dfdd")
                    else -> Triple("#e1dfdd", "#a19f9d", "none")
                }
                span.style.set("background", bg)
                span.style.set("color", color)
                span.style.set("border", border)
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

        val rowHeader = rows.mapNotNull { dimensionNames[it] }.joinToString(" / ").ifEmpty { "Righe" }
        resultsGrid.addHierarchyColumn { node -> node.label }
            .setHeader(rowHeader)
            .setWidth("220px")
            .setFlexGrow(0)

        val allValueKeys = collectAllValueKeys(rowHierarchy)
        allValueKeys.forEach { key ->
            val parts = key.split("|")
            val headerText = if (parts.size > 1) parts.drop(1).joinToString(" · ") else key
            resultsGrid.addColumn { node -> formatMetricValue(node.values[key]) }
                .setHeader(headerText)
                .setTooltipGenerator { key.replace("|", " · ") }
                .setWidth("120px")
                .setFlexGrow(0)
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

    /** Un EChartComponent per grafico pronto; un placeholder per ogni grafico non coerente col pivot corrente. */
    fun renderCharts(chartsData: List<com.lightningbi.lightning_engine.model.ChartResult>, rows: List<UUID>) {
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
        chartsData.forEach { result ->
            when (result) {
                is com.lightningbi.lightning_engine.model.ChartResult.Ready -> {
                    val chartComponent = EChartComponent()
                    grid.add(chartComponent)
                    chartComponent.render(result.data)
                }
                is com.lightningbi.lightning_engine.model.ChartResult.Incoherent -> {
                    grid.add(buildIncoherentPlaceholder(result.chart.titolo, result.reason))
                }
            }
        }
        chartsPanel.add(grid)
    }

    /**
     * Placeholder mostrato al posto di un grafico non più coerente col
     * pivot corrente (campi rimossi, metriche non più esistenti): il
     * grafico non sparisce silenziosamente, l'utente capisce cosa
     * sistemare e può andare in ChartsView a farlo.
     */
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

    fun clearAll() {
        filtersColumn.removeAll()
        filtersColumn.add(activeSelectionsBar)
        activeSelectionsBar.removeAll()
        resultsGrid.setDataProvider(TreeDataProvider(TreeData()))
        resultsGrid.removeAllColumns()
        chartsPanel.removeAll()
        filterGrids.clear()
        dimensionSpans.clear()
        currentItems.clear()
    }
}