package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.AreaChart
import com.lightningbi.lightning_engine.model.ChartResult
import com.lightningbi.lightning_engine.model.ChartType
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.service.ChartService
import com.lightningbi.lightning_engine.service.SourceVerificationService
import com.lightningbi.lightning_engine.repository.UserPivotStateRepository
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.BeforeEvent
import com.vaadin.flow.router.HasUrlParameter
import com.vaadin.flow.router.Route
import java.util.UUID
import com.lightningbi.lightning_engine.service.AuthService
import com.lightningbi.lightning_engine.model.AggregateOrder


/**
 * Pagina di gestione grafici per un'Analisi. Resta sempre agganciata a
 * un'area precisa (ricevuta dall'URL): il gruppo "Analisi" nella sidebar
 * qui mostra SOLO quella singola area, non l'elenco completo - non
 * esiste modo di cambiare analisi restando su questa pagina.
 *
 * Si apre direttamente sulle nove card di scelta tipo, stile Tableau:
 * niente pulsante "nuovo grafico" separato, il click su una card apre
 * subito la creazione con quel tipo già deciso.
 *
 * Righe/Colonne/Valori del grafico si scelgono con un PivotPanel dedicato
 * INLINE nella pagina (non più in un Dialog): sotto compare una preview
 * live del grafico (EChartComponent), ricalcolata ad ogni modifica del
 * pivot o delle opzioni.
 *
 * I grafici dipendono dall'ANALISI (Area), non da nessuna PivotView:
 * TUTTI i campi (dimensioni + metriche) dell'area sono sempre
 * selezionabili nel PivotPanel del grafico, nessuno disabilitato -
 * comportamento diverso da prima del refactor multi-vista, quando i
 * campi ammessi dipendevano dall'ultimo pivot salvato dall'utente in
 * pagina Analisi. Le selezioni (verde/bianco/grigio), invece, sono le
 * stesse condivise per l'Area e si applicano comunque al calcolo/
 * preview del grafico.
 *
 * IMPORTANTE: il callback onChange di PivotPanel scatta SINCRONO già
 * dentro setFieldsWithIds/restoreState, quindi PRIMA che l'assegnazione
 * "val pivotPanel = buildChartPivotPanelWithCallback(...)" sia conclusa.
 * Per questo aggiornaPreview riceve righe/colonne/valori come PARAMETRI
 * del callback stesso, non li rilegge da una variabile pivotPanel
 * catturata nella lambda (quello causava
 * UninitializedPropertyAccessException con un lateinit var).
 */
@Route("charts")
class ChartsView(
    private val chartService: ChartService,
    private val registryRepository: RegistryRepository,
    private val areaSourceRepository: AreaSourceRepository,
    private val sourceVerificationService: SourceVerificationService,
    private val authService: AuthService,
    private val userPivotStateRepository: UserPivotStateRepository
) : VerticalLayout(), HasUrlParameter<String> {

    private var areaId: UUID? = null
    private val grid = Grid<AreaChart>()

    /** Area del form (creazione/edit) inline: vuota quando nessun grafico è in editing. */
    private val formArea = VerticalLayout().apply {
        isPadding = false
        isVisible = false
        className = "lbi-chart-form-area"
    }

    override fun setParameter(event: BeforeEvent, parameter: String) {
        areaId = try {
            UUID.fromString(parameter)
        } catch (e: IllegalArgumentException) {
            Notification.show("Analisi non valida")
            null
        }
        buildPage()
    }

    private fun buildPage() {
        removeAll()
        setSizeFull()
        isPadding = false

        val currentAreaId = areaId
        val area = currentAreaId?.let { registryRepository.findAreaById(it) }

        val content: Component = if (area == null) {
            Span("Analisi non trovata.").apply { className = "lbi-wizard-label" }
        } else {
            buildContent(area.id, area.nome)
        }

        val menuGroups = listOf(
            LbiSidebarMenu.MenuGroup(
                label = "Analisi",
                entries = if (area != null) {
                    listOf(LbiSidebarMenu.MenuEntry(area.nome) { navigateToAssociative(area.id) })
                } else {
                    emptyList()
                }
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Gestisci",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Torna all'analisi", enabled = area != null) {
                        navigateToAssociative(area?.id)
                    }
                )
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Grafici",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Gestisci grafici", enabled = false) {}
                ),
                active = true
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Report",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Stampe") { Notification.show("Funzione in arrivo") }
                )
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Amministrazione",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Gestione utenti") { Notification.show("Funzione in arrivo") }
                )
            )
        )

        val shell = LbiAppShell(menuGroups, content, authService)
        add(shell)
        setFlexGrow(1.0, shell)
    }

    private fun navigateToAssociative(targetAreaId: UUID?) {
        if (targetAreaId == null) {
            ui.ifPresent { it.navigate(AssociativeExplorerView::class.java) }
        } else {
            ui.ifPresent { it.navigate(AssociativeExplorerView::class.java, targetAreaId.toString()) }
        }
    }

    private fun buildContent(currentAreaId: UUID, areaNome: String): Component {
        val typeGallery = buildTypeGallery(currentAreaId)

        grid.apply {
            setWidthFull()
            height = "320px"
            addColumn { it.titolo }.setHeader("Titolo").setAutoWidth(true)
            addColumn { it.tipo.name }.setHeader("Tipo").setAutoWidth(true)
            addColumn { chart ->
                chartService.getMetricheDelGrafico(chart.id)
                    .mapNotNull { cm -> registryRepository.findMetricheByArea(currentAreaId).find { it.id == cm.metricaId }?.nome }
                    .joinToString(", ")
            }.setHeader("Metriche").setAutoWidth(true)

            addComponentColumn { chart ->
                HorizontalLayout(
                    Button("Modifica") { openEditForm(currentAreaId, chart) },
                    Button("Elimina") { confirmDelete(chart) }
                ).apply { isPadding = false }
            }.setHeader("")
        }
        reload(currentAreaId)

        formArea.removeAll()
        formArea.isVisible = false

        return VerticalLayout(
            Span("Grafici di \"$areaNome\"").apply { className = "lbi-section-title" },
            Span("Scegli un tipo per creare un nuovo grafico. Righe, Colonne e Valori sono proprie del grafico, scelte fra tutti i campi dell'analisi.").apply {
                className = "lbi-wizard-label"
            },
            typeGallery,
            formArea,
            Span("Grafici esistenti").apply { className = "lbi-section-title" },
            grid
        ).apply {
            className = "lbi-center"
            isPadding = true
            setSizeFull()
        }
    }

    /** Griglia delle nove card di scelta tipo, ognuna con la sua miniatura SVG. */
    private fun buildTypeGallery(currentAreaId: UUID): Component {
        val gallery = HorizontalLayout().apply {
            className = "lbi-chart-type-gallery"
            isPadding = false
        }
        ChartType.entries.forEach { tipo ->
            gallery.add(buildTypeCard(tipo, currentAreaId))
        }
        return gallery
    }

    private fun buildTypeCard(tipo: ChartType, currentAreaId: UUID): Component {
        val icon = com.vaadin.flow.component.html.Div().apply {
            element.setProperty("innerHTML", chartTypeIcon(tipo))
        }
        val label = Span(chartTypeLabel(tipo)).apply { className = "lbi-chart-type-label" }

        val card = VerticalLayout(icon, label).apply {
            className = "lbi-chart-type-card"
            isPadding = false
            width = "110px"
            height = "110px"
            addClickListener { openCreateForm(currentAreaId, tipo) }
        }
        return card
    }

    private fun chartTypeLabel(tipo: ChartType): String = when (tipo) {
        ChartType.BAR -> "Barre"
        ChartType.BAR_HORIZONTAL -> "Barre orizzontali"
        ChartType.LINE -> "Linee"
        ChartType.AREA -> "Area"
        ChartType.PIE -> "Torta"
        ChartType.DONUT -> "Ciambella"
        ChartType.SCATTER -> "Dispersione"
        ChartType.RADAR -> "Radar"
        ChartType.MAP -> "Cartina"
    }

    /**
     * Icone SVG che rappresentano visivamente ogni tipo di grafico, stile
     * Tableau: non icone generiche, ma piccole illustrazioni della forma
     * reale del grafico. Palette Tableau 10 per coerenza con il tema dei
     * grafici veri (ECharts).
     */
    private fun chartTypeIcon(tipo: ChartType): String = when (tipo) {
        ChartType.BAR -> """
            <svg viewBox="0 0 64 48" width="48" height="36">
                <rect x="6"  y="24" width="10" height="20" fill="#4E79A7" rx="1"/>
                <rect x="20" y="12" width="10" height="32" fill="#F28E2B" rx="1"/>
                <rect x="34" y="18" width="10" height="26" fill="#E15759" rx="1"/>
                <rect x="48" y="6"  width="10" height="38" fill="#76B7B2" rx="1"/>
            </svg>
        """.trimIndent()

        ChartType.BAR_HORIZONTAL -> """
            <svg viewBox="0 0 64 48" width="48" height="36">
                <rect x="4" y="6"  width="38" height="8" fill="#4E79A7" rx="1"/>
                <rect x="4" y="18" width="52" height="8" fill="#F28E2B" rx="1"/>
                <rect x="4" y="30" width="28" height="8" fill="#E15759" rx="1"/>
            </svg>
        """.trimIndent()

        ChartType.LINE -> """
            <svg viewBox="0 0 64 48" width="48" height="36">
                <polyline points="6,36 20,20 34,28 48,10 58,16"
                          fill="none" stroke="#4E79A7" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"/>
                <circle cx="6" cy="36" r="2.5" fill="#4E79A7"/>
                <circle cx="20" cy="20" r="2.5" fill="#4E79A7"/>
                <circle cx="34" cy="28" r="2.5" fill="#4E79A7"/>
                <circle cx="48" cy="10" r="2.5" fill="#4E79A7"/>
                <circle cx="58" cy="16" r="2.5" fill="#4E79A7"/>
            </svg>
        """.trimIndent()

        ChartType.AREA -> """
            <svg viewBox="0 0 64 48" width="48" height="36">
                <polygon points="6,36 20,20 34,28 48,10 58,16 58,44 6,44"
                         fill="#4E79A7" fill-opacity="0.35" stroke="#4E79A7" stroke-width="2"/>
            </svg>
        """.trimIndent()

        ChartType.PIE -> """
            <svg viewBox="0 0 48 48" width="40" height="40">
                <circle cx="24" cy="24" r="20" fill="#4E79A7"/>
                <path d="M24 24 L24 4 A20 20 0 0 1 41.3 34 Z" fill="#F28E2B"/>
                <path d="M24 24 L41.3 34 A20 20 0 0 1 12.6 41.9 Z" fill="#E15759"/>
            </svg>
        """.trimIndent()

        ChartType.DONUT -> """
            <svg viewBox="0 0 48 48" width="40" height="40">
                <circle cx="24" cy="24" r="20" fill="#4E79A7"/>
                <path d="M24 24 L24 4 A20 20 0 0 1 41.3 34 Z" fill="#F28E2B"/>
                <path d="M24 24 L41.3 34 A20 20 0 0 1 12.6 41.9 Z" fill="#E15759"/>
                <circle cx="24" cy="24" r="9" fill="#faf9f8"/>
            </svg>
        """.trimIndent()

        ChartType.SCATTER -> """
            <svg viewBox="0 0 64 48" width="48" height="36">
                <circle cx="10" cy="34" r="3" fill="#4E79A7"/>
                <circle cx="20" cy="18" r="3" fill="#F28E2B"/>
                <circle cx="30" cy="30" r="3" fill="#E15759"/>
                <circle cx="40" cy="12" r="3" fill="#76B7B2"/>
                <circle cx="48" cy="24" r="3" fill="#59A14F"/>
                <circle cx="56" cy="8"  r="3" fill="#EDC948"/>
            </svg>
        """.trimIndent()

        ChartType.RADAR -> """
            <svg viewBox="0 0 48 48" width="40" height="40">
                <polygon points="24,6 42,18 34,40 14,40 6,18"
                         fill="none" stroke="#d0d0d0" stroke-width="1"/>
                <polygon points="24,14 34,20 30,34 18,34 14,20"
                         fill="#4E79A7" fill-opacity="0.4" stroke="#4E79A7" stroke-width="2"/>
            </svg>
        """.trimIndent()

        ChartType.MAP -> """
            <svg viewBox="0 0 64 48" width="48" height="36">
                <path d="M6 30 L14 12 L26 8 L34 20 L30 34 L46 26 L58 38 L44 44 L20 42 Z"
                      fill="#76B7B2" stroke="#4E79A7" stroke-width="1.5"/>
                <circle cx="24" cy="24" r="3" fill="#E15759"/>
                <circle cx="42" cy="30" r="3" fill="#F28E2B"/>
            </svg>
        """.trimIndent()
    }

    private fun reload(currentAreaId: UUID) {
        grid.setItems(chartService.findByArea(currentAreaId))
    }

    /**
     * Tutti i campi (dimensioni + metriche) dell'Area: i grafici
     * dipendono dall'Analisi, non da nessuna PivotView, quindi ogni
     * campo dell'area è sempre ammesso/selezionabile qui, nessuno
     * disabilitato.
     */
    private fun campiAmmessi(currentAreaId: UUID): Set<UUID> {
        val dimensioni = registryRepository.findDimensioniByArea(currentAreaId).map { it.dimensioneId }
        val metriche = registryRepository.findMetricheByArea(currentAreaId).map { it.id }
        return (dimensioni + metriche).toSet()
    }

    /**
     * Selezioni correnti (verde/bianco/grigio) dell'utente per quest'area:
     * uniche e condivise per l'Area (non per vista), usate per calcolare
     * la preview del grafico con gli stessi filtri che vede in pagina
     * Analisi/Configura Analisi.
     */
    private fun selezioniCorrenti(currentAreaId: UUID): Map<UUID, Set<Long>> {
        val userId = CurrentUserHolder.get()?.userId ?: return emptyMap()
        return userPivotStateRepository.find(userId, currentAreaId)?.selections ?: emptyMap()
    }

    /**
     * Costruisce il PivotPanel dedicato al grafico: tutti i campi
     * dell'area, TUTTI abilitati (nessuna disabilitazione), perché i
     * grafici dipendono dall'Analisi intera, non da una PivotView
     * specifica.
     *
     * onChange riceve righe/colonne/valori come parametri diretti dal
     * PivotPanel (non va letto da una variabile esterna catturata nella
     * lambda): il costruttore/setFieldsWithIds/restoreState di PivotPanel
     * possono scatenare onChange in modo sincrono, PRIMA che l'assegnazione
     * "val pivotPanel = ..." nel chiamante sia completata.
     */
    private fun buildChartPivotPanelWithCallback(
        currentAreaId: UUID,
        onChange: (rows: List<UUID>, columns: List<UUID>, values: List<UUID>) -> Unit
    ): PivotPanel {
        val dimensioni = registryRepository.findDimensioniByArea(currentAreaId)
            .mapNotNull { d -> registryRepository.findDimensione(d.dimensioneId)?.let { d.dimensioneId to it.nome } }
        val metriche = registryRepository.findMetricheByArea(currentAreaId).map { it.id to it.nome }

        val panel = PivotPanel(onChange)
        panel.setFieldsWithIds(dimensioni, metriche)
        return panel
    }

    /** Chiude il form inline (creazione o edit) senza salvare, ripulendo l'area. */
    private fun closeForm() {
        formArea.removeAll()
        formArea.isVisible = false
    }

    /**
     * Apre il form inline di creazione: il tipo arriva già deciso dal
     * click sulla card. Righe/Colonne/Valori si scelgono col PivotPanel
     * dedicato, con preview live sotto che si aggiorna ad ogni modifica
     * del pivot o delle opzioni.
     */
    private fun openCreateForm(currentAreaId: UUID, tipo: ChartType) {
        val metriche = registryRepository.findMetricheByArea(currentAreaId)
        if (metriche.isEmpty()) {
            Notification.show("L'analisi non ha metriche configurate: aggiungine prima dalla vista principale")
            return
        }

        formArea.removeAll()

        val titoloField = TextField("Titolo").apply { setWidthFull() }

        val orderByCombo = ComboBox<AggregateOrder>("Ordinamento").apply {
            setItems(AggregateOrder.entries)
            setItemLabelGenerator {
                when (it) {
                    AggregateOrder.DIMENSION -> "Per etichetta (A-Z)"
                    AggregateOrder.METRIC_DESC -> "Per valore, decrescente"
                    AggregateOrder.METRIC_ASC -> "Per valore, crescente"
                }
            }
            value = AggregateOrder.DIMENSION
            setWidthFull()
        }
        val maxItemsField = com.vaadin.flow.component.textfield.IntegerField("Limite righe (vuoto = automatico)").apply {
            setWidthFull()
        }
        val followsColumnsCheckbox = com.vaadin.flow.component.checkbox.Checkbox(
            "Segui le Colonne del grafico (una serie per valore, es. una per anno)"
        )
        val highlightDeclineCheckbox = com.vaadin.flow.component.checkbox.Checkbox(
            "Evidenzia i cali (rosso) confrontando l'ultima colonna con la precedente"
        ).apply { isEnabled = false }
        followsColumnsCheckbox.addValueChangeListener { event ->
            highlightDeclineCheckbox.isEnabled = event.value
            if (!event.value) highlightDeclineCheckbox.value = false
        }

        val previewContainer = VerticalLayout().apply { isPadding = false }

        // Ultimo stato pivot noto, aggiornato dal callback del PivotPanel
        // e riletto dai listener dei campi opzione (titolo, ordinamento,
        // ecc.) per ricalcolare la preview senza dover leggere pivotPanel
        // direttamente.
        var ultimoPivotRows: List<UUID> = emptyList()
        var ultimoPivotColumns: List<UUID> = emptyList()
        var ultimoPivotValues: List<UUID> = emptyList()

        fun aggiornaPreview(pivotRows: List<UUID>, pivotColumns: List<UUID>, pivotValues: List<UUID>) {
            ultimoPivotRows = pivotRows
            ultimoPivotColumns = pivotColumns
            ultimoPivotValues = pivotValues

            previewContainer.removeAll()
            if (pivotRows.isEmpty() || pivotValues.isEmpty()) {
                previewContainer.add(buildPreviewPlaceholder("Seleziona almeno una dimensione in Righe e una metrica in Valori"))
                return
            }
            val bozza = AreaChart(
                id = UUID.randomUUID(),
                areaId = currentAreaId,
                titolo = titoloField.value?.trim()?.ifBlank { "Anteprima" } ?: "Anteprima",
                tipo = tipo,
                orderBy = orderByCombo.value ?: AggregateOrder.DIMENSION,
                maxItems = maxItemsField.value,
                followsColumns = followsColumnsCheckbox.value,
                highlightDecline = highlightDeclineCheckbox.value,
                pivotRows = pivotRows,
                pivotColumns = pivotColumns
            )
            renderPreview(previewContainer, bozza, pivotValues, currentAreaId)
        }

        val pivotPanel = buildChartPivotPanelWithCallback(currentAreaId) { rows, columns, values ->
            aggiornaPreview(rows, columns, values)
        }

        titoloField.addValueChangeListener { aggiornaPreview(ultimoPivotRows, ultimoPivotColumns, ultimoPivotValues) }
        orderByCombo.addValueChangeListener { aggiornaPreview(ultimoPivotRows, ultimoPivotColumns, ultimoPivotValues) }
        maxItemsField.addValueChangeListener { aggiornaPreview(ultimoPivotRows, ultimoPivotColumns, ultimoPivotValues) }
        followsColumnsCheckbox.addValueChangeListener { aggiornaPreview(ultimoPivotRows, ultimoPivotColumns, ultimoPivotValues) }
        highlightDeclineCheckbox.addValueChangeListener { aggiornaPreview(ultimoPivotRows, ultimoPivotColumns, ultimoPivotValues) }

        val cancelButton = Button("Annulla") { closeForm() }
        val createButton = Button("Crea") {
            val titolo = titoloField.value?.trim()
            if (titolo.isNullOrBlank()) {
                Notification.show("Il titolo è obbligatorio")
                return@Button
            }
            val (pivotRows, pivotColumns, pivotValues) = pivotPanel.currentState()
            if (pivotRows.isEmpty()) {
                Notification.show("Seleziona almeno una dimensione in Righe")
                return@Button
            }
            if (pivotValues.isEmpty()) {
                Notification.show("Seleziona almeno una metrica in Valori")
                return@Button
            }
            if (followsColumnsCheckbox.value && pivotValues.size > 1) {
                Notification.show(
                    "\"Segui le Colonne\" richiede una sola metrica: rimuovine alcune o disattiva l'opzione",
                    5000, Notification.Position.MIDDLE
                )
                return@Button
            }
            try {
                chartService.create(
                    areaId = currentAreaId,
                    titolo = titolo,
                    tipo = tipo,
                    metricaIds = pivotValues,
                    pivotRows = pivotRows,
                    pivotColumns = pivotColumns,
                    orderBy = orderByCombo.value ?: AggregateOrder.DIMENSION,
                    maxItems = maxItemsField.value,
                    followsColumns = followsColumnsCheckbox.value,
                    highlightDecline = highlightDeclineCheckbox.value
                )
                reload(currentAreaId)
                closeForm()
                Notification.show("Grafico creato", 3000, Notification.Position.BOTTOM_END)
            } catch (e: Exception) {
                Notification.show("Errore: ${e.message}", 5000, Notification.Position.MIDDLE)
            }
        }.apply { addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY) }

        formArea.add(
            Span("Nuovo grafico: ${chartTypeLabel(tipo)}").apply { className = "lbi-section-title" },
            titoloField,
            Span("Righe, Colonne e Valori (tutti i campi dell'analisi sono selezionabili)")
                .apply { className = "lbi-wizard-label" },
            pivotPanel,
            orderByCombo,
            maxItemsField,
            followsColumnsCheckbox,
            highlightDeclineCheckbox,
            Span("Anteprima").apply { className = "lbi-section-title" },
            previewContainer,
            HorizontalLayout(cancelButton, createButton).apply { isPadding = false }
        )
        formArea.isVisible = true
    }

    /**
     * Apre il form inline di modifica: stesso layout della creazione, ma
     * precompilato con la configurazione salvata. Il tipo non è
     * modificabile (cambiare tipo significa cambiare i vincoli: più
     * semplice eliminare e ricreare col tipo giusto).
     */
    private fun openEditForm(currentAreaId: UUID, chart: AreaChart) {
        val tutteMetriche = registryRepository.findMetricheByArea(currentAreaId)
        if (tutteMetriche.isEmpty()) {
            Notification.show("L'analisi non ha metriche configurate")
            return
        }
        val metricheAttuali = chartService.getMetricheDelGrafico(chart.id)
            .sortedBy { it.posizione }
            .mapNotNull { cm -> tutteMetriche.find { it.id == cm.metricaId } }

        formArea.removeAll()

        val titoloField = TextField("Titolo").apply {
            setWidthFull()
            value = chart.titolo
        }
        val orderByCombo = ComboBox<AggregateOrder>("Ordinamento").apply {
            setItems(AggregateOrder.entries)
            setItemLabelGenerator {
                when (it) {
                    AggregateOrder.DIMENSION -> "Per etichetta (A-Z)"
                    AggregateOrder.METRIC_DESC -> "Per valore, decrescente"
                    AggregateOrder.METRIC_ASC -> "Per valore, crescente"
                }
            }
            value = chart.orderBy
            setWidthFull()
        }
        val maxItemsField = com.vaadin.flow.component.textfield.IntegerField("Limite righe (vuoto = automatico)").apply {
            setWidthFull()
            value = chart.maxItems
        }
        val followsColumnsCheckbox = com.vaadin.flow.component.checkbox.Checkbox(
            "Segui le Colonne del grafico (una serie per valore, es. una per anno)"
        ).apply { value = chart.followsColumns }
        val highlightDeclineCheckbox = com.vaadin.flow.component.checkbox.Checkbox(
            "Evidenzia i cali (rosso) confrontando l'ultima colonna con la precedente"
        ).apply {
            value = chart.highlightDecline
            isEnabled = chart.followsColumns
        }
        followsColumnsCheckbox.addValueChangeListener { event ->
            highlightDeclineCheckbox.isEnabled = event.value
            if (!event.value) highlightDeclineCheckbox.value = false
        }

        val previewContainer = VerticalLayout().apply { isPadding = false }

        var ultimoPivotRows: List<UUID> = chart.pivotRows
        var ultimoPivotColumns: List<UUID> = chart.pivotColumns
        var ultimoPivotValues: List<UUID> = metricheAttuali.map { it.id }

        fun aggiornaPreview(pivotRows: List<UUID>, pivotColumns: List<UUID>, pivotValues: List<UUID>) {
            ultimoPivotRows = pivotRows
            ultimoPivotColumns = pivotColumns
            ultimoPivotValues = pivotValues

            previewContainer.removeAll()
            if (pivotRows.isEmpty() || pivotValues.isEmpty()) {
                previewContainer.add(buildPreviewPlaceholder("Seleziona almeno una dimensione in Righe e una metrica in Valori"))
                return
            }
            val bozza = chart.copy(
                titolo = titoloField.value?.trim()?.ifBlank { chart.titolo } ?: chart.titolo,
                orderBy = orderByCombo.value ?: chart.orderBy,
                maxItems = maxItemsField.value,
                followsColumns = followsColumnsCheckbox.value,
                highlightDecline = highlightDeclineCheckbox.value,
                pivotRows = pivotRows,
                pivotColumns = pivotColumns
            )
            renderPreview(previewContainer, bozza, pivotValues, currentAreaId)
        }

        val pivotPanel = buildChartPivotPanelWithCallback(currentAreaId) { rows, columns, values ->
            aggiornaPreview(rows, columns, values)
        }
        pivotPanel.restoreState(chart.pivotRows, chart.pivotColumns, metricheAttuali.map { it.id })
        // restoreState non chiama onChange (per design di PivotPanel):
        // la preview iniziale va quindi disegnata esplicitamente qui,
        // con lo stato salvato del grafico.
        aggiornaPreview(chart.pivotRows, chart.pivotColumns, metricheAttuali.map { it.id })

        titoloField.addValueChangeListener { aggiornaPreview(ultimoPivotRows, ultimoPivotColumns, ultimoPivotValues) }
        orderByCombo.addValueChangeListener { aggiornaPreview(ultimoPivotRows, ultimoPivotColumns, ultimoPivotValues) }
        maxItemsField.addValueChangeListener { aggiornaPreview(ultimoPivotRows, ultimoPivotColumns, ultimoPivotValues) }
        followsColumnsCheckbox.addValueChangeListener { aggiornaPreview(ultimoPivotRows, ultimoPivotColumns, ultimoPivotValues) }
        highlightDeclineCheckbox.addValueChangeListener { aggiornaPreview(ultimoPivotRows, ultimoPivotColumns, ultimoPivotValues) }

        val cancelButton = Button("Annulla") { closeForm() }
        val saveButton = Button("Salva") {
            val titolo = titoloField.value?.trim()
            if (titolo.isNullOrBlank()) {
                Notification.show("Il titolo è obbligatorio")
                return@Button
            }
            val (pivotRows, pivotColumns, pivotValues) = pivotPanel.currentState()
            if (pivotRows.isEmpty()) {
                Notification.show("Seleziona almeno una dimensione in Righe")
                return@Button
            }
            if (pivotValues.isEmpty()) {
                Notification.show("Seleziona almeno una metrica in Valori")
                return@Button
            }
            if (followsColumnsCheckbox.value && pivotValues.size > 1) {
                Notification.show(
                    "\"Segui le Colonne\" richiede una sola metrica: rimuovine alcune o disattiva l'opzione",
                    5000, Notification.Position.MIDDLE
                )
                return@Button
            }
            val updatedChart = chart.copy(
                titolo = titolo,
                orderBy = orderByCombo.value ?: chart.orderBy,
                maxItems = maxItemsField.value,
                followsColumns = followsColumnsCheckbox.value,
                highlightDecline = highlightDeclineCheckbox.value,
                pivotRows = pivotRows,
                pivotColumns = pivotColumns
            )
            try {
                chartService.update(updatedChart, pivotValues)
                reload(currentAreaId)
                closeForm()
                Notification.show("Grafico aggiornato", 3000, Notification.Position.BOTTOM_END)
            } catch (e: Exception) {
                Notification.show("Errore: ${e.message}", 5000, Notification.Position.MIDDLE)
            }
        }.apply { addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY) }

        formArea.add(
            Span("Modifica \"${chart.titolo}\" (${chartTypeLabel(chart.tipo)})").apply { className = "lbi-section-title" },
            titoloField,
            Span("Righe, Colonne e Valori (tutti i campi dell'analisi sono selezionabili)")
                .apply { className = "lbi-wizard-label" },
            pivotPanel,
            orderByCombo,
            maxItemsField,
            followsColumnsCheckbox,
            highlightDeclineCheckbox,
            Span("Anteprima").apply { className = "lbi-section-title" },
            previewContainer,
            HorizontalLayout(cancelButton, saveButton).apply { isPadding = false }
        )
        formArea.isVisible = true
    }

    /**
     * Calcola e disegna la preview del grafico con i dati reali (stessa
     * ChartService.getChartDataForPreview usata a runtime per grafici non
     * ancora salvati), usando le selezioni correnti dell'utente per
     * l'area - se il grafico bozza non è coerente (metriche non più
     * esistenti), mostra un placeholder invece di un errore.
     */
    private fun renderPreview(container: VerticalLayout, bozza: AreaChart, metricaIds: List<UUID>, currentAreaId: UUID) {
        val campiAmmessiCorrente = campiAmmessi(currentAreaId)
        val selections = selezioniCorrenti(currentAreaId)

        try {
            val result = chartService.getChartDataForPreview(bozza, metricaIds, campiAmmessiCorrente, selections)
            when (result) {
                is ChartResult.Ready -> {
                    val chartComponent = EChartComponent()
                    container.add(chartComponent)
                    chartComponent.render(result.data)
                }
                is ChartResult.Incoherent -> {
                    container.add(buildPreviewPlaceholder(result.reason))
                }
            }
        } catch (e: Exception) {
            container.add(buildPreviewPlaceholder("Impossibile calcolare l'anteprima: ${e.message}"))
        }
    }

    private fun buildPreviewPlaceholder(message: String): Span =
        Span(message).apply { className = "lbi-wizard-label" }

    private fun confirmDelete(chart: AreaChart) {
        val dialog = Dialog().apply {
            headerTitle = "Eliminare \"${chart.titolo}\"?"
            width = "440px"
        }
        dialog.add(Span("Il grafico sarà rimosso definitivamente."))
        val cancelButton = Button("Annulla") { dialog.close() }
        val confirmButton = Button("Elimina") {
            chartService.delete(chart.id)
            areaId?.let { reload(it) }
            closeForm()
            dialog.close()
        }
        dialog.footer.add(cancelButton, confirmButton)
        dialog.open()
    }
}