package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.etl.EtlOrchestrator
import com.lightningbi.lightning_engine.model.AggregateRequest
import com.lightningbi.lightning_engine.model.AggregateResult
import com.lightningbi.lightning_engine.model.AggregateRow
import com.lightningbi.lightning_engine.model.Area
import com.lightningbi.lightning_engine.model.SourceStatus
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.service.AggregateService
import com.lightningbi.lightning_engine.service.AssociativeStateService
import com.lightningbi.lightning_engine.service.CryptoService
import com.lightningbi.lightning_engine.service.DimensionState
import com.lightningbi.lightning_engine.service.MetadataService
import com.lightningbi.lightning_engine.service.RegistryService
import com.lightningbi.lightning_engine.service.SourceVerificationService
import com.lightningbi.lightning_engine.service.SymbolLookupService
import com.lightningbi.lightning_engine.service.SymbolTableService
import com.lightningbi.lightning_engine.service.VersionService
import com.lightningbi.lightning_engine.service.ViewSqlGenerator
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.confirmdialog.ConfirmDialog
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.listbox.MultiSelectListBox
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.data.renderer.ComponentRenderer
import com.vaadin.flow.router.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@Route("associative")
class AssociativeExplorerView(
    private val associativeStateService: AssociativeStateService,
    private val aggregateService: AggregateService,
    private val versionService: VersionService,
    private val registryRepository: RegistryRepository,
    private val registryService: RegistryService,
    private val symbolTableService: SymbolTableService,
    private val symbolLookupService: SymbolLookupService,
    private val areaSourceRepository: AreaSourceRepository,
    private val sourceVerificationService: SourceVerificationService,
    private val etlOrchestrator: EtlOrchestrator,
    private val cryptoService: CryptoService,
    private val metadataService: MetadataService,
    private val viewSqlGenerator: ViewSqlGenerator
) : VerticalLayout() {

    private var areaId: UUID? = null

    private val selections = mutableMapOf<UUID, Set<Long>>()
    private val dimensionBoxes = mutableMapOf<UUID, MultiSelectListBox<Long>>()
    private val currentItems = mutableMapOf<UUID, List<Long>>()

    private var pivotRows: List<UUID> = emptyList()
    private var pivotValues: List<UUID> = emptyList()
    private val pivotPanel = PivotPanel { rows, values -> onPivotChanged(rows, values) }

    private val requestCounter = AtomicLong(0)
    private val resultsGrid = Grid<AggregateRow>()
    private val filtersColumn = VerticalLayout()
    private val sidebar = LbiSidebarMenu()
    private val sourceStatusLabel = Span().apply { className = "lbi-source-status" }

    // Stato corrente della sorgente dell'area aperta, usato per decidere
    // quali voci del gruppo "Gestisci" in sidebar sono abilitate. Ricalcolato
    // da refreshSourceStatus() e riletto da buildMenuGroups() ogni volta
    // che la sidebar viene ridisegnata.
    private var sourceStatus: SourceStatus? = null
    private var hasSource: Boolean = false

    private var viewScope: CoroutineScope? = null
    private var isDark = false
    private var currentAreas: List<Area> = emptyList()

    init {
        className = "lbi-app"
        setSizeFull()
        isPadding = false
        isSpacing = false

        currentAreas = registryRepository.findAllAree()

        val logoImage = Image("images/logo.png", "LightningBI").apply { className = "lbi-logo-img" }
        val logoSpan = Span("LightningBI").apply { className = "lbi-logo" }
        val logoContainer = HorizontalLayout(logoImage, logoSpan).apply {
            className = "lbi-logo-container"
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            isSpacing = true
        }

        val themeToggle = Button("Dark").apply {
            className = "lbi-theme-toggle"
            addClickListener {
                isDark = !isDark
                element.executeJs(
                    "document.documentElement.setAttribute('theme', \$0)",
                    if (isDark) "dark" else ""
                )
                text = if (isDark) "Light" else "Dark"
            }
        }

        val topMenuBar = HorizontalLayout(logoContainer, themeToggle).apply {
            className = "lbi-topmenu"
            justifyContentMode = FlexComponent.JustifyContentMode.BETWEEN
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            setWidthFull()
        }

        sidebar.setGroups(buildMenuGroups())

        resultsGrid.className = "lbi-results-grid"
        resultsGrid.setSizeFull()

        val statusRow = HorizontalLayout(sourceStatusLabel).apply {
            className = "lbi-action-bar"
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            isPadding = false
            setWidthFull()
        }

        val centerArea = VerticalLayout(
            statusRow,
            pivotPanel,
            Span("Risultati").apply { className = "lbi-section-title" },
            resultsGrid
        ).apply {
            className = "lbi-center"
            setSizeFull()
            isPadding = true
            setFlexGrow(0.0, statusRow)
            setFlexGrow(0.0, pivotPanel)
            setFlexGrow(1.0, resultsGrid)
        }

        filtersColumn.className = "lbi-filters-column"
        filtersColumn.height = "100%"

        val body = HorizontalLayout(sidebar, centerArea, filtersColumn).apply {
            className = "lbi-body"
            setSizeFull()
            isPadding = false
            isSpacing = true
            setFlexGrow(0.0, sidebar)
            setFlexGrow(1.0, centerArea)
            setFlexGrow(0.0, filtersColumn)
        }

        add(topMenuBar, body)
        setFlexGrow(0.0, topMenuBar)
        setFlexGrow(1.0, body)

        if (currentAreas.isNotEmpty()) {
            switchArea(currentAreas.first().id)
        }
    }

    // ================= Sidebar: menu "Gestisci" incluso =================

    /**
     * Tutte le voci di navigazione e di gestione dell'analisi vivono qui,
     * nella sidebar - non in un menu separato sopra i risultati. Le voci
     * di "Gestisci" sono abilitate/disabilitate in base a sourceStatus e
     * hasSource, aggiornati da refreshSourceStatus() prima di ogni
     * ricostruzione della sidebar.
     */
    private fun buildMenuGroups(): List<LbiSidebarMenu.MenuGroup> {
        val groups = mutableListOf(
            LbiSidebarMenu.MenuGroup(
                label = "Analisi",
                entries = currentAreas.map { area ->
                    LbiSidebarMenu.MenuEntry(area.nome) { switchArea(area.id) }
                } + LbiSidebarMenu.MenuEntry("+ Nuova analisi") { openNewAnalysisWizard() }
            )
        )

        if (areaId != null) {
            val verificaEnabled = hasSource
            val sincronizzaEnabled = hasSource && sourceStatus == SourceStatus.VERIFIED
            val sqlEnabled = hasSource

            groups.add(
                LbiSidebarMenu.MenuGroup(
                    label = "Gestisci",
                    entries = listOf(
                        LbiSidebarMenu.MenuEntry("Verifica sorgente", enabled = verificaEnabled) { verifySource() },
                        LbiSidebarMenu.MenuEntry("Mostra SQL view", enabled = sqlEnabled) { showViewSql() },
                        LbiSidebarMenu.MenuEntry("Sincronizza", enabled = sincronizzaEnabled) { runEtl() },
                        LbiSidebarMenu.MenuEntry("Modifica metriche") { openEditMetrics() },
                        LbiSidebarMenu.MenuEntry("Elimina analisi") { confirmDeleteArea() }
                    )
                )
            )
        }

        groups.add(
            LbiSidebarMenu.MenuGroup(
                label = "Amministrazione",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Gestione utenti") {
                        Notification.show("Funzione in arrivo")
                    }
                )
            )
        )
        groups.add(
            LbiSidebarMenu.MenuGroup(
                label = "Report",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Stampe") {
                        Notification.show("Funzione in arrivo")
                    }
                )
            )
        )
        return groups
    }

    private fun refreshSourceStatus() {
        val currentAreaId = areaId
        if (currentAreaId == null) {
            hasSource = false
            sourceStatus = null
            sourceStatusLabel.text = ""
            sidebar.setGroups(buildMenuGroups())
            return
        }

        val source = areaSourceRepository.findByArea(currentAreaId).firstOrNull()
        hasSource = source != null
        sourceStatus = source?.status

        sourceStatusLabel.text = when {
            source == null -> "Nessuna sorgente collegata"
            source.status == SourceStatus.VERIFIED -> "Sorgente verificata: ${source.config.viewName}"
            source.status == SourceStatus.ERROR -> "Sorgente in errore: ${source.errorDetail ?: "causa non registrata"}"
            else -> "View da creare sul database di origine (${source.config.viewName})"
        }

        // La sidebar va ricostruita ad ogni cambio di stato, perché le
        // voci abilitate/disabilitate dipendono da hasSource/sourceStatus.
        sidebar.setGroups(buildMenuGroups())
    }

    private fun verifySource() {
        val currentAreaId = areaId ?: return
        val ui = ui.orElse(null) ?: return
        val scope = viewScope ?: return

        sourceStatusLabel.text = "Verifica in corso..."

        scope.launch {
            val results = try {
                sourceVerificationService.verifyArea(currentAreaId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.access {
                    Notification.show("Verifica fallita: ${e.message}", 6000, Notification.Position.MIDDLE)
                    refreshSourceStatus()
                }
                return@launch
            }
            ui.access {
                if (areaId != currentAreaId) return@access
                val failed = results.firstOrNull { !it.ok }
                if (failed != null) {
                    Notification.show(failed.message, 8000, Notification.Position.MIDDLE)
                } else {
                    Notification.show("Sorgente verificata: si può sincronizzare", 4000, Notification.Position.BOTTOM_END)
                }
                refreshSourceStatus()
            }
        }
    }

    private fun runEtl() {
        val currentAreaId = areaId ?: return
        val ui = ui.orElse(null) ?: return
        val scope = viewScope ?: return
        val sources = areaSourceRepository.findByArea(currentAreaId)
        if (sources.isEmpty()) {
            Notification.show("Nessuna sorgente da sincronizzare")
            return
        }

        sourceStatusLabel.text = "Sincronizzazione in corso..."

        scope.launch {
            try {
                sources.forEach { etlOrchestrator.runForArea(currentAreaId, it) }
                ui.access {
                    if (areaId != currentAreaId) return@access
                    Notification.show("Sincronizzazione completata", 4000, Notification.Position.BOTTOM_END)
                    refreshSourceStatus()
                    refresh()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.access {
                    Notification.show(
                        "Sincronizzazione fallita: ${e.message ?: e::class.simpleName}",
                        8000, Notification.Position.MIDDLE
                    )
                    refreshSourceStatus()
                }
            }
        }
    }

    private fun showViewSql() {
        val currentAreaId = areaId ?: return
        val source = areaSourceRepository.findByArea(currentAreaId).firstOrNull() ?: return

        val attese = sourceVerificationService.expectedColumns(currentAreaId, source.config.syncMode)
        val dialog = Dialog().apply {
            className = "lbi-wizard-dialog"
            headerTitle = "View attesa: ${source.config.viewName}"
            width = "760px"
        }
        val area = TextArea().apply {
            isReadOnly = true
            setWidthFull()
            height = "320px"
            value = buildString {
                append("-- Colonne che la view deve esporre, con questi alias esatti:\n")
                attese.forEach { append("--   ").append(it).append('\n') }
                append("\n-- Tabella di origine: ")
                append(source.config.schema?.let { "$it." } ?: "")
                append(source.config.mainTable)
            }
        }
        dialog.add(area)
        dialog.footer.add(Button("Chiudi") { dialog.close() })
        dialog.open()
    }

    private fun openEditMetrics() {
        val currentAreaId = areaId ?: return
        EditMetricsDialog(currentAreaId, registryService) {
            refreshPivotFields(currentAreaId)
            refresh()
        }.open()
    }

    private fun confirmDeleteArea() {
        val currentAreaId = areaId ?: return
        val areaNome = currentAreas.find { it.id == currentAreaId }?.nome ?: "questa analisi"

        ConfirmDialog(
            "Eliminare \"$areaNome\"?",
            "L'analisi, le sue metriche, i collegamenti alle dimensioni e i dati caricati verranno rimossi. L'operazione non è reversibile.",
            "Elimina",
            { _ -> performDeleteArea(currentAreaId) },
            "Annulla",
            { _ -> }
        ).open()
    }

    private fun performDeleteArea(targetAreaId: UUID) {
        try {
            registryService.deleteAreaCompleta(targetAreaId)
        } catch (e: Exception) {
            Notification.show("Errore durante l'eliminazione: ${e.message}", 8000, Notification.Position.MIDDLE)
        }
        currentAreas = registryRepository.findAllAree()
        if (areaId == targetAreaId) {
            areaId = null
            selections.clear()
            dimensionBoxes.clear()
            currentItems.clear()
            filtersColumn.removeAll()
            resultsGrid.setItems(emptyList())
            resultsGrid.removeAllColumns()
            currentAreas.firstOrNull()?.let { switchArea(it.id) } ?: refreshSourceStatus()
        } else {
            sidebar.setGroups(buildMenuGroups())
        }
        Notification.show("Analisi eliminata", 4000, Notification.Position.BOTTOM_END)
    }

    // ================= Wizard =================

    private fun openNewAnalysisWizard() {
        NewAnalysisWizardDialog(
            registryService, registryRepository, symbolTableService,
            areaSourceRepository, cryptoService, metadataService, viewSqlGenerator
        ) {
            currentAreas = registryRepository.findAllAree()
            currentAreas.lastOrNull()?.let { switchArea(it.id) } ?: sidebar.setGroups(buildMenuGroups())
        }.open()
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        refreshSourceStatus()
        refresh()
    }

    override fun onDetach(detachEvent: DetachEvent) {
        viewScope?.cancel()
        viewScope = null
        super.onDetach(detachEvent)
    }

    private fun switchArea(newAreaId: UUID) {
        areaId = newAreaId
        selections.clear()
        dimensionBoxes.clear()
        currentItems.clear()
        filtersColumn.removeAll()
        resultsGrid.setItems(emptyList())
        resultsGrid.removeAllColumns()
        buildFilterCards(newAreaId)
        refreshPivotFields(newAreaId)
        refreshSourceStatus()
        refresh()
    }

    private fun refreshPivotFields(currentAreaId: UUID) {
        val dims = registryRepository.findDimensioniByArea(currentAreaId)
            .mapNotNull { ad -> registryRepository.findDimensione(ad.dimensioneId)?.let { ad.dimensioneId to it.nome } }
        val metriche = registryRepository.findMetricheByArea(currentAreaId).map { it.id to it.nome }
        pivotPanel.setFieldsWithIds(dims, metriche)
    }

    private fun onPivotChanged(rows: List<UUID>, values: List<UUID>) {
        pivotRows = rows
        pivotValues = values
        refreshAggregatesOnly()
    }

    private fun buildFilterCards(currentAreaId: UUID) {
        val dims = registryRepository.findDimensioniByArea(currentAreaId)
        dims.forEach { areaDim ->
            val dimensione = registryRepository.findDimensione(areaDim.dimensioneId) ?: return@forEach
            val dimId = areaDim.dimensioneId

            val box = MultiSelectListBox<Long>()
            box.width = "100%"
            box.height = "180px"
            box.setRenderer(neutralRenderer())
            box.addSelectionListener { event ->
                if (!event.isFromClient) return@addSelectionListener
                selections[dimId] = event.value.toSet()
                refresh()
            }
            dimensionBoxes[dimId] = box

            val etichetta = if (dims.count { it.dimensioneId == dimId } > 1) {
                "${dimensione.nome} (${areaDim.colonnaFisica})"
            } else {
                dimensione.nome
            }

            val title = Span(etichetta).apply { className = "lbi-filter-title" }
            val card = VerticalLayout(title, box).apply { className = "lbi-filter-card" }
            filtersColumn.add(card)
        }
    }

    private fun refresh() {
        val currentAreaId = areaId ?: return
        val ui = ui.orElse(null) ?: return
        val scope = viewScope ?: return
        val myRequestId = requestCounter.incrementAndGet()

        val selectionsSnapshot: Map<UUID, Set<Long>> = selections
            .filterValues { it.isNotEmpty() }
            .mapValues { it.value.toSet() }
        val rowsSnapshot = pivotRows
        val valuesSnapshot = pivotValues

        scope.launch {
            try {
                val versions = versionService.snapshotVersions(currentAreaId)

                val statesDeferred = async {
                    associativeStateService.getStates(currentAreaId, selectionsSnapshot, versions)
                }
                val aggregatesDeferred = async {
                    aggregateService.getAggregates(
                        AggregateRequest(
                            areaId = currentAreaId,
                            selections = selectionsSnapshot,
                            groupBy = rowsSnapshot,
                            metricIds = valuesSnapshot,
                            resolveLabels = true
                        ),
                        versions
                    )
                }
                val states = statesDeferred.await()
                val aggregates = aggregatesDeferred.await()

                val labels = resolveLabels(states)

                ui.access {
                    if (myRequestId != requestCounter.get()) return@access
                    if (areaId != currentAreaId) return@access
                    renderStates(states, labels)
                    renderResultsGrid(aggregates, rowsSnapshot)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.access {
                    if (myRequestId != requestCounter.get()) return@access
                    Notification.show("Errore aggiornamento: ${e.message}", 5000, Notification.Position.BOTTOM_END)
                }
            }
        }
    }

    private fun refreshAggregatesOnly() {
        val currentAreaId = areaId ?: return
        val ui = ui.orElse(null) ?: return
        val scope = viewScope ?: return
        val myRequestId = requestCounter.incrementAndGet()

        val selectionsSnapshot: Map<UUID, Set<Long>> = selections
            .filterValues { it.isNotEmpty() }
            .mapValues { it.value.toSet() }
        val rowsSnapshot = pivotRows
        val valuesSnapshot = pivotValues

        scope.launch {
            try {
                val versions = versionService.snapshotVersions(currentAreaId)
                val aggregates = aggregateService.getAggregates(
                    AggregateRequest(
                        areaId = currentAreaId,
                        selections = selectionsSnapshot,
                        groupBy = rowsSnapshot,
                        metricIds = valuesSnapshot,
                        resolveLabels = true
                    ),
                    versions
                )
                ui.access {
                    if (myRequestId != requestCounter.get()) return@access
                    if (areaId != currentAreaId) return@access
                    renderResultsGrid(aggregates, rowsSnapshot)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ui.access {
                    if (myRequestId != requestCounter.get()) return@access
                    Notification.show("Errore aggiornamento: ${e.message}", 5000, Notification.Position.BOTTOM_END)
                }
            }
        }
    }

    private fun resolveLabels(states: Map<UUID, DimensionState>): Map<UUID, Map<Long, String>> =
        states.mapNotNull { (dimId, state) ->
            val dimensione = registryRepository.findDimensione(dimId) ?: return@mapNotNull null
            val ids = state.verdi + state.grigi + state.selezionati
            dimId to symbolLookupService.resolveLabels(dimensione.nome, ids)
        }.toMap()

    private fun renderStates(
        states: Map<UUID, DimensionState>,
        labels: Map<UUID, Map<Long, String>>
    ) {
        states.forEach { (dimId, state) ->
            val box = dimensionBoxes[dimId] ?: return@forEach
            val dimLabels = labels[dimId] ?: emptyMap()

            val allValues = (state.verdi + state.grigi + state.selezionati)
                .distinct()
                .sortedBy { symbolLookupService.labelOrFallback(dimLabels, it) }

            if (currentItems[dimId] != allValues) {
                box.setItems(allValues)
                currentItems[dimId] = allValues
            }

            box.setRenderer(ComponentRenderer { valueId ->
                Span(symbolLookupService.labelOrFallback(dimLabels, valueId)).apply {
                    className = when {
                        valueId in state.selezionati -> "state-selected"
                        valueId in state.verdi -> "state-possible"
                        else -> "state-excluded"
                    }
                }
            })

            if (box.value != state.selezionati) {
                box.value = state.selezionati
            }
        }
    }

    private fun renderResultsGrid(result: AggregateResult, rows: List<UUID>) {
        resultsGrid.removeAllColumns()

        if (result.rows.isEmpty()) {
            resultsGrid.setItems(emptyList())
            return
        }

        rows.forEach { dimId ->
            val dimensione = registryRepository.findDimensione(dimId)
            resultsGrid.addColumn { row: AggregateRow ->
                row.labels[dimId] ?: row.groupKeys[dimId]?.let { "#$it" } ?: "—"
            }.setHeader(dimensione?.nome ?: "?").setAutoWidth(true)
        }

        val metricNames = result.rows.first().values.keys.toList()
        metricNames.forEach { name ->
            resultsGrid.addColumn { row: AggregateRow -> row.values[name]?.toString() ?: "" }
                .setHeader(name)
                .setAutoWidth(true)
        }

        resultsGrid.setItems(result.rows)

        if (result.truncated) {
            val messaggio = if (rows.isEmpty())
                "Risultato troncato: troppe righe da mostrare"
            else
                "Troppe combinazioni da mostrare: prova a togliere una dimensione dalle Righe"
            Notification.show(messaggio, 5000, Notification.Position.BOTTOM_END)
        }
    }

    private fun neutralRenderer() = ComponentRenderer<Span, Long> { valueId ->
        Span(valueId.toString()).apply { className = "state-possible" }
    }
}