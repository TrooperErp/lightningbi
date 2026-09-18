package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.AreaDimensione
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.service.CryptoService
import com.lightningbi.lightning_engine.service.MetadataService
import com.lightningbi.lightning_engine.service.RegistryService
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import java.util.UUID

/**
 * Collega nuove dimensioni a un'analisi esistente, oltre a quelle scelte
 * al momento della creazione (NewAnalysisWizardDialog).
 *
 * Non tocca metriche (EditMetricsDialog se ne occupa già) e non permette
 * di scollegare una dimensione da qui: rimuovere una dimensione già in
 * uso in filtri o pivot salvati romperebbe stato esistente in modo poco
 * controllato - va gestito a parte se servirà, non improvvisato qui.
 *
 * Le colonne proposte arrivano da introspezione JDBC reale sulla view
 * sorgente dell'area (MetadataService.listColumns, db-agnostic), non da
 * un campo di testo libero: evita errori di battitura nel nome colonna,
 * che altrimenti si scoprirebbero solo al prossimo ETL fallito.
 */
class EditDimensionsDialog(
    private val areaId: UUID,
    private val registryService: RegistryService,
    private val areaSourceRepository: AreaSourceRepository,
    private val cryptoService: CryptoService,
    private val metadataService: MetadataService,
    private val onChanged: () -> Unit
) : Dialog() {

    private data class Row(val areaDim: AreaDimensione, val nomeDimensione: String)

    private val grid = Grid<Row>()

    init {
        className = "lbi-wizard-dialog"
        headerTitle = "Dimensioni dell'analisi"
        width = "760px"

        add(buildContent())
        footer.add(
            Button("+ Nuova dimensione") { openAddDialog() }.apply { addThemeVariants(ButtonVariant.LUMO_TERTIARY) },
            Button("Chiudi") { close() }
        )

        reload()
    }

    private fun buildContent(): VerticalLayout {
        grid.apply {
            setWidthFull()
            height = "320px"

            addColumn { it.nomeDimensione }.setHeader("Nome").setAutoWidth(true)
            addColumn { it.areaDim.colonnaFisica }.setHeader("Colonna").setAutoWidth(true)
            addColumn { if (it.areaDim.obbligatoria) "Sì" else "No" }.setHeader("Obbligatoria").setAutoWidth(true)
        }



        return VerticalLayout(
            Span("Le dimensioni collegate a questa analisi. Da qui si possono solo aggiungere: rimuoverne una già in uso va fatto con cautela, non ancora disponibile in questa schermata.").apply {
                className = "lbi-wizard-label"
            },
            grid,

        ).apply { isPadding = false }
    }

    private fun reload() {
        val righe = registryService.getDimensioniArea(areaId).mapNotNull { ad ->
            registryService.getDimensione(ad.dimensioneId)?.let { dim -> Row(ad, dim.nome) }
        }
        grid.setItems(righe)
    }

    // ================= Aggiunta nuova dimensione =================

    /**
     * Legge via JDBC le colonne reali della view sorgente dell'area,
     * esclude quelle già collegate (come dimensione o come metrica), e
     * propone solo il resto - niente testo libero, niente colonne già
     * usate con un ruolo diverso o uguale.
     */
    private fun openAddDialog() {
        val source = areaSourceRepository.findByArea(areaId).firstOrNull()
        if (source == null) {
            Notification.show("Nessuna sorgente collegata a questa analisi: impossibile leggere le colonne disponibili")
            return
        }

        val colonneDisponibili = try {
            val password = cryptoService.decrypt(source.config.encryptedPassword)
            metadataService.connect(
                source.config.jdbcUrl, source.config.username, password, source.config.driverClassName
            ).use { conn ->
                val tutteLeColonne = metadataService.listColumns(conn, source.config.schema, source.config.viewName)
                    .map { it.name }

                val giaCollegate = (
                        registryService.getDimensioniArea(areaId).map { it.colonnaFisica } +
                                registryService.getMetricheArea(areaId).mapNotNull { it.colonnaFisica }
                        ).map { it.lowercase() }.toSet()

                tutteLeColonne.filterNot { it.lowercase() in giaCollegate }
            }
        } catch (e: Exception) {
            Notification.show("Errore nel leggere le colonne dalla sorgente: ${e.message}", 6000, Notification.Position.MIDDLE)
            return
        }

        if (colonneDisponibili.isEmpty()) {
            Notification.show("Nessuna colonna nuova disponibile: tutte le colonne della view sono già collegate")
            return
        }

        val dialog = Dialog().apply {
            headerTitle = "Nuova dimensione"
            width = "440px"
        }

        val colonnaCombo = ComboBox<String>("Colonna nella view").apply {
            setItems(colonneDisponibili)
            setWidthFull()
        }
        val nomeField = TextField("Nome dimensione").apply {
            setWidthFull()
            helperText = "Come apparirà nel pivot e nei filtri"
        }
        val obbligatoriaCheckbox = Checkbox("Obbligatoria (nessun valore nullo atteso)").apply {
            value = false
        }

        colonnaCombo.addValueChangeListener { event ->
            if (nomeField.value.isNullOrBlank()) {
                nomeField.value = event.value?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: ""
            }
        }

        dialog.add(VerticalLayout(colonnaCombo, nomeField, obbligatoriaCheckbox).apply { isPadding = false })

        val cancelButton = Button("Annulla") { dialog.close() }
        val createButton = Button("Crea e collega") {
            val colonna = colonnaCombo.value
            val nome = nomeField.value?.trim()
            if (colonna == null) {
                Notification.show("Seleziona una colonna")
                return@Button
            }
            if (nome.isNullOrBlank()) {
                Notification.show("Indica un nome per la dimensione")
                return@Button
            }
            try {
                val dim = registryService.findOrCreateDimensione(nome)
                registryService.linkDimensioneToArea(
                    areaId = areaId,
                    dimensioneId = dim.id,
                    colonnaFisica = colonna,
                    obbligatoria = obbligatoriaCheckbox.value,
                    cardinalita = null
                )
                reload()
                onChanged()
                dialog.close()
                Notification.show(
                    "Dimensione collegata. Rilancia la sincronizzazione per popolarla con i dati esistenti.",
                    6000, Notification.Position.BOTTOM_END
                )
            } catch (e: Exception) {
                Notification.show("Errore: ${e.message}", 5000, Notification.Position.MIDDLE)
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        dialog.footer.add(cancelButton, createButton)
        dialog.open()
    }
}