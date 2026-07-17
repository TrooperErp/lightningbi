package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.AggregateRequest
import com.lightningbi.lightning_engine.model.AggregateResult
import com.lightningbi.lightning_engine.model.Area
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.service.AggregateService
import com.lightningbi.lightning_engine.service.AssociativeStateService
import com.lightningbi.lightning_engine.service.CryptoService
import com.lightningbi.lightning_engine.service.DimensionState
import com.lightningbi.lightning_engine.service.MetadataService
import com.lightningbi.lightning_engine.service.RegistryService
import com.lightningbi.lightning_engine.service.SymbolTableService
import com.lightningbi.lightning_engine.service.VersionService
import com.lightningbi.lightning_engine.service.ViewSqlGenerator
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.contextmenu.MenuItem
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.listbox.MultiSelectListBox
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
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
    private val areaSourceRepository: AreaSourceRepository,
    private val cryptoService: CryptoService,
    private val metadataService: MetadataService,
    private val viewSqlGenerator: ViewSqlGenerator
) : VerticalLayout() {

    private var areaId: UUID? = null

    private val selections = mutableMapOf<UUID, Set<Long>>()
    private val dimensionBoxes = mutableMapOf<UUID, MultiSelectListBox<Long>>()
    private val currentItems = mutableMapOf<UUID, List<Long>>()

    private val requestCounter = AtomicLong(0)
    private val resultsGrid = Grid<Map<String, Any?>>()
    private val filtersColumn = VerticalLayout()
    private val sidebarAreaList = VerticalLayout()
    private lateinit var selectAreaMenuItem: MenuItem

    private var viewScope: CoroutineScope? = null
    private var isDark = false
    private var currentAreas: List<Area> = emptyList()

    init {
        className = "lbi-app"
        setSizeFull()
        isPadding = false
        isSpacing = false

        currentAreas = registryRepository.findAllAree()

        // ===== Menu top stile desktop =====
        val menuBar = MenuBar().apply { className = "lbi-menubar" }
        selectAreaMenuItem = menuBar.addItem("Seleziona Area")
        rebuildAreaMenu()

        val addAreaItem = menuBar.addItem("+ Nuova analisi")
        addAreaItem.addClickListener {
            AddAreaWizardDialog(registryService, registryRepository, symbolTableService)  {
                currentAreas = registryRepository.findAllAree()
                rebuildAreaMenu()
                currentAreas.lastOrNull()?.let { switchArea(it.id) }
            }.open()
        }

        val logoImage = com.vaadin.flow.component.html.Image("images/logo.png", "LightningBI").apply {
            className = "lbi-logo-img"
        }
        val logoSpan = Span("LightningBI").apply { className = "lbi-logo" }
        val logoContainer = HorizontalLayout(logoImage, logoSpan).apply {
            className = "lbi-logo-container"
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            isSpacing = true
        }

        val themeToggle = Button("Dark").apply {
            addThemeVariants(ButtonVariant.LUMO_TERTIARY)
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

        val topMenuBar = HorizontalLayout(logoContainer, menuBar, themeToggle).apply {
            className = "lbi-topmenu"
            justifyContentMode = FlexComponent.JustifyContentMode.BETWEEN
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            setWidthFull()
        }

        // ===== Sidebar (vuota per ora, riservata a future voci) =====
        val sidebar = VerticalLayout(
            Span("Menu").apply {
                className = "lbi-sidebar-title"
            },
            createSidebarItem("Nuova analisi") {
                AddAreaWizardDialog(registryService, registryRepository, symbolTableService) {
                    currentAreas = registryRepository.findAllAree()
                    rebuildAreaMenu()
                    currentAreas.lastOrNull()?.let { switchArea(it.id) }
                }.open()
            },
            createSidebarItem("Gestione utenti") {
                Notification.show("Funzione in arrivo")
            },
            createSidebarItem("Sorgenti Dati") {
                val currentArea = currentAreas.find { it.id == areaId }
                if (currentArea == null) {
                    Notification.show("Seleziona prima un'analisi")
                    return@createSidebarItem
                }
                val existing = areaSourceRepository.findByArea(currentArea.id).firstOrNull()
                ConfigureSourceDialog(
                    currentArea, areaSourceRepository, registryRepository,
                    cryptoService, metadataService, viewSqlGenerator, existing
                ) {
                    Notification.show("Sorgente aggiornata")
                }.open()
            },
            createSidebarItem("Grafici Superset") {
                Notification.show("Funzione in arrivo")
            },
            createSidebarItem("Stampe") {
                Notification.show("Funzione in arrivo")
            }

        ).apply {
            className = "lbi-sidebar"
            setWidth("260px")
            height = "100%"
        }
        // ===== Grid risultati centrale =====
        resultsGrid.className = "lbi-results-grid"
        resultsGrid.setSizeFull()

        val centerArea = VerticalLayout(
            Span("Risultati").apply { className = "lbi-section-title" },
            resultsGrid
        ).apply {
            className = "lbi-center"
            setSizeFull()
            isPadding = true
        }

        // ===== Colonna destra filtri =====
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

    private fun rebuildAreaMenu() {
        selectAreaMenuItem.subMenu.removeAll()
        currentAreas.forEach { area ->
            selectAreaMenuItem.subMenu.addItem(area.nome) {
                switchArea(area.id)
            }
        }
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        viewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        refresh()
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

            val title = Span(dimensione.nome).apply { className = "lbi-filter-title" }
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

        scope.launch {
            try {
                val versions = versionService.snapshotVersions(currentAreaId)

                val statesDeferred = async {
                    associativeStateService.getStates(currentAreaId, selectionsSnapshot, versions)
                }
                val aggregatesDeferred = async {
                    aggregateService.getAggregates(AggregateRequest(currentAreaId, selectionsSnapshot), versions)
                }
                val states = statesDeferred.await()
                val aggregates = aggregatesDeferred.await()

                ui.access {
                    if (myRequestId != requestCounter.get()) return@access
                    if (areaId != currentAreaId) return@access
                    renderStates(states)
                    renderResultsGrid(aggregates)
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

    private fun renderStates(states: Map<UUID, DimensionState>) {
        states.forEach { (dimId, state) ->
            val box = dimensionBoxes[dimId] ?: return@forEach

            val allValues = (state.verdi + state.grigi + state.selezionati).distinct().sorted()
            if (currentItems[dimId] != allValues) {
                box.setItems(allValues)
                currentItems[dimId] = allValues
            }

            box.setRenderer(ComponentRenderer { valueId ->
                Span(valueId.toString()).apply {
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

    private fun renderResultsGrid(result: AggregateResult) {
        resultsGrid.removeAllColumns()

        val rows = result.rows.map { it.values }
        if (rows.isEmpty()) {
            resultsGrid.setItems(emptyList())
            return
        }

        val columnNames = rows.first().keys.toList()
        columnNames.forEach { colName ->
            resultsGrid.addColumn { row -> row[colName]?.toString() ?: "" }
                .setHeader(colName.replaceFirstChar { it.uppercase() })
                .setAutoWidth(true)
        }

        resultsGrid.setItems(rows)

        if (result.truncated) {
            Notification.show("Risultato troncato: troppe righe da mostrare", 4000, Notification.Position.BOTTOM_END)
        }
    }

    private fun neutralRenderer() = ComponentRenderer<Span, Long> { valueId ->
        Span(valueId.toString()).apply { className = "state-possible" }
    }
    private fun createSidebarItem(label: String, onClick: () -> Unit): Div {
        return Div(Span(label)).apply {
            className = "lbi-sidebar-item"
            addClickListener { onClick() }
        }
    }
}