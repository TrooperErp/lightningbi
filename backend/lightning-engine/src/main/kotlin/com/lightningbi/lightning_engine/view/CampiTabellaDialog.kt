package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.ImportedColumn
import com.lightningbi.lightning_engine.model.ImportedTable
import com.lightningbi.lightning_engine.service.Naming
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
import com.vaadin.flow.component.checkbox.Checkbox

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
    private val testoIniziale: Set<String> = tableImportService.campiTesto()
    private val testo = LinkedHashMap<UUID, Checkbox>()

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
            addComponentColumn { c ->
                Checkbox().apply {
                    value = Naming.column(c.nomeCampo) in testoIniziale
                    isEnabled = !c.derivata
                    testo[c.id] = this
                }
            }.setHeader("Testo").setAutoWidth(true)
            setItems(colonne)
        }
        contenuto.add(grid)
        contenuto.setFlexGrow(1.0, grid)
        add(contenuto)

        val chiudi = Button("Chiudi") { close() }
        footer.add(chiudi)
        val salva = Button("Salva") { salva() }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        footer.add(salva)
    }

    private fun salva() {
        val rinomine = colonne
            .filter { campi.containsKey(it.id) }
            .associate { it.id to campi.getValue(it.id).value.trim() }
            .filter { (id, nuovo) -> nuovo != colonne.first { it.id == id }.nomeCampo }
        val cambiTesto = colonne
            .filter { testo.containsKey(it.id) && !it.derivata }
            .filter { testo.getValue(it.id).value != (Naming.column(it.nomeCampo) in testoIniziale) }
        if (rinomine.isEmpty() && cambiTesto.isEmpty()) {
            Notification.show("Nessuna modifica")
            return
        }
        try {
            // Il flag va sul nome campo come sarà DOPO la rinomina.
            val nuoviNomi = colonne.associate { it.id to (rinomine[it.id] ?: it.nomeCampo) }
            if (rinomine.isNotEmpty()) tableImportService.rinominaCampi(tabella.id, rinomine)
            cambiTesto.forEach { tableImportService.impostaTesto(nuoviNomi.getValue(it.id), testo.getValue(it.id).value) }
            Notification.show("Salvato. Le tabelle interessate vanno ricaricate da zero.")
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