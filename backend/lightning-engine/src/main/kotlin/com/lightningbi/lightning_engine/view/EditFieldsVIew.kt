package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.AreaDimensione
import com.lightningbi.lightning_engine.model.AreaMetrica
import com.lightningbi.lightning_engine.model.TipoAggregazione
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.service.AuthService
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
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.Tabs
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.BeforeEvent
import com.vaadin.flow.router.HasUrlParameter
import com.vaadin.flow.router.Route
import java.util.UUID

/**
 * Pagina "Modifica Schema" del Dataset: due sezioni, Dimensioni e
 * Metriche, selezionabili con Tabs, invece dei due EditDimensionsDialog/
 * EditMetricsDialog separati. Stessa logica di prima (aggiunta,
 * modifica, eliminazione), solo spostata da dialog a pagina a sé
 * stante, coerente con le altre viste corpose dell'app (Analisi,
 * Grafici) e più adatta a un elenco potenzialmente lungo di campi.
 *
 * Segue lo stesso pattern di route di AssociativeExplorerView/
 * ChartsView: @Route + HasUrlParameter<String>, areaId passato come
 * segmento URL (es. /edit-fields/<uuid>).
 */
@Route("edit-fields")
class EditFieldsView(
    private val registryService: RegistryService,
    private val areaSourceRepository: AreaSourceRepository,
    private val cryptoService: CryptoService,
    private val metadataService: MetadataService,
    private val authService: AuthService
) : VerticalLayout(), HasUrlParameter<String> {

    private var areaId: UUID? = null

    private lateinit var shell: LbiAppShell

    // ================= Dimensioni =================
    private data class DimRow(val areaDim: AreaDimensione, val nomeDimensione: String)
    private val dimGrid = Grid<DimRow>()
    private val dimSection = VerticalLayout().apply { isPadding = false }

    // ================= Metriche =================
    private val metricGrid = Grid<AreaMetrica>()
    private var metriche: List<AreaMetrica> = emptyList()
    private val metricSection = VerticalLayout().apply { isPadding = false; isVisible = false }

    init {
        setSizeFull()
        isPadding = false
        isSpacing = false

        val tabs = Tabs(Tab("Dimensioni"), Tab("Metriche")).apply {
            addSelectedChangeListener { event ->
                dimSection.isVisible = event.selectedTab.label == "Dimensioni"
                metricSection.isVisible = event.selectedTab.label == "Metriche"
            }
        }

        buildDimSection()
        buildMetricSection()

        val centerArea = VerticalLayout(
            Span("Modifica Schema").apply { className = "lbi-section-title" },
            tabs,
            dimSection,
            metricSection
        ).apply {
            isPadding = true
            setSizeFull()
        }

        shell = LbiAppShell(emptyList(), centerArea, authService)
        add(shell)
        setFlexGrow(1.0, shell)
    }

    override fun setParameter(event: BeforeEvent, parameter: String?) {
        val id = parameter?.let {
            try { UUID.fromString(it) } catch (e: IllegalArgumentException) { null }
        }
        areaId = id
        if (id != null) {
            reloadDim(id)
            reloadMetric(id)
        }
    }

    // ================= Sezione Dimensioni =================

    private fun buildDimSection() {
        dimGrid.apply {
            setWidthFull()
            height = "360px"

            addColumn { it.nomeDimensione }.setHeader("Nome").setAutoWidth(true)
            addColumn { it.areaDim.colonnaFisica }.setHeader("Colonna").setAutoWidth(true)
            addColumn { if (it.areaDim.obbligatoria) "Sì" else "No" }.setHeader("Obbligatoria").setAutoWidth(true)
        }

        val addButton = Button("+ Nuova dimensione") { openAddDimensionDialog() }
            .apply { addThemeVariants(ButtonVariant.LUMO_TERTIARY) }

        dimSection.add(
            Span("Le dimensioni collegate a questo Dataset. Da qui si possono solo aggiungere: rimuoverne una già in uso va fatto con cautela, non ancora disponibile in questa schermata.").apply {
                className = "lbi-wizard-label"
            },
            dimGrid,
            addButton
        )
    }

    private fun reloadDim(id: UUID) {
        val righe = registryService.getDimensioniArea(id).mapNotNull { ad ->
            registryService.getDimensione(ad.dimensioneId)?.let { dim -> DimRow(ad, dim.nome) }
        }
        dimGrid.setItems(righe)
    }

    /**
     * Legge via JDBC le colonne reali della view sorgente dell'area,
     * esclude quelle già collegate (come dimensione o come metrica), e
     * propone solo il resto - niente testo libero, niente colonne già
     * usate con un ruolo diverso o uguale.
     */
    private fun openAddDimensionDialog() {
        val currentAreaId = areaId ?: return
        val source = areaSourceRepository.findByArea(currentAreaId).firstOrNull()
        if (source == null) {
            Notification.show("Nessuna sorgente collegata a questo Dataset: impossibile leggere le colonne disponibili")
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
                        registryService.getDimensioniArea(currentAreaId).map { it.colonnaFisica } +
                                registryService.getMetricheArea(currentAreaId).mapNotNull { it.colonnaFisica }
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
                    areaId = currentAreaId,
                    dimensioneId = dim.id,
                    colonnaFisica = colonna,
                    obbligatoria = obbligatoriaCheckbox.value,
                    cardinalita = null
                )
                reloadDim(currentAreaId)
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

    // ================= Sezione Metriche =================

    private fun buildMetricSection() {
        metricGrid.apply {
            setWidthFull()
            height = "360px"

            addColumn { it.nome }.setHeader("Nome").setAutoWidth(true)
            addColumn { it.colonnaFisica ?: "(nessuna, COUNT su righe)" }.setHeader("Colonna").setAutoWidth(true)
            addColumn { aggregationLabel(it.tipoAggregazione) }.setHeader("Aggregazione").setAutoWidth(true)

            addComponentColumn { metrica ->
                HorizontalLayout(
                    Button("✎") { openEditMetricDialog(metrica) }.apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL)
                    },
                    Button("🗑") { confirmDeleteMetric(metrica) }.apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL)
                    }
                ).apply { isPadding = false }
            }.setHeader("").setAutoWidth(true).setFlexGrow(0)
        }

        val addButton = Button("+ Nuova metrica su colonna esistente") { openAddMetricDialog() }
            .apply { addThemeVariants(ButtonVariant.LUMO_TERTIARY) }

        metricSection.add(
            Span("Le metriche di questo Dataset. Nome e aggregazione sono modificabili; la colonna sorgente no.").apply {
                className = "lbi-wizard-label"
            },
            metricGrid,
            addButton
        )
    }

    private fun reloadMetric(id: UUID) {
        metriche = registryService.getMetricheArea(id)
        metricGrid.setItems(metriche)
    }

    private fun openEditMetricDialog(metrica: AreaMetrica) {
        val currentAreaId = areaId ?: return
        val dialog = Dialog().apply {
            headerTitle = "Modifica \"${metrica.nome}\""
            width = "440px"
        }

        val nameField = TextField("Nome").apply {
            value = metrica.nome
            setWidthFull()
        }

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
                reloadMetric(currentAreaId)
                dialog.close()
            } catch (e: Exception) {
                Notification.show("Errore: ${e.message}", 5000, Notification.Position.MIDDLE)
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        dialog.footer.add(cancelButton, saveButton)
        dialog.open()
    }

    private fun confirmDeleteMetric(metrica: AreaMetrica) {
        val currentAreaId = areaId ?: return
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
                reloadMetric(currentAreaId)
            } catch (e: Exception) {
                Notification.show("Errore: ${e.message}", 5000, Notification.Position.MIDDLE)
            }
            dialog.close()
        }
        dialog.footer.add(cancelButton, confirmButton)
        dialog.open()
    }

    private fun openAddMetricDialog() {
        val currentAreaId = areaId ?: return
        val colonneDisponibili = registryService.getColonneMetricheDisponibili(currentAreaId)
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
                registryService.addMetrica(currentAreaId, nome, colonna, aggregazione)
                reloadMetric(currentAreaId)
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
        if (!target.value.isNullOrBlank()) return
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