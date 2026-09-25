package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.service.DimensionState
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import java.util.UUID

class FilterCardsUi(
    private val onFilterSelectionChanged: (dimId: UUID, values: Set<Long>) -> Unit
) {
    val root = VerticalLayout().apply {
        className = "lbi-filters-column"
        width = "420px"
        height = "100%"
    }

    private val filterGrids = mutableMapOf<UUID, Div>()
    private val dimensionSpans = mutableMapOf<UUID, MutableMap<Long, Span>>()
    private val currentItems = mutableMapOf<UUID, List<Long>>()
    private val filterTitles = mutableMapOf<UUID, Span>()
    private val filterTotals = mutableMapOf<UUID, Int>()
    private val expandedCards = mutableMapOf<UUID, Boolean>()

    // Cache dell'ULTIMO stato/label ricevuti per ogni dimensione: serve
    // per poter generare gli Span "on demand" quando l'utente espande
    // una card che finora era collassata, senza dover aspettare il
    // prossimo refresh completo. Senza questa cache, una dimensione ad
    // alta cardinalità mai espansa non avrebbe mai renderizzato nulla,
    // ma appena aperta dopo un click non avrebbe dati da mostrare finché
    // non arriva un nuovo giro di renderStates.
    private var lastLabels: Map<UUID, Map<Long, String>> = emptyMap()
    private var lastLabelOrFallback: ((Map<Long, String>, Long?) -> String)? = null
    private var lastColonnaFisicaFor: ((UUID) -> String?)? = null
    private val lastStates = mutableMapOf<UUID, DimensionState>()

    private val nomiColonneComuni = setOf("codice_ditta", "anno", "mese_numero", "giorno")

    fun rebuildFilterCards(
        allDimIds: List<UUID>,
        dimensionNames: Map<UUID, String>,
        columnFor: (UUID) -> String?,
        countSameName: (String) -> Int,
        hiddenDimIds: Set<UUID> = emptySet()
    ) {
        root.removeAll()
        filterGrids.clear()
        dimensionSpans.clear()
        currentItems.clear()
        filterTitles.clear()
        filterTotals.clear()
        lastStates.clear()

        val visibili = allDimIds.filter { it !in hiddenDimIds }
        val comuni = visibili.filter { dimId -> columnFor(dimId) in nomiColonneComuni }
        val altre = visibili.filter { dimId -> dimId !in comuni }

        fun buildCard(dimId: UUID, expandedByDefault: Boolean): VerticalLayout? {
            val dimName = dimensionNames[dimId] ?: return null

            val grid = Div().apply {
                className = "lbi-filter-grid"
                isVisible = expandedByDefault
            }
            filterGrids[dimId] = grid
            dimensionSpans[dimId] = mutableMapOf()
            expandedCards[dimId] = expandedByDefault

            val colonna = columnFor(dimId)
            val etichettaBase = if (countSameName(dimName) > 1 && colonna != null) "$dimName ($colonna)" else dimName

            val title = Span(etichettaBase).apply {
                className = "lbi-filter-title"
                style.set("cursor", "pointer")
                addClickListener {
                    val nowExpanded = !(expandedCards[dimId] ?: true)
                    expandedCards[dimId] = nowExpanded
                    grid.isVisible = nowExpanded
                    // Espansione on demand: se la card non ha ancora mai
                    // costruito i suoi Span (currentItems assente) e ora
                    // viene aperta, li genera subito dall'ultimo stato
                    // ricevuto, invece di aspettare il prossimo refresh.
                    if (nowExpanded && currentItems[dimId] == null) {
                        lastStates[dimId]?.let { state -> renderOneDimension(dimId, state) }
                    }
                }
            }
            filterTitles[dimId] = title

            return VerticalLayout(title, grid).apply { className = "lbi-filter-card" }
        }

        if (comuni.isNotEmpty()) {
            val comuniGroup = VerticalLayout().apply { isPadding = false; className = "lbi-filter-group lbi-filter-group-common" }
            comuni.forEach { dimId -> buildCard(dimId, expandedByDefault = true)?.let { comuniGroup.add(it) } }
            root.add(comuniGroup)
        }

        if (altre.isNotEmpty()) {
            val altreGrid = HorizontalLayout().apply {
                className = "lbi-filter-others-grid"
                isPadding = false
                isWrap = true
            }
            altre.forEach { dimId -> buildCard(dimId, expandedByDefault = false)?.let { altreGrid.add(it) } }
            root.add(altreGrid)
        }
    }

    fun deselectValue(dimId: UUID, valueId: Long) {
        dimensionSpans[dimId]?.get(valueId)?.className = "state-possible"
    }

    fun deselectAll(dimId: UUID) {
        dimensionSpans[dimId]?.values?.forEach { it.className = "state-possible" }
    }

    /**
     * Aggiorna SOLO il conteggio N/M per ogni dimensione (leggero,
     * nessun DOM), e costruisce/aggiorna gli Span reali soltanto per le
     * card attualmente espanse - le altre restano "fredde" finché
     * l'utente non le apre, evitando di generare migliaia di Span per
     * dimensioni ad alta cardinalità mai guardate.
     */
    fun renderStates(
        states: Map<UUID, DimensionState>,
        labels: Map<UUID, Map<Long, String>>,
        labelOrFallback: (Map<Long, String>, Long?) -> String,
        colonnaFisicaFor: (UUID) -> String? = { null }
    ) {
        lastLabels = labels
        lastLabelOrFallback = labelOrFallback
        lastColonnaFisicaFor = colonnaFisicaFor
        lastStates.putAll(states)

        states.forEach { (dimId, state) ->
            filterTitles[dimId]?.let { title ->
                val dimLabels = labels[dimId] ?: emptyMap()
                val totale = filterTotals.getOrPut(dimId) {
                    (state.verdi + state.grigi + state.selezionati).distinct().size
                }
                val possibili = state.verdi.size + state.selezionati.size
                val baseText = title.text?.substringBefore(" (") ?: ""
                title.text = "$baseText ($possibili/$totale)"
            }

            if (expandedCards[dimId] == true) {
                renderOneDimension(dimId, state)
            }
        }
    }

    /** Costruisce/aggiorna gli Span di UNA dimensione (chiamato per le card espanse, o on demand all'espansione). */
    private fun renderOneDimension(dimId: UUID, state: DimensionState) {
        val grid = filterGrids[dimId] ?: return
        val spanMap = dimensionSpans[dimId] ?: return
        val labelOrFallback = lastLabelOrFallback ?: return
        val dimLabels = lastLabels[dimId] ?: emptyMap()
        val colonna = lastColonnaFisicaFor?.invoke(dimId)

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

    fun clearAll() {
        root.removeAll()
        filterGrids.clear()
        dimensionSpans.clear()
        currentItems.clear()
        filterTitles.clear()
        filterTotals.clear()
        expandedCards.clear()
        lastStates.clear()
    }
}