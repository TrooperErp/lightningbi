package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.connector.ConnectionOrchestrator
import com.lightningbi.lightning_engine.model.RuoloTabella
import com.lightningbi.lightning_engine.model.SourceConnection
import com.lightningbi.lightning_engine.service.ColonnaImport
import com.lightningbi.lightning_engine.service.ColumnProposal
import com.lightningbi.lightning_engine.service.TableImportService
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.textfield.TextField

/**
 * Aggiunge una tabella importata, indipendente dai dataset: connessione,
 * schema, tabella o view della sorgente, nome logico, ruolo (Fatti o
 * Dimensione) e colonne.
 *
 * Le colonne sono tutte incluse di default (se ne possono escludere). Le
 * colonne chiave servono a collegare Fatti e Dimensioni e a sostituire le
 * righe in sincronizzazione: per una Dimensione se ne sceglie una sola, per
 * un Fatti se ne possono segnare quante servono. Quelle il cui nome inizia con
 * un prefisso di colonna chiave della connessione partono segnate come chiave.
 * Il ruolo Dimensione/Metrica delle colonne non si sceglie qui: lo decide il
 * dataset.
 */
class AddTableDialog(
    private val connectionOrchestrator: ConnectionOrchestrator,
    private val tableImportService: TableImportService,
    private val onSaved: () -> Unit
) : Dialog() {

    private class RigaColonna(val nome: String, val tipo: String, var include: Boolean, var chiave: Boolean)

    private val connessioneCombo = ComboBox<SourceConnection>("Connessione")
    private val schemaCombo = ComboBox<String>("Schema")
    private val tabellaCombo = ComboBox<String>("Tabella o view")
    private val nomeLogicoField = TextField("Nome logico")
    private val ruoloGroup = RadioButtonGroup<RuoloTabella>("Ruolo")
    private val chiaveCombo = ComboBox<String>("Colonna chiave della Dimensione")
    private val colonneGrid = Grid<RigaColonna>()
    private var righe: List<RigaColonna> = emptyList()

    private var prefissiColonnaChiave: List<String> = emptyList()
    private var prefissiDaTogliere: List<String> = emptyList()

    private lateinit var colonnaChiaveGrid: Grid.Column<RigaColonna>

    init {
        headerTitle = "Aggiungi tabella"
        width = "900px"

        connessioneCombo.apply {
            setItems(connectionOrchestrator.findAll().sortedBy { it.nome.lowercase() })
            setItemLabelGenerator { "${it.nome} · ${it.tipo}" }
            setWidthFull()
            addValueChangeListener { cambiaConnessione(it.value) }
        }
        schemaCombo.apply {
            setWidthFull()
            isEnabled = false
            addValueChangeListener { if (it.isFromClient) cambiaSchema(it.value) }
        }
        tabellaCombo.apply {
            setWidthFull()
            isEnabled = false
            addValueChangeListener { if (it.isFromClient) cambiaTabella(it.value) }
        }
        nomeLogicoField.setWidthFull()
        ruoloGroup.apply {
            setItems(RuoloTabella.FATTI, RuoloTabella.DIMENSIONE)
            setItemLabelGenerator { if (it == RuoloTabella.FATTI) "Fatti" else "Dimensione" }
            value = RuoloTabella.DIMENSIONE
            addValueChangeListener { aggiornaRuolo() }
        }
        chiaveCombo.apply {
            setWidthFull()
            helperText = "Colonna con cui la Dimensione si collega ai Fatti"
        }

        colonneGrid.apply {
            setWidthFull()
            height = "300px"
            addComponentColumn { riga ->
                Checkbox(riga.include).apply {
                    addValueChangeListener { ev ->
                        riga.include = ev.value
                        if (!ev.value) riga.chiave = false
                        colonneGrid.dataProvider.refreshItem(riga)
                        aggiornaChiaveCombo()
                    }
                }
            }.setHeader("Includi").setAutoWidth(true).setFlexGrow(0)
            addColumn { it.nome }.setHeader("Colonna").setAutoWidth(true)
            addColumn { it.tipo }.setHeader("Tipo").setAutoWidth(true)
            colonnaChiaveGrid = addComponentColumn { riga ->
                Checkbox(riga.chiave).apply {
                    isEnabled = riga.include
                    addValueChangeListener { ev -> riga.chiave = ev.value }
                }
            }.setHeader("Chiave").setAutoWidth(true).setFlexGrow(0)
        }

        add(
            VerticalLayout(
                connessioneCombo, schemaCombo, tabellaCombo, nomeLogicoField, ruoloGroup, chiaveCombo,
                Span("Colonne da importare").apply { className = "lbi-wizard-label" },
                colonneGrid
            ).apply { isPadding = false }
        )
        aggiornaRuolo()

        footer.add(
            Button("Annulla") { close() },
            Button("Aggiungi") { salva() }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        )
    }

    // ---------- selezioni a cascata ----------

    private fun cambiaConnessione(connessione: SourceConnection?) {
        schemaCombo.clear()
        schemaCombo.setItems(emptyList())
        schemaCombo.isEnabled = false
        resettaTabelle()
        if (connessione == null) return

        prefissiColonnaChiave = ColumnProposal.lista(connessione.parametri, ColumnProposal.PREFISSI_COLONNA_CHIAVE)
        prefissiDaTogliere = ColumnProposal.lista(connessione.parametri, ColumnProposal.PREFISSI_DA_TOGLIERE)
        try {
            schemaCombo.setItems(connectionOrchestrator.listSchemas(connessione.id))
            schemaCombo.isEnabled = true
        } catch (e: Exception) {
            Notification.show("Impossibile leggere gli schemi: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
    }

    private fun cambiaSchema(schema: String?) {
        resettaTabelle()
        val connessione = connessioneCombo.value ?: return
        if (schema == null) return
        try {
            tabellaCombo.setItems(connectionOrchestrator.listTables(connessione.id, schema).map { it.name }.sorted())
            tabellaCombo.isEnabled = true
        } catch (e: Exception) {
            Notification.show("Impossibile leggere le tabelle: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
    }

    private fun cambiaTabella(tabella: String?) {
        righe = emptyList()
        colonneGrid.setItems(righe)
        chiaveCombo.clear()
        val connessione = connessioneCombo.value ?: return
        if (tabella == null) return

        nomeLogicoField.value = ColumnProposal.nomeLogico(tabella, prefissiDaTogliere)
        try {
            val colonne = connectionOrchestrator.listColumns(connessione.id, schemaCombo.value, tabella)
            righe = colonne.map { c ->
                RigaColonna(c.name, c.typeName, include = true, chiave = isTecnica(c.name))
            }
            colonneGrid.setItems(righe)
            aggiornaChiaveCombo()
            // Dimensione: la prima colonna tecnica è la candidata come chiave.
            chiaveCombo.value = righe.firstOrNull { isTecnica(it.nome) }?.nome
        } catch (e: Exception) {
            Notification.show("Impossibile leggere le colonne: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
    }

    private fun resettaTabelle() {
        tabellaCombo.clear()
        tabellaCombo.setItems(emptyList())
        tabellaCombo.isEnabled = false
        nomeLogicoField.clear()
        righe = emptyList()
        colonneGrid.setItems(righe)
        chiaveCombo.clear()
    }

    private fun isTecnica(nome: String): Boolean = prefissiColonnaChiave.any { nome.startsWith(it, ignoreCase = true) }

    // ---------- ruolo e chiave ----------

    private fun aggiornaRuolo() {
        val dimensione = ruoloGroup.value == RuoloTabella.DIMENSIONE
        // Dimensione: una sola colonna chiave, scelta qui sopra. Fatti: quante servono, nella griglia.
        chiaveCombo.isVisible = dimensione
        colonnaChiaveGrid.isVisible = !dimensione
    }

    private fun aggiornaChiaveCombo() {
        val inclusi = righe.filter { it.include }.map { it.nome }
        val corrente = chiaveCombo.value
        chiaveCombo.setItems(inclusi)
        chiaveCombo.value = corrente?.takeIf { it in inclusi }
    }

    // ---------- salvataggio ----------

    private fun salva() {
        val connessione = connessioneCombo.value
        val tabella = tabellaCombo.value
        if (connessione == null || tabella == null) {
            Notification.show("Scegli connessione, schema e tabella")
            return
        }
        val ruolo = ruoloGroup.value ?: RuoloTabella.DIMENSIONE
        val chiaveDimensione = if (ruolo == RuoloTabella.DIMENSIONE) chiaveCombo.value else null
        if (ruolo == RuoloTabella.DIMENSIONE && chiaveDimensione == null) {
            Notification.show("Scegli la colonna chiave della Dimensione")
            return
        }

        val colonne = righe.filter { it.include }.map { riga ->
            val chiave = if (ruolo == RuoloTabella.DIMENSIONE) {
                riga.nome.equals(chiaveDimensione, ignoreCase = true)
            } else {
                riga.chiave
            }
            ColonnaImport(riga.nome, riga.tipo, chiave)
        }

        try {
            tableImportService.aggiungi(
                connectionId = connessione.id,
                schema = schemaCombo.value,
                nomeOrigine = tabella,
                nomeLogico = nomeLogicoField.value.orEmpty(),
                ruolo = ruolo,
                colonnaChiave = chiaveDimensione,
                colonne = colonne
            )
            Notification.show("Tabella aggiunta")
            close()
            onSaved()
        } catch (e: Exception) {
            Notification.show(e.message ?: "Errore", 7000, Notification.Position.MIDDLE)
        }
    }
}