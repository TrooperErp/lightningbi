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
 * Pannello pivot stile Excel: tre zone (Righe, Colonne, Valori) su cui
 * trascinare i campi dell'area. Righe e Colonne accettano solo dimensioni,
 * Valori solo metriche. Ogni cambiamento richiama onChange con il nuovo
 * stato, che AssociativeExplorerView usa per rilanciare AggregateService.
 *
 * Colonne è l'asse che permette confronti tipo "Fatturato per Mese,
 * spaccato per Anno" (Mese in Righe, Anno in Colonne, Fatturato in
 * Valori) — lo stesso pattern delle pivot table Excel/Qlik.
 */
class PivotPanel(
    private val onChange: (rows: List<UUID>, columns: List<UUID>, values: List<UUID>) -> Unit
) : VerticalLayout() {

    private data class Field(val id: UUID, val label: String, val isMetric: Boolean)

    private var allFields: List<Field> = emptyList()
    private val rowFields = mutableListOf<Field>()
    private val columnFields = mutableListOf<Field>()
    private val valueFields = mutableListOf<Field>()

    /**
     * Campi non trascinabili in questo momento (grigi, ma visibili) -
     * usato dal PivotPanel del grafico: solo i campi attualmente presenti
     * nel pivot dell'Analisi sono ammessi, gli altri restano visibili ma
     * disabilitati, per dare visibilità di "esiste ma non è selezionabile
     * ora" invece di farlo sparire silenziosamente.
     */
    private var disabledFieldIds: Set<UUID> = emptySet()

    // ... (poolBox, rowsBox, columnsBox, valuesBox invariati) ...

    private val poolBox = HorizontalLayout().apply { className = "lbi-pivot-pool"; isPadding = false }
    private val rowsBox = VerticalLayout().apply { className = "lbi-pivot-zone"; isPadding = false }
    private val columnsBox = VerticalLayout().apply { className = "lbi-pivot-zone"; isPadding = false }
    private val valuesBox = VerticalLayout().apply { className = "lbi-pivot-zone"; isPadding = false }

    init {
        // forza il preload del chunk che contiene dndConnector (bundlato insieme a vaadin-icon)
        add(Icon(VaadinIcon.ARROW_UP).apply { isVisible = false })
        isPadding = false
        isSpacing = false
        className = "lbi-pivot-panel"

        add(
            Span("Campi disponibili (trascina in Righe, Colonne o Valori)").apply { className = "lbi-wizard-label" },
            poolBox,
            HorizontalLayout(
                VerticalLayout(Span("Righe").apply { className = "lbi-wizard-label" }, rowsBox).apply { isPadding = false; isSpacing = false; setWidthFull() },
                VerticalLayout(Span("Colonne").apply { className = "lbi-wizard-label" }, columnsBox).apply { isPadding = false; isSpacing = false; setWidthFull() },
                VerticalLayout(Span("Valori").apply { className = "lbi-wizard-label" }, valuesBox).apply { isPadding = false; isSpacing = false; setWidthFull() }
            ).apply {
                isPadding = false
                isSpacing = false
                setWidthFull()
            }
        )

        // setupDropTarget NON va chiamato qui: DragSource/DropTarget di
        // Vaadin devono essere creati DOPO che il componente è attaccato
        // al DOM del browser, altrimenti il client-side (FlowClient) non
        // trova il nodo Flow corretto e il drag&drop fallisce silenziosamente
        // (icona di divieto, errore "Cannot read properties of undefined
        // (reading 'updateDropTarget')" in console). Il chiamante deve
        // invocare ensureDropTargetsAttached() da onAttach().
    }

    private var dropTargetsAttached = false

    /**
     * Registra i DropTarget delle tre zone. Va chiamato una sola volta,
     * DOPO che questo componente è attaccato al DOM (da onAttach() del
     * chiamante) - mai nel costruttore, altrimenti il drag&drop fallisce
     * silenziosamente perché il nodo Flow non è ancora pronto lato client.
     */
    fun ensureDropTargetsAttached() {
        if (dropTargetsAttached) return
        setupDropTarget(rowsBox, zoneType = Zone.ROWS)
        setupDropTarget(columnsBox, zoneType = Zone.COLUMNS)
        setupDropTarget(valuesBox, zoneType = Zone.VALUES)
        dropTargetsAttached = true
    }

    private enum class Zone { ROWS, COLUMNS, VALUES }

    /**
     * Variante con id reali: usata da AssociativeExplorerView, che conosce
     * gli id veri di dimensioni e metriche (dimensioneId, metrica.id).
     *
     * Applica il default (tutte le metriche in Valori, nessuna dimensione
     * in Righe o Colonne). Se c'è uno stato da ripristinare dopo aver
     * chiamato questo metodo, usare restoreState(): setFieldsWithIds da
     * sola cancellerebbe sempre qualunque configurazione precedente.
     */
    fun setFieldsWithIds(dimensioni: List<Pair<UUID, String>>, metriche: List<Pair<UUID, String>>) {
        allFields = dimensioni.map { (id, nome) -> Field(id, nome, isMetric = false) } +
                metriche.map { (id, nome) -> Field(id, nome, isMetric = true) }
        rowFields.clear()
        columnFields.clear()
        valueFields.clear()
        valueFields.addAll(allFields.filter { it.isMetric })
        renderAll()
        fireChange()
    }

    /**
     * Ripristina una configurazione specifica di Righe/Colonne/Valori, al
     * posto del default che setFieldsWithIds applica sempre. Da chiamare
     * SUBITO DOPO setFieldsWithIds, quando esiste uno stato di lavoro
     * salvato da riportare (es. tornando da un'altra pagina): senza questo
     * metodo, il pannello mostrerebbe sempre lo stato "vuoto, tutte le
     * metriche in Valori" indipendentemente da cosa l'utente aveva
     * impostato prima di uscire, perché quella era l'unica via per
     * popolare rowFields/columnFields/valueFields.
     *
     * Gli id non più presenti in allFields (es. una dimensione rimossa
     * nel frattempo) vengono scartati silenziosamente, non causano errore:
     * lo stato salvato può riferirsi a un momento precedente a modifiche
     * dell'area.
     */
    fun restoreState(rowIds: List<UUID>, columnIds: List<UUID>, valueIds: List<UUID>) {
        val byId = allFields.associateBy { it.id }

        rowFields.clear()
        rowFields.addAll(rowIds.mapNotNull { byId[it] })

        columnFields.clear()
        columnFields.addAll(columnIds.mapNotNull { byId[it] })

        valueFields.clear()
        valueFields.addAll(valueIds.mapNotNull { byId[it] })

        renderAll()
        // Nessun fireChange() qui: il chiamante (switchArea) già gestisce
        // il refresh() dopo il ripristino, chiamare onChange qui
        // duplicherebbe il ricalcolo appena fatto per la stessa richiesta.
    }

    private fun setupDropTarget(zone: VerticalLayout, zoneType: Zone) {
        val dropTarget = DropTarget.create(zone)
        dropTarget.addDropListener { event ->
            val fieldId = event.dragData.orElse(null) as? UUID ?: return@addDropListener
            val field = allFields.find { it.id == fieldId } ?: return@addDropListener
            if (field.id in disabledFieldIds) return@addDropListener

            // Righe e Colonne accettano solo dimensioni, Valori solo metriche.
            when (zoneType) {
                Zone.ROWS, Zone.COLUMNS -> if (field.isMetric) return@addDropListener
                Zone.VALUES -> if (!field.isMetric) return@addDropListener
            }

            val target = when (zoneType) {
                Zone.ROWS -> rowFields
                Zone.COLUMNS -> columnFields
                Zone.VALUES -> valueFields
            }
            if (field !in target) {
                target.add(field)
                renderAll()
                fireChange()
            }
        }
    }

    private fun renderAll() {
        poolBox.removeAll()
        allFields.filter { it !in rowFields && it !in columnFields && it !in valueFields }.forEach { field ->
            poolBox.add(buildDraggableChip(field))
        }

        rowsBox.removeAll()
        rowFields.forEachIndexed { index, field ->
            rowsBox.add(buildZoneChip(field, rowFields, index))
        }

        columnsBox.removeAll()
        columnFields.forEachIndexed { index, field ->
            columnsBox.add(buildZoneChip(field, columnFields, index))
        }

        valuesBox.removeAll()
        valueFields.forEachIndexed { index, field ->
            valuesBox.add(buildZoneChip(field, valueFields, index))
        }
    }

    private fun buildDraggableChip(field: Field): Span {
        val isDisabled = field.id in disabledFieldIds
        val chip = Span(field.label).apply {
            className = when {
                isDisabled -> "lbi-pivot-chip lbi-pivot-chip-disabled"
                field.isMetric -> "lbi-pivot-chip lbi-pivot-chip-metric"
                else -> "lbi-pivot-chip lbi-pivot-chip-dim"
            }
        }
        if (!isDisabled) {
            val dragSource = DragSource.create(chip)
            dragSource.setDragData(field.id)
        }
        return chip
    }

    private fun buildZoneChip(field: Field, list: MutableList<Field>, index: Int): HorizontalLayout {
        val label = Span(field.label).apply { className = "lbi-pivot-chip-label" }

        val upButton = Button(Icon(VaadinIcon.ARROW_UP)) {
            if (index > 0) {
                list.removeAt(index)
                list.add(index - 1, field)
                renderAll()
                fireChange()
            }
        }.apply {
            isEnabled = index > 0
            className = "lbi-pivot-chip-btn"
        }

        val downButton = Button(Icon(VaadinIcon.ARROW_DOWN)) {
            if (index < list.size - 1) {
                list.removeAt(index)
                list.add(index + 1, field)
                renderAll()
                fireChange()
            }
        }.apply {
            isEnabled = index < list.size - 1
            className = "lbi-pivot-chip-btn"
        }

        val removeButton = Button(Icon(VaadinIcon.CLOSE_SMALL)) {
            list.removeAt(index)
            renderAll()
            fireChange()
        }.apply { className = "lbi-pivot-chip-btn lbi-pivot-chip-btn-remove" }

        return HorizontalLayout(label, upButton, downButton, removeButton).apply {
            className = if (field.isMetric) "lbi-pivot-zone-chip lbi-pivot-zone-chip-metric" else "lbi-pivot-zone-chip lbi-pivot-zone-chip-dim"
            isPadding = false
        }
    }

    private fun fireChange() {
        onChange(rowFields.map { it.id }, columnFields.map { it.id }, valueFields.map { it.id })
    }

    /**
     * Aggiorna l'insieme dei campi non selezionabili (grigi) senza
     * toccare rowFields/columnFields/valueFields già scelti. Se un campo
     * già presente in una zona diventa disabilitato (perché rimosso dal
     * pivot pagina), resta lì ma visivamente segnalato - la rimozione
     * effettiva dalla zona è compito del chiamante (vedi nota in
     * ChartsView su potatura), non di questo metodo.
     */
    fun setDisabledFields(ids: Set<UUID>) {
        disabledFieldIds = ids
        renderAll()
    }
    /**
     * Stato corrente (Righe, Colonne, Valori) senza passare da onChange -
     * usato dal dialog grafico per leggere la configurazione al momento
     * del salvataggio (Crea/Salva), non ad ogni singola modifica.
     */
    fun currentState(): Triple<List<UUID>, List<UUID>, List<UUID>> =
        Triple(rowFields.map { it.id }, columnFields.map { it.id }, valueFields.map { it.id })
}