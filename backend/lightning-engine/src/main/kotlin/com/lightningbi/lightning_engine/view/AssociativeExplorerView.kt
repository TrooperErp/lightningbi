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
import com.vaadin.flow.component.menubar.MenuBar
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
    private lateinit var gestisciMenu: MenuBar
    private lateinit var verificaItem: com.vaadin.flow.component.contextmenu.MenuItem
    private lateinit var sincronizzaItem: com.vaadin.flow.component.contextmenu.MenuItem
    private lateinit var mostraSqlItem: com.vaadin.flow.component.contextmenu.MenuItem
    private lateinit var modificaMetricheItem: com.vaadin.flow.component.contextmenu.MenuItem
    private lateinit var eliminaAnalisiItem: com.vaadin.flow.component.contextmenu.MenuItem

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

        buildGestisciMenu()

        resultsGrid.className = "lbi-results-grid"
        resultsGrid.setSizeFull()

        val statusRow = HorizontalLayout(sourceStatusLabel, gestisciMenu).apply {
            className = "lbi-action-bar"
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            isPadding = false
            justifyContentMode = FlexComponent.JustifyContentMode.BETWEEN
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

    // ================= Pannello "Gestisci" =================

    private fun buildGestisciMenu() {
        gestisciMenu = MenuBar()
        val root = gestisciMenu.addItem("Gestisci ▾")
        val subMenu = root.subMenu

        verificaItem = subMenu.addItem("Verifica sorgente") { verifySource() }
        mostraSqlItem = subMenu.addItem("Mostra SQL view") { showViewSql() }
        sincronizzaItem = subMenu.addItem("Sincronizza") { runEtl() }
        modificaMetricheItem = subMenu.addItem("Modifica metriche") { openEditMetrics() }
        eliminaAnalisiItem = subMenu.addItem("Elimina analisi") { confirmDeleteArea() }
    }

    private fun refreshSourceStatus() {
        val currentAreaId = areaId
        if (currentAreaId == null) {
            gestisciMenu.isVisible = false
            sourceStatusLabel.text = ""
            return
        }
        gestisciMenu.isVisible = true
        modificaMetricheItem.isEnabled = true
        eliminaAnalisiItem.isEnabled = true

        val sources = areaSourceRepository.findByArea(currentAreaId)
        val source = sources.firstOrNull()

        when {
            source == null -> {
                sourceStatusLabel.text = "Nessuna sorgente collegata"
                verificaItem.isEnabled = false
                sincronizzaItem.isEnabled = false
                mostraSqlItem.isEnabled = false
            }
            source.status == SourceStatus.VERIFIED -> {
                sourceStatusLabel.text = "Sorgente verificata: ${source.config.viewName}"
                verificaItem.isEnabled = true
                sincronizzaItem.isEnabled = true
                mostraSqlItem.isEnabled = true
            }
            source.status == SourceStatus.ERROR -> {
                sourceStatusLabel.text = "Sorgente in errore: ${source.errorDetail ?: "causa non registrata"}"
                verificaItem.isEnabled = true
                sincronizzaItem.isEnabled = false
                mostraSqlItem.isEnabled = true
            }
            else -> {
                sourceStatusLabel.text = "View da creare sul database di origine (${source.config.viewName})"
                verificaItem.isEnabled = true
                sincronizzaItem.isEnabled = false
                mostraSqlItem.isEnabled = true
            }
        }
    }

    private fun verifySource() {
        val currentAreaId = areaId ?: return
        val ui = ui.orElse(null) ?: return
        val scope = viewScope ?: return

        verificaItem.isEnabled = false
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

        sincronizzaItem.isEnabled = false
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
        sidebar.setGroups(buildMenuGroups())
        if (areaId == targetAreaId) {
            areaId = null
            selections.clear()
            dimensionBoxes.clear()
            currentItems.clear()
            filtersColumn.removeAll()
            resultsGrid.setItems(emptyList())
            resultsGrid.removeAllColumns()
            currentAreas.firstOrNull()?.let { switchArea(it.id) } ?: refreshSourceStatus()
        }
        Notification.show("Analisi eliminata", 4000, Notification.Position.BOTTOM_END)
    }

    // ================= Wizard e menu =================

    private fun openNewAnalysisWizard() {
        NewAnalysisWizardDialog(
            registryService, registryRepository, symbolTableService,
            areaSourceRepository, cryptoService, metadataService, viewSqlGenerator
        ) {
            currentAreas = registryRepository.findAllAree()
            sidebar.setGroups(buildMenuGroups())
            currentAreas.lastOrNull()?.let { switchArea(it.id) }
        }.open()
    }

    private fun buildMenuGroups(): List<LbiSidebarMenu.MenuGroup> = listOf(
        LbiSidebarMenu.MenuGroup(
            label = "Analisi",
            entries = currentAreas.map { area ->
                LbiSidebarMenu.MenuEntry(area.nome) { switchArea(area.id) }
            } + LbiSidebarMenu.MenuEntry("+ Nuova analisi") { openNewAnalysisWizard() }
        ),
        LbiSidebarMenu.MenuGroup(
            label = "Amministrazione",
            entries = listOf(
                LbiSidebarMenu.MenuEntry("Gestione utenti") {
                    Notification.show("Funzione in arrivo")
                }
            )
        ),
        LbiSidebarMenu.MenuGroup(
            label = "Report",
            entries = listOf(
                LbiSidebarMenu.MenuEntry("Stampe") {
                    Notification.show("Funzione in arrivo")
                }
            )
        )
    )

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

    /**
     * Richiamato dal PivotPanel ad ogni modifica delle zone Righe/Valori.
     *
     * Ricalcola SOLO gli aggregati (refreshAggregatesOnly), non gli stati
     * associativi. Gli stati dipendono dai filtri selezionati, non da come
     * si raggruppano le Righe: richiamare refresh() per intero ad ogni
     * drag pagava il costo di AssociativeStateService (una query
     * ClickHouse per dimensione) per un cambiamento che non lo riguarda -
     * è questo che rendeva il drag&drop percepito come lento.
     */
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

    /**
     * Refresh completo: filtri (stati associativi) + aggregati. Usato al
     * cambio area, al cambio selezione filtro, dopo sync/modifica metriche.
     */
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

    /**
     * Refresh leggero: solo aggregati, nessun ricalcolo degli stati
     * associativi. Usato dal pivot, dove i filtri non cambiano e le
     * listbox a destra restano esattamente come sono.
     */
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