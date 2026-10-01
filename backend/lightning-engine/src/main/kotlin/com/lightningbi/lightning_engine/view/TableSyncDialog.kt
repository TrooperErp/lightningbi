package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.ImportedTable
import com.lightningbi.lightning_engine.model.ModalitaSync
import com.lightningbi.lightning_engine.model.TableSync
import com.lightningbi.lightning_engine.service.QueryNonValidaException
import com.lightningbi.lightning_engine.service.QueryScadutaException
import com.lightningbi.lightning_engine.service.TableImportService
import com.lightningbi.lightning_engine.service.TableSyncService
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.MultiSelectComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.textfield.IntegerField
import com.vaadin.flow.component.textfield.TextArea

/**
 * Configurazione della sincronizzazione di una tabella importata: completa
 * (si ricarica tutto) oppure incrementale (si rileggono solo le unità
 * cambiate).
 *
 * Per l'incrementale si indicano le colonne che identificano l'unità da
 * sostituire e le query, nel linguaggio della sorgente, che restituiscono le
 * chiavi delle unità cambiate dopo `:ultima_sync` e (opzionale) di quelle da
 * rileggere sempre. Le query si possono provare: la prova controlla che
 * restituiscano le colonne dell'unità, senza leggere un insieme di chiavi.
 * Salvare in incrementale prova le query di nuovo, quindi un salvataggio
 * riuscito vale come prova.
 *
 * La prova gira nel thread della richiesta: su una sorgente lenta la finestra
 * resta in attesa fino al timeout (configurabile dal server).
 */
class TableSyncDialog(
    private val tableSyncService: TableSyncService,
    tableImportService: TableImportService,
    private val tabella: ImportedTable,
    private val onSaved: () -> Unit
) : Dialog() {

    private val esistente: TableSync = tableSyncService.leggi(tabella.id)

    private val modalitaGroup = RadioButtonGroup<ModalitaSync>("Modalità")
    private val unitaCombo = MultiSelectComboBox<String>("Colonne che identificano l'unità da sostituire")
    private val queryCambiatiArea = TextArea("Query delle unità cambiate")
    private val querySempreArea = TextArea("Query delle unità da rileggere sempre (facoltativa)")
    private val cancellazioniCheck = Checkbox("Elimina le unità sparite dalla sorgente")
    private val margineField = IntegerField("Margine (minuti)")
    private val datastampCheck = Checkbox(
        "Il datastamp è scritto dall'orologio del database sorgente e l'ho verificato con una modifica reale"
    )
    private val pannelloIncrementale = VerticalLayout().apply { isPadding = false }

    init {
        headerTitle = "Sincronizzazione di \"${tabella.nomeLogico}\""
        width = "760px"

        modalitaGroup.apply {
            setItems(ModalitaSync.COMPLETA, ModalitaSync.INCREMENTALE)
            setItemLabelGenerator { if (it == ModalitaSync.COMPLETA) "Completa" else "Incrementale" }
            value = esistente.modalita
            addValueChangeListener { aggiornaVisibilita() }
        }

        val nomiColonne = tableImportService.colonne(tabella.id).map { it.nome }.sorted()
        unitaCombo.apply {
            setWidthFull()
            setItems(nomiColonne)
            select(esistente.colonneUnita.filter { it in nomiColonne })
            helperText = "Devono essere tra le colonne importate: servono per sostituire le righe"
        }
        queryCambiatiArea.apply {
            setWidthFull()
            minHeight = "110px"
            value = esistente.queryCambiati.orEmpty()
            placeholder = "SELECT <colonne dell'unità> FROM ... WHERE <datastamp> > :ultima_sync"
            helperText = "Deve restituire esattamente le colonne dell'unità, con gli stessi nomi. " +
                    ":ultima_sync è il valore di riferimento (parametro, non testo). Solo SELECT o WITH."
        }
        querySempreArea.apply {
            setWidthFull()
            minHeight = "90px"
            value = esistente.querySempre.orEmpty()
            helperText = "Per le unità il cui stato dipende da altre (si rileggono a ogni giro). " +
                    "Stesse regole: restituisce le colonne dell'unità."
        }
        cancellazioniCheck.value = esistente.confrontaCancellazioni
        margineField.apply {
            min = 0
            max = 10080
            isStepButtonsVisible = true
            value = esistente.margineSecondi / 60
            helperText = "Almeno 60 minuti: copre il cambio dell'ora legale. Ridurlo è un limite accettato."
        }
        datastampCheck.value = esistente.datastampVerificato

        val pulsantiProva = HorizontalLayout(
            Button("Prova query dei cambiati") { prova(queryCambiatiArea.value, "query dei cambiati") },
            Button("Prova query sempre") { prova(querySempreArea.value, "query delle unità da rileggere sempre") }
        ).apply { isPadding = false }

        pannelloIncrementale.add(
            unitaCombo, queryCambiatiArea, querySempreArea, pulsantiProva,
            cancellazioniCheck, margineField, datastampCheck
        )

        add(
            VerticalLayout(
                Span("Ultima sincronizzazione: " + (esistente.ultimaSyncInizio?.toString()?.replace('T', ' ') ?: "mai")),
                modalitaGroup,
                pannelloIncrementale
            ).apply { isPadding = false }
        )
        aggiornaVisibilita()

        footer.add(
            Button("Annulla") { close() },
            Button("Salva") { salva() }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        )
    }

    private fun aggiornaVisibilita() {
        pannelloIncrementale.isVisible = modalitaGroup.value == ModalitaSync.INCREMENTALE
    }

    private fun margineSecondi(): Int = (margineField.value ?: 60) * 60

    private fun colonneUnita(): List<String> = unitaCombo.value.toList().sorted()

    private fun prova(query: String?, etichetta: String) {
        try {
            val esito = tableSyncService.provaQuery(
                tabella.id, query.orEmpty(), colonneUnita(), margineSecondi(), etichetta
            )
            val esempio = esito.esempio?.entries?.joinToString(", ") { "${it.key}=${it.value}" } ?: "nessuna riga"
            Notification.show(
                "La $etichetta è valida. Colonne: ${esito.colonne.joinToString(", ")}. Esempio: $esempio",
                8000, Notification.Position.MIDDLE
            )
        } catch (e: QueryScadutaException) {
            Notification.show("${e.message}", 9000, Notification.Position.MIDDLE)
        } catch (e: QueryNonValidaException) {
            Notification.show("${e.message}", 9000, Notification.Position.MIDDLE)
        } catch (e: Exception) {
            Notification.show(e.message ?: "Errore", 7000, Notification.Position.MIDDLE)
        }
    }

    private fun salva() {
        val sync = TableSync(
            importedTableId = tabella.id,
            modalita = modalitaGroup.value ?: ModalitaSync.COMPLETA,
            colonneUnita = colonneUnita(),
            queryCambiati = queryCambiatiArea.value?.takeIf { it.isNotBlank() },
            querySempre = querySempreArea.value?.takeIf { it.isNotBlank() },
            confrontaCancellazioni = cancellazioniCheck.value,
            margineSecondi = margineSecondi(),
            datastampVerificato = datastampCheck.value,
            // Salvare la configurazione non tocca l'ultima sincronizzazione.
            ultimaSyncInizio = esistente.ultimaSyncInizio
        )
        try {
            tableSyncService.salva(sync)
            Notification.show("Configurazione salvata")
            close()
            onSaved()
        } catch (e: QueryScadutaException) {
            Notification.show("${e.message}", 9000, Notification.Position.MIDDLE)
        } catch (e: Exception) {
            Notification.show(e.message ?: "Errore", 9000, Notification.Position.MIDDLE)
        }
    }
}