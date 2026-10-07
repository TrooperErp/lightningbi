package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.connector.ConnectionOrchestrator
import com.lightningbi.lightning_engine.model.RuoloTabella
import com.lightningbi.lightning_engine.model.SourceConnection
import com.lightningbi.lightning_engine.service.AdminGuard
import com.lightningbi.lightning_engine.service.EsitoImport
import com.lightningbi.lightning_engine.service.ImportazioneMultiplaService
import com.lightningbi.lightning_engine.service.RichiestaImport
import com.lightningbi.lightning_engine.service.RisultatoImport
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.progressbar.ProgressBar
import com.vaadin.flow.component.select.Select
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.ListDataProvider
import com.vaadin.flow.data.value.ValueChangeMode

/**
 * Importa più tabelle o view di una connessione in un colpo solo: si sceglie
 * connessione e schema, si spuntano le tabelle e si dà a ciascuna il ruolo
 * (Dimensione o Fatti; ci sono scorciatoie per le selezionate). L'importazione
 * registra solo la definizione, senza dati: i dati arrivano con la sincronizzazione.
 *
 * Gira in un thread a parte con avanzamento; alla fine mostra il rapporto
 * (importate, saltate, errori con il motivo). Il controllo "solo admin" si fa
 * qui, nel thread della UI, prima di avviare.
 */
class ImportaPiuTabelleDialog(
    private val connectionOrchestrator: ConnectionOrchestrator,
    private val importazioneService: ImportazioneMultiplaService,
    private val adminGuard: AdminGuard,
    private val onFinished: () -> Unit
) : Dialog() {

    private class Riga(
        val nomeOrigine: String,
        val giaImportata: Boolean,
        var selezionata: Boolean = false,
        var ruolo: RuoloTabella = RuoloTabella.DIMENSIONE
    )

    private val connessioneCombo = ComboBox<SourceConnection>("Connessione")
    private val schemaCombo = ComboBox<String>("Schema")
    private val ricercaField = TextField()
    private val conteggio = Span()
    private val grid = Grid<Riga>()
    private val corpo = VerticalLayout().apply { isPadding = false }

    private var righe: List<Riga> = emptyList()
    private var dataProvider = ListDataProvider<Riga>(emptyList())

    private val chiudiButton = Button("Chiudi") { close() }
    private val importaButton = Button("Importa") { avvia() }
        .apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

    init {
        headerTitle = "Importa più tabelle"
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
            addValueChangeListener { cambiaSchema(it.value) }
        }
        ricercaField.apply {
            placeholder = "Cerca per nome..."
            setWidthFull()
            valueChangeMode = ValueChangeMode.EAGER
            addValueChangeListener { applicaFiltro() }
        }

        grid.apply {
            setWidthFull()
            height = "380px"
            addComponentColumn { riga ->
                Checkbox(riga.selezionata).apply {
                    isEnabled = !riga.giaImportata
                    addValueChangeListener { ev ->
                        riga.selezionata = ev.value
                        aggiornaConteggio()
                    }
                }
            }.setHeader("Importa").setAutoWidth(true).setFlexGrow(0)
            addColumn { it.nomeOrigine }.setHeader("Tabella o view").setAutoWidth(true).setFlexGrow(1)
            addComponentColumn { riga ->
                Select<RuoloTabella>().apply {
                    setItems(RuoloTabella.DIMENSIONE, RuoloTabella.FATTI)
                    setItemLabelGenerator { if (it == RuoloTabella.FATTI) "Fatti" else "Dimensione" }
                    value = riga.ruolo
                    isEnabled = !riga.giaImportata
                    width = "150px"
                    addValueChangeListener { ev -> ev.value?.let { riga.ruolo = it } }
                }
            }.setHeader("Ruolo").setAutoWidth(true).setFlexGrow(0)
            addColumn { if (it.giaImportata) "Già importata" else "" }
                .setHeader("Stato").setAutoWidth(true).setFlexGrow(0)
            setDataProvider(dataProvider)
        }

        val azioni = HorizontalLayout(
            Button("Seleziona visibili") { impostaSelezione(true) }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) },
            Button("Nessuna") { impostaSelezione(false) }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) },
            Button("Selezionate → Dimensione") { impostaRuolo(RuoloTabella.DIMENSIONE) }
                .apply { addThemeVariants(ButtonVariant.LUMO_SMALL) },
            Button("Selezionate → Fatti") { impostaRuolo(RuoloTabella.FATTI) }
                .apply { addThemeVariants(ButtonVariant.LUMO_SMALL) },
            conteggio
        ).apply { isPadding = false; alignItems = com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER }

        corpo.add(connessioneCombo, schemaCombo, ricercaField, azioni, grid)
        add(corpo)
        footer.add(chiudiButton, importaButton)
        aggiornaConteggio()
    }

    // ---------- selezioni a cascata ----------

    private fun cambiaConnessione(connessione: SourceConnection?) {
        schemaCombo.clear()
        schemaCombo.setItems(emptyList())
        schemaCombo.isEnabled = false
        impostaRighe(emptyList())
        if (connessione == null) return
        try {
            val schemi = connectionOrchestrator.listSchemas(connessione.id)
            schemaCombo.setItems(schemi)
            schemaCombo.isEnabled = true
            if (schemi.size == 1) schemaCombo.value = schemi.first()
        } catch (e: Exception) {
            Notification.show("Impossibile leggere gli schemi: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
    }

    private fun cambiaSchema(schema: String?) {
        impostaRighe(emptyList())
        val connessione = connessioneCombo.value ?: return
        if (schema == null) return
        try {
            impostaRighe(
                importazioneService.elenco(connessione.id, schema)
                    .map { Riga(it.nomeOrigine, it.giaImportata) }
            )
        } catch (e: Exception) {
            Notification.show("Impossibile leggere le tabelle: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
    }

    private fun impostaRighe(nuove: List<Riga>) {
        righe = nuove
        dataProvider = ListDataProvider(righe)
        grid.setDataProvider(dataProvider)
        ricercaField.clear()
        aggiornaConteggio()
    }

    // ---------- ricerca e scorciatoie ----------

    private fun applicaFiltro() {
        val testo = ricercaField.value.orEmpty().trim().lowercase()
        if (testo.isEmpty()) dataProvider.clearFilters()
        else dataProvider.setFilter { it.nomeOrigine.lowercase().contains(testo) }
    }

    private fun visibili(): List<Riga> =
        righe.filter { riga ->
            val testo = ricercaField.value.orEmpty().trim().lowercase()
            testo.isEmpty() || riga.nomeOrigine.lowercase().contains(testo)
        }

    private fun impostaSelezione(valore: Boolean) {
        if (valore) visibili().filter { !it.giaImportata }.forEach { it.selezionata = true }
        else righe.forEach { it.selezionata = false }
        dataProvider.refreshAll()
        aggiornaConteggio()
    }

    private fun impostaRuolo(ruolo: RuoloTabella) {
        righe.filter { it.selezionata }.forEach { it.ruolo = ruolo }
        dataProvider.refreshAll()
    }

    private fun aggiornaConteggio() {
        val n = righe.count { it.selezionata && !it.giaImportata }
        conteggio.text = "$n selezionate su ${righe.size}"
    }

    // ---------- importazione ----------

    private fun avvia() {
        try {
            adminGuard.requireAdmin()
        } catch (e: SecurityException) {
            Notification.show(e.message ?: "Operazione non consentita")
            return
        }
        val connessione = connessioneCombo.value
        val schema = schemaCombo.value
        if (connessione == null || schema == null) {
            Notification.show("Scegli connessione e schema")
            return
        }
        val richieste = righe.filter { it.selezionata && !it.giaImportata }
            .map { RichiestaImport(it.nomeOrigine, it.ruolo) }
        if (richieste.isEmpty()) {
            Notification.show("Spunta almeno una tabella")
            return
        }
        val ui = UI.getCurrent() ?: return

        val avanzamento = Span("Avvio...")
        corpo.removeAll()
        corpo.add(ProgressBar().apply { isIndeterminate = true }, avanzamento)
        importaButton.isEnabled = false
        chiudiButton.isEnabled = false
        isCloseOnEsc = false
        isCloseOnOutsideClick = false

        val thread = Thread {
            try {
                val risultati = importazioneService.importa(connessione.id, schema, richieste) { fase ->
                    aggiornaUi(ui) { avanzamento.text = fase }
                }
                aggiornaUi(ui) { mostraRapporto(risultati) }
            } catch (e: Exception) {
                aggiornaUi(ui) {
                    corpo.removeAll()
                    corpo.add(Span("Importazione fallita: ${e.message ?: e::class.simpleName}"))
                    chiudiButton.isEnabled = true
                    isCloseOnEsc = true
                    onFinished()
                }
            }
        }
        thread.isDaemon = true
        thread.name = "importazione-multipla"
        thread.start()
    }

    private fun mostraRapporto(risultati: List<RisultatoImport>) {
        val importate = risultati.count { it.esito == EsitoImport.IMPORTATA }
        val saltate = risultati.count { it.esito == EsitoImport.SALTATA }
        val errori = risultati.count { it.esito == EsitoImport.ERRORE }

        val rapporto = Grid<RisultatoImport>().apply {
            setWidthFull()
            height = "380px"
            addColumn { it.nomeOrigine }.setHeader("Tabella o view").setAutoWidth(true)
            addColumn { it.nomeLogico ?: "—" }.setHeader("Nome logico").setAutoWidth(true)
            addColumn {
                when (it.esito) {
                    EsitoImport.IMPORTATA -> "Importata"
                    EsitoImport.SALTATA -> "Saltata"
                    EsitoImport.ERRORE -> "Errore"
                }
            }.setHeader("Esito").setAutoWidth(true)
            addColumn { it.dettaglio ?: "" }.setHeader("Dettaglio").setAutoWidth(true).setFlexGrow(1)
            setItems(risultati)
        }

        corpo.removeAll()
        corpo.add(Span("Importate: $importate · Saltate: $saltate · Errori: $errori"), rapporto)
        chiudiButton.isEnabled = true
        isCloseOnEsc = true
        isCloseOnOutsideClick = true
        onFinished()
    }

    /** Aggiorna la UI dal thread di importazione; se la pagina è stata chiusa non fa niente. */
    private fun aggiornaUi(ui: UI, azione: () -> Unit) {
        try {
            ui.access { azione() }
        } catch (_: Exception) {
            // UI staccata: l'importazione prosegue comunque.
        }
    }
}