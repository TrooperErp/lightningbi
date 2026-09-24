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
 * Le selezioni si vedono SOLO dentro le card (stato verde/selezionato):
 * non esiste una barra "selezioni attive" separata sopra le card, che
 * duplicherebbe la stessa informazione senza motivo.
 *
 * PIVOT: il PivotPanel (Righe/Colonne/Valori) è di nuovo QUI, nella
 * stessa pagina di griglia e grafici - non più su una pagina
 * "Configura Analisi" separata. Cambiare Righe/Colonne non richiede il
 * ricalcolo del motore associativo (verde/bianco/grigio): tocca solo
 * AggregateService, che è già veloce - il costo pesante è legato
 * unicamente alle SELEZIONI, non alla struttura del pivot. Le card
 * filtro restano comunque limitate alle sole dimensioni presenti in
 * Righe/Colonne in quel momento (mai tutte quelle dell'Area), quindi il
 * loro numero resta sempre piccolo.
 *
 * Lo stato sorgente ("Sorgente verificata: X") non vive più qui: è
 * mostrato nella topbar (LbiAppShell.updateSourceStatus).
 */
class AssociativeExplorerUi(
    private val onFilterSelectionChanged: (dimId: UUID, values: Set<Long>) -> Unit,
    private val onRemoveSelection: (dimId: UUID, valueId: Long) -> Unit,
    private val onPivotChanged: (rows: List<UUID>, columns: List<UUID>, values: List<UUID>) -> Unit
) {
    val pivotPanel = PivotPanel { rows, columns, values -> onPivotChanged(rows, columns, values) }
    private val pivotHeaderRows = mutableListOf<com.vaadin.flow.component.grid.HeaderRow>()
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
        // Solo la griglia è dentro lo Scroller: i grafici restano fuori,
        // in un'area fissa sotto, sempre visibili senza dover scrollare.
        val scrollableGrid = Scroller(resultsGrid).apply {
            setWidthFull()
            height = "280px"
            style.set("flex-shrink", "0")
        }

        val centerArea = VerticalLayout(
            pivotPanel,
            Span("Risultati").apply { className = "lbi-section-title" },
            scrollableGrid,
            chartsPanel
        ).apply {
            className = "lbi-center"
            isPadding = true
            setWidthFull()
            setHeightFull()
            setFlexGrow(0.0, pivotPanel)
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
        filterGrids.clear()
        dimensionSpans.clear()
        currentItems.clear()

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
     *
     * NOTA (debito tecnico aperto): quando Colonne ha più di una
     * dimensione (es. Anno + Mese), le colonne qui restano piatte - non
     * c'è ancora un header a più livelli che raggruppi visivamente
     * "2025" sopra i suoi 12 mesi, come già avviene per le Righe con
     * l'espandi/collassa del TreeGrid. Le chiavi (separate da "|")
     * portano già l'informazione gerarchica necessaria; va aggiunto un
     * HeaderRow con più livelli (Grid.prependHeaderRow + join) per
     * sfruttarla.
     */


    fun renderResultsGrid(
        result: AggregateResult,
        rowHierarchy: List<PivotEngine.PivotNode>,
        rows: List<UUID>,
        dimensionNames: Map<UUID, String>
    ) {
        resultsGrid.removeAllColumns()

        // removeAllColumns() non rimuove le header row aggiunte con
        // prependHeaderRow(): le rimuovo esplicitamente per riferimento,
        // evitando accumulo ad ogni render successivo (es. dopo aver
        // spostato una riga con le frecce su/giù).
        pivotHeaderRows.forEach { resultsGrid.removeHeaderRow(it) }
        pivotHeaderRows.clear()

        if (rowHierarchy.isEmpty()) {
            resultsGrid.setDataProvider(TreeDataProvider(TreeData()))
            return
        }

        val treeData = TreeData<PivotEngine.PivotNode>()
        addNodesRecursively(treeData, null, rowHierarchy)
        resultsGrid.setDataProvider(TreeDataProvider(treeData))

        val rowHeader = rows.mapNotNull { dimensionNames[it] }.joinToString(" / ").ifEmpty { "Righe" }
        val hierarchyColumn = resultsGrid.addHierarchyColumn { node -> node.label }
            .setHeader(rowHeader)
            .setWidth("220px")
            .setFlexGrow(0)

        val allValueKeys = collectAllValueKeys(rowHierarchy)
        val dataColumns = allValueKeys.map { key ->
            val parts = key.split("|")
            val headerText = if (parts.size > 1) parts.drop(1).joinToString(" · ") else key
            resultsGrid.addColumn { node -> formatMetricValue(node.values[key]) }
                .setHeader(headerText)
                .setTooltipGenerator { key.replace("|", " · ") }
                .setWidth("120px")
                .setFlexGrow(0)
        }

        // Un livello di header aggiuntivo per ogni dimensione in Colonne,
        // sopra il livello "nome metrica · valori" già impostato con
        // .setHeader() sulle colonne dati. Ogni key è "Metrica|val1|val2|...",
        // quindi i livelli extra sono (numero di parti - 1). Si costruiscono
        // dal livello più esterno (Anno) al più interno (Mese), ognuno con
        // una prependHeaderRow() dedicata, così l'ultima chiamata resta la
        // più esterna e finisce in cima come nelle pivot Excel/Qlik.
        val columnLevels = allValueKeys.maxOfOrNull { it.split("|").size - 1 } ?: 0

        if (columnLevels > 0) {
            for (level in (columnLevels - 1) downTo 0) {
                val levelValues = allValueKeys.map { it.split("|").getOrNull(level + 1) ?: "" }
                val headerRow = resultsGrid.prependHeaderRow()
                pivotHeaderRows.add(headerRow)

                var i = 0
                while (i < dataColumns.size) {
                    val value = levelValues[i]
                    var j = i
                    // Due colonne sono raggruppabili allo stesso livello solo
                    // se condividono anche tutti i livelli più esterni già
                    // raggruppati in questo ciclo: altrimenti "Gennaio 2025"
                    // e "Gennaio 2026" verrebbero unite per errore solo
                    // perché condividono la label "Gennaio".
                    while (j + 1 < dataColumns.size) {
                        val partsJ = allValueKeys[j + 1].split("|")
                        val partsI = allValueKeys[i].split("|")
                        val samePrefix = (0 until level).all { l -> partsI.getOrNull(l + 1) == partsJ.getOrNull(l + 1) }
                        if (samePrefix && levelValues[j + 1] == value) j++ else break
                    }
                    val group = dataColumns.subList(i, j + 1).toTypedArray()
                    val cell = if (group.size > 1) headerRow.join(*group) else headerRow.getCell(group[0])
                    cell.text = value
                    i = j + 1
                }
            }
        }

        // Riordino esplicito DOPO aver creato le header row:
        // prependHeaderRow() può alterare l'ordine interno delle colonne,
        // spostando la colonna gerarchica lontano dalla prima posizione.
        resultsGrid.setColumnOrder(buildList {
            add(hierarchyColumn)
            addAll(dataColumns)
        })

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
        resultsGrid.setDataProvider(TreeDataProvider(TreeData()))
        resultsGrid.removeAllColumns()
        chartsPanel.removeAll()
        filterGrids.clear()
        dimensionSpans.clear()
        currentItems.clear()
    }
}