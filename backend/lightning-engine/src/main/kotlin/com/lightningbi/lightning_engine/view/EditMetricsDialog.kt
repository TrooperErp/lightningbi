package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.AreaMetrica
import com.lightningbi.lightning_engine.model.TipoAggregazione
import com.lightningbi.lightning_engine.service.RegistryService
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextField
import java.util.UUID

/**
 * Modifica delle metriche di un'analisi esistente.
 *
 * Si può cambiare nome e tipo di aggregazione, o eliminare una metrica.
 * Non si può cambiare la colonna fisica: farlo significherebbe cambiare
 * cosa l'analisi misura, non come lo mostra - a quel punto è una metrica
 * diversa, va creata come tale.
 *
 * Nuove metriche si possono aggiungere solo su colonne che sono già
 * metriche nell'area (un'altra aggregazione sulla stessa colonna, es.
 * media accanto a una somma già esistente), non su colonne oggi usate
 * solo come dimensione: promuovere una dimensione a metrica cambia il
 * ruolo della colonna nell'analisi, non è una semplice aggiunta.
 */
class EditMetricsDialog(
    private val areaId: UUID,
    private val registryService: RegistryService,
    private val onChanged: () -> Unit
) : Dialog() {

    private val grid = Grid<AreaMetrica>()
    private var metriche: List<AreaMetrica> = emptyList()

    init {
        className = "lbi-wizard-dialog"
        headerTitle = "Metriche dell'analisi"
        width = "760px"

        add(buildContent())
        footer.add(Button("Chiudi") { close() })

        reload()
    }

    private fun buildContent(): VerticalLayout {
        grid.apply {
            setWidthFull()
            height = "320px"

            addColumn { it.nome }.setHeader("Nome").setAutoWidth(true)
            addColumn { it.colonnaFisica ?: "(nessuna, COUNT su righe)" }.setHeader("Colonna").setAutoWidth(true)
            addColumn { aggregationLabel(it.tipoAggregazione) }.setHeader("Aggregazione").setAutoWidth(true)

            addComponentColumn { metrica ->
                HorizontalLayout(
                    Button("✎") { openEditRow(metrica) }.apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL)
                    },
                    Button("🗑") { confirmDelete(metrica) }.apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL)
                    }
                ).apply { isPadding = false }
            }.setHeader("").setAutoWidth(true).setFlexGrow(0)
        }

        val addButton = Button("+ Nuova metrica su colonna esistente") { openAddDialog() }
            .apply { addThemeVariants(ButtonVariant.LUMO_TERTIARY) }

        return VerticalLayout(
            Span("Le metriche già presenti nell'analisi. Nome e aggregazione sono modificabili; la colonna sorgente no.").apply {
                className = "lbi-wizard-label"
            },
            grid,
            addButton
        ).apply { isPadding = false }
    }

    private fun reload() {
        metriche = registryService.getMetricheArea(areaId)
        grid.setItems(metriche)
    }

    // ================= Modifica riga =================

    private fun openEditRow(metrica: AreaMetrica) {
        val dialog = Dialog().apply {
            headerTitle = "Modifica \"${metrica.nome}\""
            width = "440px"
        }

        val nameField = TextField("Nome").apply {
            value = metrica.nome
            setWidthFull()
        }

        // COUNT_DISTINCT/AVG/MIN/MAX/SUM tutti disponibili se la metrica ha
        // colonna; se colonnaFisica è null (COUNT su righe) resta bloccata
        // su COUNT, perché le altre aggregazioni richiedono una colonna che
        // qui non c'è e non si può aggiungere in questa modifica.
        val aggregazioneCombo = ComboBox<TipoAggregazione>("Aggregazione").apply {
            setItems(
                if (metrica.colonnaFisica != null) TipoAggregazione.entries
                else listOf(TipoAggregazione.COUNT)
            )
            setItemLabelGenerator { aggregationLabel(it) }
            value = metrica.tipoAggregazione
            isEnabled = metrica.colonnaFisica != null
            setWidthFull()
        }

        dialog.add(VerticalLayout(nameField, aggregazioneCombo).apply { isPadding = false })

        val cancelButton = Button("Annulla") { dialog.close() }
        val saveButton = Button("Salva") {
            val nome = nameField.value?.trim()
            if (nome.isNullOrBlank()) {
                Notification.show("Il nome non può essere vuoto")
                return@Button
            }
            val aggregazione = aggregazioneCombo.value ?: metrica.tipoAggregazione
            try {
                registryService.updateMetrica(metrica.id, nome, aggregazione)
                reload()
                onChanged()
                dialog.close()
            } catch (e: Exception) {
                Notification.show("Errore: ${e.message}", 5000, Notification.Position.MIDDLE)
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        dialog.footer.add(cancelButton, saveButton)
        dialog.open()
    }

    private fun confirmDelete(metrica: AreaMetrica) {
        val dialog = Dialog().apply {
            headerTitle = "Eliminare \"${metrica.nome}\"?"
            width = "440px"
        }
        dialog.add(
            Span("La metrica sparirà dalla griglia e dai grafici che la usano. L'operazione non è reversibile da qui.")
        )
        val cancelButton = Button("Annulla") { dialog.close() }
        val confirmButton = Button("Elimina") {
            try {
                registryService.deleteMetrica(metrica.id)
                reload()
                onChanged()
            } catch (e: Exception) {
                Notification.show("Errore: ${e.message}", 5000, Notification.Position.MIDDLE)
            }
            dialog.close()
        }
        dialog.footer.add(cancelButton, confirmButton)
        dialog.open()
    }
    // ================= Aggiunta nuova metrica =================

    /**
     * Aggiunge un'aggregazione su una colonna GIA' metrica nell'area.
     * L'elenco proposto è quello di getColonneMetricheDisponibili, non
     * tutte le colonne fisiche della tabella: promuovere una dimensione a
     * metrica non è un'aggiunta, è un cambio di ruolo che qui non si fa.
     */
    private fun openAddDialog() {
        val colonneDisponibili = registryService.getColonneMetricheDisponibili(areaId)
        if (colonneDisponibili.isEmpty()) {
            Notification.show("Nessuna colonna metrica disponibile su cui aggiungere un'aggregazione")
            return
        }

        val dialog = Dialog().apply {
            headerTitle = "Nuova metrica"
            width = "440px"
        }

        val colonnaCombo = ComboBox<String>("Colonna").apply {
            setItems(colonneDisponibili)
            setWidthFull()
        }
        val aggregazioneCombo = ComboBox<TipoAggregazione>("Aggregazione").apply {
            setItems(TipoAggregazione.entries.filter { it != TipoAggregazione.COUNT })
            setItemLabelGenerator { aggregationLabel(it) }
            setWidthFull()
        }
        val nameField = TextField("Nome").apply { setWidthFull() }

        colonnaCombo.addValueChangeListener { updateSuggestedName(it.value, aggregazioneCombo.value, nameField) }
        aggregazioneCombo.addValueChangeListener { updateSuggestedName(colonnaCombo.value, it.value, nameField) }

        dialog.add(VerticalLayout(colonnaCombo, aggregazioneCombo, nameField).apply { isPadding = false })

        val cancelButton = Button("Annulla") { dialog.close() }
        val createButton = Button("Crea") {
            val colonna = colonnaCombo.value
            val aggregazione = aggregazioneCombo.value
            val nome = nameField.value?.trim()
            if (colonna == null || aggregazione == null) {
                Notification.show("Seleziona colonna e aggregazione")
                return@Button
            }
            if (nome.isNullOrBlank()) {
                Notification.show("Indica un nome per la metrica")
                return@Button
            }
            try {
                registryService.addMetrica(areaId, nome, colonna, aggregazione)
                reload()
                onChanged()
                dialog.close()
            } catch (e: Exception) {
                Notification.show("Errore: ${e.message}", 5000, Notification.Position.MIDDLE)
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        dialog.footer.add(cancelButton, createButton)
        dialog.open()
    }

    private fun updateSuggestedName(colonna: String?, tipo: TipoAggregazione?, target: TextField) {
        if (colonna == null || tipo == null) return
        if (!target.value.isNullOrBlank()) return // non sovrascrivere se l'utente ha già digitato
        val leggibile = colonna.replace('_', ' ').replaceFirstChar { it.uppercase() }
        val prefisso = when (tipo) {
            TipoAggregazione.SUM -> "Totale"
            TipoAggregazione.AVG -> "Media"
            TipoAggregazione.COUNT -> "Conteggio"
            TipoAggregazione.COUNT_DISTINCT -> "Conteggio distinto"
            TipoAggregazione.MIN -> "Minimo"
            TipoAggregazione.MAX -> "Massimo"
        }
        target.value = "$prefisso $leggibile"
    }

    private fun aggregationLabel(tipo: TipoAggregazione): String = when (tipo) {
        TipoAggregazione.SUM -> "Somma"
        TipoAggregazione.AVG -> "Media"
        TipoAggregazione.COUNT -> "Conteggio"
        TipoAggregazione.COUNT_DISTINCT -> "Conteggio distinto"
        TipoAggregazione.MIN -> "Minimo"
        TipoAggregazione.MAX -> "Massimo"
    }
}