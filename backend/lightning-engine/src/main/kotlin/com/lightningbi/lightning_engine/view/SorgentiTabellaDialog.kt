// FILE: src/main/kotlin/com/lightningbi/lightning_engine/view/SorgentiTabellaDialog.kt
package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.connector.ConnectionOrchestrator
import com.lightningbi.lightning_engine.model.ImportedTable
import com.lightningbi.lightning_engine.model.SorgenteTabella
import com.lightningbi.lightning_engine.model.SourceConnection
import com.lightningbi.lightning_engine.service.TableImportService
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.IntegerField
import com.vaadin.flow.component.textfield.TextField

/**
 * Sorgenti di una tabella importata: la principale (la sua connessione) più quelle
 * aggiuntive, la stessa vista letta da altri database e accodata nella tabella
 * (come il CONCATENATE di Qlik).
 *
 * Per una sorgente aggiuntiva si possono indicare la ditta da forzare nel campo
 * azienda e il prefisso per le colonne chiave, così le chiavi dei due database non
 * collidono. Aggiungere o togliere una sorgente rende completa la prossima
 * sincronizzazione.
 */
class SorgentiTabellaDialog(
    private val tableImportService: TableImportService,
    private val connectionOrchestrator: ConnectionOrchestrator,
    private val tabella: ImportedTable,
    private val alSalvataggio: () -> Unit
) : Dialog() {

    private val connessioni: Map<java.util.UUID, SourceConnection> =
        connectionOrchestrator.findAll().associateBy { it.id }

    private val griglia = Grid<SorgenteTabella>()
    private val connessioneCombo = ComboBox<SourceConnection>("Connessione")
    private val dittaField = IntegerField("Ditta da forzare")
    private val prefissoField = TextField("Prefisso delle chiavi")

    init {
        headerTitle = "Sorgenti di \"${tabella.nomeLogico}\""
        width = "760px"

        val principale = tabella.connectionId?.let { connessioni[it]?.nome } ?: "—"

        griglia.apply {
            setWidthFull()
            height = "200px"
            addColumn { it.ordine }.setHeader("N.").setAutoWidth(true).setFlexGrow(0)
            addColumn { connessioni[it.connectionId]?.nome ?: "?" }.setHeader("Connessione").setAutoWidth(true)
            addColumn { it.dittaForzata?.toString() ?: "dalla vista" }.setHeader("Ditta").setAutoWidth(true)
            addColumn { it.prefissoChiavi ?: "—" }.setHeader("Prefisso chiavi").setAutoWidth(true)
            addComponentColumn { s ->
                Button("Togli") { togli(s) }.apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR)
                }
            }.setHeader("").setAutoWidth(true).setFlexGrow(0)
        }

        connessioneCombo.apply {
            setItemLabelGenerator { "${it.nome} · ${it.tipo}" }
            width = "260px"
        }
        dittaField.apply {
            width = "150px"
            isClearButtonVisible = true
            helperText = "Vuoto = si legge dalla vista"
        }
        prefissoField.apply {
            width = "170px"
            placeholder = "es. 5|"
            helperText = "Vuoto = nessun prefisso"
        }
        val aggiungi = Button("Aggiungi") { aggiungi() }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        val modulo = HorizontalLayout(connessioneCombo, dittaField, prefissoField, aggiungi).apply {
            isPadding = false
            defaultVerticalComponentAlignment = FlexComponent.Alignment.BASELINE
        }

        add(
            VerticalLayout(
                Span("Sorgente principale (n. 0): $principale"),
                griglia,
                Span("Aggiungi una sorgente: la stessa vista (${tabella.schemaOrigine?.let { "$it." } ?: ""}${tabella.nomeOrigine ?: "?"}) letta da un'altra connessione."),
                modulo,
                Span("Aggiungere o togliere una sorgente rende completa la prossima sincronizzazione.")
            ).apply { isPadding = false }
        )

        footer.add(Button("Chiudi") { close() })
        ricarica()
    }

    private fun ricarica() {
        val sorgenti = tableImportService.sorgenti(tabella.id)
        griglia.setItems(sorgenti)
        val usate = sorgenti.map { it.connectionId }.toSet() + listOfNotNull(tabella.connectionId)
        connessioneCombo.setItems(connessioni.values.filter { it.id !in usate }.sortedBy { it.nome.lowercase() })
        connessioneCombo.clear()
        dittaField.clear()
        prefissoField.clear()
    }

    private fun aggiungi() {
        val connessione = connessioneCombo.value
        if (connessione == null) {
            Notification.show("Scegli la connessione")
            return
        }
        try {
            tableImportService.aggiungiSorgente(tabella.id, connessione.id, dittaField.value, prefissoField.value)
            Notification.show("Sorgente aggiunta: la prossima sincronizzazione sarà completa")
            ricarica()
            alSalvataggio()
        } catch (e: SecurityException) {
            Notification.show(e.message ?: "Operazione non consentita")
        } catch (e: IllegalArgumentException) {
            Notification.show(e.message ?: "Dati non validi", 6000, Notification.Position.MIDDLE)
        } catch (e: Exception) {
            Notification.show("Errore: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
    }

    private fun togli(s: SorgenteTabella) {
        try {
            tableImportService.eliminaSorgente(tabella.id, s.id)
            Notification.show("Sorgente tolta: la prossima sincronizzazione sarà completa")
            ricarica()
            alSalvataggio()
        } catch (e: Exception) {
            Notification.show(e.message ?: "Errore", 6000, Notification.Position.MIDDLE)
        }
    }
}