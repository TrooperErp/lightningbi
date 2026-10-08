package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.service.AccessoDatasetService
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.CheckboxGroup
import com.vaadin.flow.component.checkbox.CheckboxGroupVariant
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import java.util.UUID

/**
 * Chi può aprire un dataset. L'admin vede sempre tutto e non compare qui.
 * Un utente vede il dataset se glielo dà il suo ruolo oppure lui direttamente.
 */
class AccessiDatasetDialog(
    private val accessoDatasetService: AccessoDatasetService,
    private val areaId: UUID,
    nomeDataset: String,
    private val alSalvataggio: () -> Unit = {}
) : Dialog() {

    private val ruoli = CheckboxGroup<Pair<UUID, String>>().apply {
        label = "Ruoli"
        addThemeVariants(CheckboxGroupVariant.LUMO_VERTICAL)
        setItems(accessoDatasetService.ruoliDisponibili())
        setItemLabelGenerator { it.second }
        val scelti = accessoDatasetService.ruoliDi(areaId)
        value = accessoDatasetService.ruoliDisponibili().filter { it.first in scelti }.toSet()
    }

    private val utenti = CheckboxGroup<Pair<UUID, String>>().apply {
        label = "Utenti (in aggiunta al ruolo)"
        addThemeVariants(CheckboxGroupVariant.LUMO_VERTICAL)
        setItems(accessoDatasetService.utentiDisponibili())
        setItemLabelGenerator { it.second }
        val scelti = accessoDatasetService.utentiDi(areaId)
        value = accessoDatasetService.utentiDisponibili().filter { it.first in scelti }.toSet()
    }

    init {
        headerTitle = "Accessi a \"$nomeDataset\""
        width = "520px"

        val contenuto = VerticalLayout(
            Span("L'amministratore vede sempre tutti i dataset. Gli altri vedono solo quelli assegnati al loro ruolo o a loro."),
            ruoli,
            utenti
        ).apply { isPadding = false }

        add(Div(contenuto).apply { style.set("max-height", "60vh"); style.set("overflow", "auto") })

        footer.add(
            Button("Chiudi") { close() },
            Button("Salva") { salva() }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        )
    }

    private fun salva() {
        try {
            accessoDatasetService.imposta(
                areaId,
                ruoli.value.map { it.first }.toSet(),
                utenti.value.map { it.first }.toSet()
            )
            Notification.show("Accessi salvati")
            alSalvataggio()
            close()
        } catch (e: SecurityException) {
            Notification.show(e.message ?: "Operazione non consentita")
        } catch (e: Exception) {
            Notification.show("Errore nel salvataggio: ${e.message}", 8000, Notification.Position.MIDDLE)
        }
    }
}