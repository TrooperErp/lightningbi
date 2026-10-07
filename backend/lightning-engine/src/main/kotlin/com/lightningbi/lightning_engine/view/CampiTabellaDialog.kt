package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.ImportedColumn
import com.lightningbi.lightning_engine.model.ImportedTable
import com.lightningbi.lightning_engine.service.TableImportService
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import java.util.UUID

/**
 * Nomi campo di una tabella importata. Il nome campo è il nome con cui la
 * colonna entra nel modello: due colonne con lo stesso nome campo, in tabelle
 * diverse, si associano (come in Qlik). Si decide qui, una volta, e vale per
 * tutti i dataset.
 *
 * Si può cambiare solo finché la tabella non è in nessun dataset. Una modifica
 * ricrea la tabella: la prossima sincronizzazione la ricarica per intero.
 */
class CampiTabellaDialog(
    private val tableImportService: TableImportService,
    private val tabella: ImportedTable,
    /** Tabella usata da almeno un dataset: nomi bloccati. */
    private val inDataset: Boolean,
    private val alSalvataggio: () -> Unit
) : Dialog() {

    private val colonne: List<ImportedColumn> = tableImportService.colonne(tabella.id)
    private val campi = LinkedHashMap<UUID, TextField>()

    init {
        headerTitle = "Campi di \"${tabella.nomeLogico}\""
        width = "800px"
        height = "70vh"

        val contenuto = VerticalLayout().apply {
            isPadding = false
            setSizeFull()
        }
        if (inDataset) {
            contenuto.add(
                Span(
                    "La tabella è usata da almeno un dataset: i nomi campo non si possono più cambiare. " +
                            "Toglila dai dataset per modificarli."
                )
            )
        } else {
            contenuto.add(
                Span(
                    "Il nome campo decide le associazioni: campi con lo stesso nome in tabelle diverse si associano. " +
                            "Dopo una modifica la tabella si ricarica per intero alla prossima sincronizzazione."
                )
            )
        }

        val grid = Grid<ImportedColumn>().apply {
            setSizeFull()
            addColumn { it.nome }.setHeader("Colonna").setAutoWidth(true)
            addColumn { it.tipo }.setHeader("Tipo").setAutoWidth(true)
            addComponentColumn { c ->
                TextField().apply {
                    value = c.nomeCampo
                    width = "100%"
                    isReadOnly = inDataset || c.derivata
                    if (!isReadOnly) campi[c.id] = this
                }
            }.setHeader("Nome campo").setFlexGrow(2)
            setItems(colonne)
        }
        contenuto.add(grid)
        contenuto.setFlexGrow(1.0, grid)
        add(contenuto)

        val chiudi = Button("Chiudi") { close() }
        footer.add(chiudi)
        if (!inDataset) {
            val salva = Button("Salva") { salva() }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
            footer.add(salva)
        }
    }

    private fun salva() {
        val rinomine = colonne
            .filter { campi.containsKey(it.id) }
            .associate { it.id to campi.getValue(it.id).value.trim() }
            .filter { (id, nuovo) -> nuovo != colonne.first { it.id == id }.nomeCampo }
        if (rinomine.isEmpty()) {
            Notification.show("Nessuna modifica")
            return
        }
        try {
            val n = tableImportService.rinominaCampi(tabella.id, rinomine)
            Notification.show("Campi modificati: $n. La tabella si ricarica alla prossima sincronizzazione.")
            alSalvataggio()
            close()
        } catch (e: SecurityException) {
            Notification.show(e.message ?: "Operazione non consentita")
        } catch (e: IllegalArgumentException) {
            Notification.show(e.message ?: "Nome non valido")
        } catch (e: IllegalStateException) {
            Notification.show(e.message ?: "Operazione non possibile")
        }
    }
}