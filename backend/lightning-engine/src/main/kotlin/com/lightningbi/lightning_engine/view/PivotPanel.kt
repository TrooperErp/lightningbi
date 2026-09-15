package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.AreaDimensione
import com.lightningbi.lightning_engine.model.AreaMetrica
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dnd.DragSource
import com.vaadin.flow.component.dnd.DropTarget
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import java.util.UUID

/**
 * Pannello pivot stile Excel: due zone (Righe, Valori) su cui trascinare i
 * campi dell'area. Ogni cambiamento richiama onChange con il nuovo stato,
 * che AssociativeExplorerView usa per rilanciare AggregateService.
 *
 * Non gestisce la zona Colonne (pivot orizzontale) né subtotali: il motore
 * (AggregateService) non li supporta oggi, e non sono necessari per il
 * caso d'uso principale (raggruppare per una o più dimensioni, vedere
 * metriche aggregate).
 *
 * L'ordine in Righe è significativo (cambia la gerarchia del risultato):
 * si riordina con le frecce, non con drag interno, perché il drag&drop
 * nativo di Vaadin non offre riordino fluido dentro una stessa zona.
 */
class PivotPanel(
    private val onChange: (rows: List<UUID>, values: List<UUID>) -> Unit
) : VerticalLayout() {

    private data class Field(val id: UUID, val label: String, val isMetric: Boolean)

    private var allFields: List<Field> = emptyList()
    private val rowFields = mutableListOf<Field>()
    private val valueFields = mutableListOf<Field>()

    private val poolBox = HorizontalLayout().apply { className = "lbi-pivot-pool"; isPadding = false }
    private val rowsBox = VerticalLayout().apply { className = "lbi-pivot-zone"; isPadding = false }
    private val valuesBox = VerticalLayout().apply { className = "lbi-pivot-zone"; isPadding = false }

    init {
        isPadding = false
        className = "lbi-pivot-panel"

        add(
            Span("Campi disponibili (trascina in Righe o Valori)").apply { className = "lbi-wizard-label" },
            poolBox,
            HorizontalLayout(
                VerticalLayout(Span("Righe").apply { className = "lbi-wizard-label" }, rowsBox).apply { isPadding = false },
                VerticalLayout(Span("Valori").apply { className = "lbi-wizard-label" }, valuesBox).apply { isPadding = false }
            ).apply { isPadding = false; setWidthFull() }
        )

        setupDropTarget(rowsBox, isRowZone = true)
        setupDropTarget(valuesBox, isRowZone = false)
    }

    /** Ricostruisce il pannello per una nuova area: pool pieno, zone vuote. */
    fun setFields(dimensioni: List<Pair<AreaDimensione, String>>, metriche: List<AreaMetrica>) {
        allFields = dimensioni.map { (_, nome) -> Field(UUID.randomUUID(), nome, isMetric = false) } +
                metriche.map { Field(it.id, it.nome, isMetric = true) }
        // Nota: le dimensioni non hanno un UUID stabile univoco per colonna
        // qui - il chiamante sostituisce l'id reale (dimensioneId) prima
        // di costruire i Field, vedi buildFieldsFor in AssociativeExplorerView.
        rowFields.clear()
        valueFields.clear()
        renderAll()
    }

    /**
     * Variante con id reali: usata da AssociativeExplorerView, che conosce
     * gli id veri di dimensioni e metriche (dimensioneId, metrica.id).
     */
    fun setFieldsWithIds(dimensioni: List<Pair<UUID, String>>, metriche: List<Pair<UUID, String>>) {
        allFields = dimensioni.map { (id, nome) -> Field(id, nome, isMetric = false) } +
                metriche.map { (id, nome) -> Field(id, nome, isMetric = true) }
        rowFields.clear()
        valueFields.clear()
        // Default ragionevole: tutte le metriche in Valori, nessuna dimensione
        // in Righe - equivale al comportamento "vecchio" (totale unico),
        // così chi non tocca il pivot vede lo stesso risultato di prima.
        valueFields.addAll(allFields.filter { it.isMetric })
        renderAll()
        fireChange()
    }

    private fun setupDropTarget(zone: VerticalLayout, isRowZone: Boolean) {
        val dropTarget = DropTarget.create(zone)
        dropTarget.addDropListener { event ->
            val fieldId = event.dragData.orElse(null) as? UUID ?: return@addDropListener
            val field = allFields.find { it.id == fieldId } ?: return@addDropListener

            // Una dimensione va in Righe, una metrica va in Valori: il
            // drop nella zona sbagliata è ignorato silenziosamente invece
            // di dare un errore - l'utente capisce dal fatto che non
            // succede nulla, senza interruzioni fastidiose.
            if (isRowZone && field.isMetric) return@addDropListener
            if (!isRowZone && !field.isMetric) return@addDropListener

            val target = if (isRowZone) rowFields else valueFields
            if (field !in target) {
                target.add(field)
                renderAll()
                fireChange()
            }
        }
    }

    private fun renderAll() {
        poolBox.removeAll()
        allFields.filter { it !in rowFields && it !in valueFields }.forEach { field ->
            poolBox.add(buildDraggableChip(field))
        }

        rowsBox.removeAll()
        rowFields.forEachIndexed { index, field ->
            rowsBox.add(buildZoneChip(field, rowFields, index))
        }

        valuesBox.removeAll()
        valueFields.forEachIndexed { index, field ->
            valuesBox.add(buildZoneChip(field, valueFields, index))
        }
    }

    private fun buildDraggableChip(field: Field): Span {
        val chip = Span(field.label).apply {
            className = if (field.isMetric) "lbi-pivot-chip lbi-pivot-chip-metric" else "lbi-pivot-chip lbi-pivot-chip-dim"
        }
        val dragSource = DragSource.create(chip)
        dragSource.setDragData(field.id)
        return chip
    }

    /** Chip già posizionato in una zona: frecce per riordinare, x per rimuovere. */
    private fun buildZoneChip(field: Field, list: MutableList<Field>, index: Int): HorizontalLayout {
        val upButton = Button(Icon(VaadinIcon.ARROW_UP)) {
            if (index > 0) {
                list.removeAt(index)
                list.add(index - 1, field)
                renderAll()
                fireChange()
            }
        }.apply { isEnabled = index > 0 }

        val downButton = Button(Icon(VaadinIcon.ARROW_DOWN)) {
            if (index < list.size - 1) {
                list.removeAt(index)
                list.add(index + 1, field)
                renderAll()
                fireChange()
            }
        }.apply { isEnabled = index < list.size - 1 }

        val removeButton = Button(Icon(VaadinIcon.CLOSE_SMALL)) {
            list.removeAt(index)
            renderAll()
            fireChange()
        }

        return HorizontalLayout(Span(field.label), upButton, downButton, removeButton).apply {
            className = "lbi-pivot-zone-item"
            isPadding = false
        }
    }

    private fun fireChange() {
        onChange(rowFields.map { it.id }, valueFields.map { it.id })
    }
}