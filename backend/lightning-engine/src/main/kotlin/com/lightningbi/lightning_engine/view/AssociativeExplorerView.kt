package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.etl.EtlOrchestrator
import com.lightningbi.lightning_engine.model.AggregateRequest
import com.lightningbi.lightning_engine.model.AggregateResult
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
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.contextmenu.MenuItem
import com.vaadin.flow.component.grid.Grid
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

    private val requestCounter = AtomicLong(0)
    private val resultsGrid = Grid<Map<String, Any?>>()
    private val filtersColumn = VerticalLayout()
    private val sidebar = LbiSidebarMenu()
    private lateinit var selectAreaMenuItem: MenuItem

    // ===== Barra azioni sorgente =====
    private val sourceStatusLabel = Span().apply { className = "lbi-source-status" }
    private val verifyButton = Button("Verifica sorgente")
    private val syncButton = Button("Sincronizza")
    private val sqlButton = Button("Mostra SQL view")
    private val actionBar = HorizontalLayout().apply {
        className = "lbi-action-bar"
        defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
        isPadding = false
        isSpacing = true
    }

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
        addAreaItem.addClickListener { openNewAnalysisWizard() }

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

        // ===== Sidebar: costruzione disaccoppiata tramite LbiSidebarMenu =====
        // La logica di business (i click handler) resta qui, dentro la view,
        // che continua ad avere accesso ai service applicativi. Il componente
        // sidebar riceve solo etichette + callback, non conosce i service.
        sidebar.setGroups(buildMenuGroups())

        buildActionBar()

        // ===== Grid risultati centrale =====
        resultsGrid.className = "lbi-results-grid"
        resultsGrid.setSizeFull()

        val centerArea = VerticalLayout(
            actionBar,
            Span("Risultati").apply { className = "lbi-section-title" },
            resultsGrid
        ).apply {
            className = "lbi-center"
            setSizeFull()
            isPadding = true
            setFlexGrow(0.0, actionBar)
            setFlexGrow(1.0, resultsGrid)
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

    // ================= Barra azioni sorgente =================

    /**
     * Verifica e sincronizzazione vivono qui, sulla pagina dell'Analisi, e
     * non in una voce di menu separata: non sono azioni di configurazione ma
     * operazioni sull'analisi che si sta guardando. La creazione della
     * sorgente resta interamente dentro il wizard "Nuova Analisi".
     *
     * Senza questi due comandi la catena era interrotta: una sorgente appena
     * creata resta PENDING_VIEW, EtlOrchestrator rifiuta di sincronizzare
     * qualunque cosa non sia VERIFIED, e nessun punto del programma invocava
     * runForArea. La tabella dei fatti restava quindi vuota per sempre.
     */
    private fun buildActionBar() {
        verifyButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
        verifyButton.addClickListener { verifySource() }

        syncButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY)
        syncButton.addClickListener { runEtl() }

        sqlButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY)
        sqlButton.addClickListener { showViewSql() }

        actionBar.add(sourceStatusLabel, verifyButton, sqlButton, syncButton)
    }

    private fun refreshSourceStatus() {
        val currentAreaId = areaId
        if (currentAreaId == null) {
            actionBar.isVisible = false
            return
        }
        val sources = areaSourceRepository.findByArea(currentAreaId)
        val source = sources.firstOrNull()

        actionBar.isVisible = true
        when {
            source == null -> {
                sourceStatusLabel.text = "Nessuna sorgente collegata"
                verifyButton.isEnabled = false
                syncButton.isEnabled = false
                sqlButton.isEnabled = false
            }
            source.status == SourceStatus.VERIFIED -> {
                sourceStatusLabel.text = "Sorgente verificata: ${source.config.viewName}"
                verifyButton.isEnabled = true
                syncButton.isEnabled = true
                sqlButton.isEnabled = true
            }
            source.status == SourceStatus.ERROR -> {
                sourceStatusLabel.text = "Sorgente in errore: ${source.errorDetail ?: "causa non registrata"}"
                verifyButton.isEnabled = true
                syncButton.isEnabled = false
                sqlButton.isEnabled = true
            }
            else -> {
                sourceStatusLabel.text = "View da creare sul database di origine (${source.config.viewName})"
                verifyButton.isEnabled = true
                // Sincronizzare una sorgente non verificata solleverebbe
                // comunque eccezione in EtlOrchestrator: meglio disabilitare
                // il comando che mostrare un errore dopo il click.
                syncButton.isEnabled = false
                sqlButton.isEnabled = true
            }
        }
    }

    private fun verifySource() {
        val currentAreaId = areaId ?: return
        val ui = ui.orElse(null) ?: return
        val scope = viewScope ?: return

        verifyButton.isEnabled = false
        sourceStatusLabel.text = "Verifica in corso..."

        // La verifica apre una connessione JDBC verso il database di origine:
        // eseguirla sul thread della UI bloccherebbe la sessione.
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

        syncButton.isEnabled = false
        sourceStatusLabel.text = "Sincronizzazione in corso..."

        scope.launch {
            try {
                sources.forEach { etlOrchestrator.runForArea(currentAreaId, it) }
                ui.access {
                    if (areaId != currentAreaId) return@access
                    Notification.show("Sincronizzazione completata", 4000, Notification.Position.BOTTOM_END)
                    refreshSourceStatus()
                    // Il dato è cambiato: dataVersion è stata incrementata da
                    // EtlCompletionService, quindi le chiavi di cache non
                    // corrispondono più e il refresh ricalcola davvero.
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

    /**
     * Mostra l'SQL della view attesa. Serve quando la verifica fallisce:
     * è il testo da consegnare al DBA, o da eseguire a mano sul database
     * di origine.
     */
    private fun showViewSql() {
        val currentAreaId = areaId ?: return
        val source = areaSourceRepository.findByArea(currentAreaId).firstOrNull() ?: return

        val attese = sourceVerificationService.expectedColumns(currentAreaId)
        val dialog = com.vaadin.flow.component.dialog.Dialog().apply {
            className = "lbi-wizard-dialog"
            headerTitle = "View attesa: ${source.config.viewName}"
            width = "760px"
        }
        val area = com.vaadin.flow.component.textfield.TextArea().apply {
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

    // ================= Wizard e menu =================

    /**
     * Apre il wizard unificato "Nuova Analisi" (discovery-first: connessione
     * sorgente + colonne reali con esempio dati, tutto in un solo flusso).
     */
    private fun openNewAnalysisWizard() {
        NewAnalysisWizardDialog(
            registryService, registryRepository, symbolTableService,
            areaSourceRepository, cryptoService, metadataService, viewSqlGenerator
        ) {
            currentAreas = registryRepository.findAllAree()
            rebuildAreaMenu()
            currentAreas.lastOrNull()?.let { switchArea(it.id) }
        }.open()
    }

    /**
     * Definisce i gruppi del menu sidebar.
     *
     * "Sorgenti Dati" come punto di CREAZIONE è stata rimossa dal menu: il
     * wizard "Nuova Analisi" la assorbe interamente. Verifica e
     * sincronizzazione vivono invece nella barra azioni della pagina, perché
     * riguardano l'analisi correntemente aperta e non una configurazione
     * globale.
     */
    private fun buildMenuGroups(): List<LbiSidebarMenu.MenuGroup> = listOf(
        LbiSidebarMenu.MenuGroup(
            label = "Analisi",
            entries = listOf(
                LbiSidebarMenu.MenuEntry("Nuova analisi") { openNewAnalysisWizard() }
            )
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
        refreshSourceStatus()
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

            // Una dimensione può comparire più volte nella stessa area con
            // ruoli diversi (data ordine e data consegna sulla stessa
            // dimensione Tempo): in quel caso il nome della dimensione da
            // solo non distingue le due schede, serve la colonna.
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

                // Le etichette si risolvono QUI, nel thread di background.
                //
                // Il motore associativo ragiona su value_id interi, ma le
                // listbox mostravano quegli interi tali e quali: l'utente
                // vedeva "47", "48", "51" al posto dei nomi dei clienti, e
                // non c'era modo di capire se gli stati verde/grigio fossero
                // corretti. La risoluzione richiede query su ClickHouse,
                // quindi non può stare dentro ui.access.
                val labels = resolveLabels(states)

                ui.access {
                    if (myRequestId != requestCounter.get()) return@access
                    if (areaId != currentAreaId) return@access
                    renderStates(states, labels)
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

    /** dimensioneId -> (value_id -> etichetta), per tutte le dimensioni visibili. */
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

            // Ordinamento per etichetta, non per id: i value_id seguono
            // l'ordine di primo caricamento nella symbol table, che non ha
            // nessun rapporto con l'ordine alfabetico atteso dall'utente.
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
}