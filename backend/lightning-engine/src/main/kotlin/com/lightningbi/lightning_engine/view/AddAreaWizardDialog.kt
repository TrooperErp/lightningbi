package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.Dimensione
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.service.RegistryService
import com.lightningbi.lightning_engine.service.SymbolTableService
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.textfield.TextField
import java.util.UUID

class AddAreaWizardDialog(
    private val registryService: RegistryService,
    private val registryRepository: RegistryRepository,
    private val symbolTableService: SymbolTableService,
    private val onAreaCreated: () -> Unit
) : Dialog() {

    private data class PendingFiltro(
        val nome: String,
        val isNuovo: Boolean,
        val existingDimensioneId: UUID?,
        val colonnaFisica: String,
        val obbligatoria: Boolean
    )

    private data class PendingSomma(
        val nome: String,
        val colonnaFisica: String,
        val tipoAggregazione: String
    )

    private val filtriAggiunti = mutableListOf<PendingFiltro>()
    private val sommeAggiunte = mutableListOf<PendingSomma>()

    private lateinit var nomeAreaField: TextField
    private val filtriListDiv = Div()
    private val sommeListDiv = Div()

    private val content = VerticalLayout().apply { className = "lbi-wizard-content" }

    init {
        className = "lbi-wizard-dialog"
        width = "600px"
        headerTitle = "Nuova Analisi"
        isCloseOnEsc = false
        isCloseOnOutsideClick = false

        add(content)
        showStep1()
    }

    private fun slugify(nome: String): String =
        nome.lowercase()
            .trim()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), "_")

    private fun normalizeColumn(value: String?): String =
        value
            ?.lowercase()
            ?.trim()
            ?.replace(Regex("[^a-z0-9\\s_]"), "")
            ?.replace(Regex("\\s+"), "_")
            ?: ""

    // ================= STEP 1: nome analisi =================
    private fun showStep1() {
        content.removeAll()
        content.add(Span("Come vuoi chiamare questa analisi? (es. Vendite, Produzione)").apply {
            className = "lbi-wizard-label"
        })

        nomeAreaField = TextField("Nome analisi").apply {
            placeholder = "es. Vendite 2026"
            className = "lbi-wizard-field"
            setWidthFull()
        }

        content.add(nomeAreaField)

        val nextButton = Button("Avanti →") {
            if (nomeAreaField.value.isNullOrBlank()) {
                Notification.show("Indica il nome dell'analisi")
                return@Button
            }
            showStep2()
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        val cancelButton = Button("Annulla") { close() }

        content.add(HorizontalLayout(cancelButton, nextButton).apply { className = "lbi-wizard-actions" })
    }

    // ================= STEP 2: filtri (dimensioni) =================
    private fun showStep2() {
        content.removeAll()
        content.add(Span("Aggiungi i FILTRI per questa analisi (es. Cliente, Prodotto, Anno)").apply {
            className = "lbi-wizard-label"
        })

        renderFiltriList()
        content.add(filtriListDiv)

        val addFiltroButton = Button("+ Aggiungi filtro") { openAddFiltroForm() }
        content.add(addFiltroButton)

        val backButton = Button("← Indietro") { showStep1() }
        val nextButton = Button("Avanti →") {
            if (filtriAggiunti.isEmpty()) {
                Notification.show("Aggiungi almeno un filtro")
                return@Button
            }
            showStep3()
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        content.add(HorizontalLayout(backButton, nextButton).apply { className = "lbi-wizard-actions" })
    }

    private fun renderFiltriList() {
        filtriListDiv.removeAll()
        filtriListDiv.className = "lbi-wizard-list"
        filtriAggiunti.forEach { f ->
            filtriListDiv.add(Div(Span("✓ ${f.nome} (colonna: ${f.colonnaFisica})")).apply {
                className = "lbi-wizard-item"
            })
        }
    }

    private fun openAddFiltroForm() {
        val subDialog = Dialog().apply { className = "lbi-wizard-dialog" }
        subDialog.headerTitle = "Nuovo filtro"

        val subtitle = Span("Un valore per cui vuoi poter filtrare, es. Cliente o Anno").apply {
            className = "lbi-wizard-label"
        }

        val modeGroup = RadioButtonGroup<String>().apply {
            label = "Tipo"
            setItems("Nuovo filtro", "Riusa filtro esistente")
            value = "Nuovo filtro"
        }

        val nomeField = TextField("Nome filtro").apply {
            placeholder = "es. Cliente, Anno"
            setWidthFull()
        }

        val existingCombo = ComboBox<Dimensione>("Filtro esistente").apply {
            setItems(registryRepository.findAllDimensioni())
            setItemLabelGenerator { it.nome }
            isVisible = false
            setWidthFull()
        }

        val colonnaField = TextField("Colonna nella tabella dati").apply {
            placeholder = "es. cliente_id"
            helperText = "Verrà convertito automaticamente in minuscolo con underscore"
            setWidthFull()
            addValueChangeListener { event ->
                val normalized = normalizeColumn(event.value)
                if (normalized != event.value) {
                    value = normalized
                }
            }
        }

        val obbligatorioCheckbox = Checkbox("Filtro obbligatorio (righe senza valore vengono scartate)")

        modeGroup.addValueChangeListener {
            val isNuovo = it.value == "Nuovo filtro"
            nomeField.isVisible = isNuovo
            existingCombo.isVisible = !isNuovo
        }

        val form = VerticalLayout(modeGroup, nomeField, existingCombo, colonnaField, obbligatorioCheckbox)
            .apply { className = "lbi-wizard-content" }

        val confirmButton = Button("Aggiungi") {
            val isNuovo = modeGroup.value == "Nuovo filtro"
            if (colonnaField.value.isNullOrBlank()) {
                Notification.show("Indica il nome della colonna")
                return@Button
            }
            if (isNuovo) {
                if (nomeField.value.isNullOrBlank()) {
                    Notification.show("Indica il nome del filtro")
                    return@Button
                }
                filtriAggiunti.add(
                    PendingFiltro(nomeField.value, true, null, colonnaField.value, obbligatorioCheckbox.value)
                )
            } else {
                val selected = existingCombo.value
                if (selected == null) {
                    Notification.show("Seleziona un filtro esistente")
                    return@Button
                }
                filtriAggiunti.add(
                    PendingFiltro(selected.nome, false, selected.id, colonnaField.value, obbligatorioCheckbox.value)
                )
            }
            renderFiltriList()
            subDialog.close()
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        val cancelButton = Button("Annulla") { subDialog.close() }

        subDialog.add(VerticalLayout(subtitle, form, HorizontalLayout(cancelButton, confirmButton).apply { className = "lbi-wizard-actions" }))
        subDialog.open()
    }

    // ================= STEP 3: somme (metriche) =================
    private fun showStep3() {
        content.removeAll()
        content.add(Span("Aggiungi le SOMME per questa analisi (es. Fatturato, Quantità)").apply {
            className = "lbi-wizard-label"
        })

        renderSommeList()
        content.add(sommeListDiv)

        val addSommaButton = Button("+ Aggiungi somma") { openAddSommaForm() }
        content.add(addSommaButton)

        val backButton = Button("← Indietro") { showStep2() }
        val nextButton = Button("Avanti →") {
            if (sommeAggiunte.isEmpty()) {
                Notification.show("Aggiungi almeno una somma")
                return@Button
            }
            showStep4()
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        content.add(HorizontalLayout(backButton, nextButton).apply { className = "lbi-wizard-actions" })
    }

    private fun renderSommeList() {
        sommeListDiv.removeAll()
        sommeListDiv.className = "lbi-wizard-list"
        sommeAggiunte.forEach { s ->
            sommeListDiv.add(Div(Span("✓ ${s.nome} (colonna: ${s.colonnaFisica})")).apply {
                className = "lbi-wizard-item"
            })
        }
    }

    private fun openAddSommaForm() {
        val subDialog = Dialog().apply { className = "lbi-wizard-dialog" }
        subDialog.headerTitle = "Nuova somma"

        val subtitle = Span("Un numero da sommare, es. Fatturato o Quantità").apply {
            className = "lbi-wizard-label"
        }

        val nomeField = TextField("Nome somma").apply {
            placeholder = "es. Fatturato"
            setWidthFull()
        }
        val colonnaField = TextField("Colonna nella tabella dati").apply {
            placeholder = "es. importo"
            helperText = "Verrà convertito automaticamente in minuscolo con underscore"
            setWidthFull()
            addValueChangeListener { event ->
                val normalized = normalizeColumn(event.value)
                if (normalized != event.value) {
                    value = normalized
                }
            }
        }

        val confirmButton = Button("Aggiungi") {
            if (nomeField.value.isNullOrBlank() || colonnaField.value.isNullOrBlank()) {
                Notification.show("Compila entrambi i campi")
                return@Button
            }
            sommeAggiunte.add(PendingSomma(nomeField.value, colonnaField.value, "SUM"))
            renderSommeList()
            subDialog.close()
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        val cancelButton = Button("Annulla") { subDialog.close() }

        subDialog.add(VerticalLayout(subtitle, nomeField, colonnaField, HorizontalLayout(cancelButton, confirmButton).apply { className = "lbi-wizard-actions" }))
        subDialog.open()
    }

    // ================= STEP 4: riepilogo =================
    private fun showStep4() {
        content.removeAll()
        content.add(Span("Riepilogo").apply { className = "lbi-wizard-label" })
        content.add(Span("Analisi: ${nomeAreaField.value}"))
        content.add(Span("Filtri: ${filtriAggiunti.joinToString(", ") { it.nome }}"))
        content.add(Span("Somme: ${sommeAggiunte.joinToString(", ") { it.nome }}"))

        val backButton = Button("← Indietro") { showStep3() }
        val confirmButton = Button("Crea Analisi") { createArea() }
            .apply { addThemeVariants(ButtonVariant.LUMO_SUCCESS) }

        content.add(HorizontalLayout(backButton, confirmButton).apply { className = "lbi-wizard-actions" })
    }

    private fun createArea() {
        try {
            val tabellaFisica = "ch_lbi_" + slugify(nomeAreaField.value)

            symbolTableService.createAreaTable(
                tabellaFisica,
                filtriAggiunti.map { it.colonnaFisica },
                sommeAggiunte.map { it.colonnaFisica }
            )

            val area = registryService.createArea(nomeAreaField.value, tabellaFisica)

            filtriAggiunti.forEach { f ->
                val dimensioneId = if (f.isNuovo) {
                    registryService.createDimensione(f.nome, "string", true, null, null).id
                } else {
                    f.existingDimensioneId!!
                }
                registryService.linkDimensioneToArea(area.id, dimensioneId, f.colonnaFisica, f.obbligatoria, null)
            }

            sommeAggiunte.forEach { s ->
                registryService.addMetrica(area.id, s.nome, s.colonnaFisica, s.tipoAggregazione)
            }

            Notification.show("Analisi \"${nomeAreaField.value}\" creata con successo!")
            onAreaCreated()
            close()
        } catch (e: Exception) {
            Notification.show("Errore: ${e.message}", 5000, Notification.Position.MIDDLE)
        }
    }
}