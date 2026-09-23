package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.etl.EtlOrchestrator
import com.lightningbi.lightning_engine.model.Area
import com.lightningbi.lightning_engine.model.SourceStatus
import com.lightningbi.lightning_engine.model.UserPivotState
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.repository.UserPivotStateRepository
import com.lightningbi.lightning_engine.service.AggregateService
import com.lightningbi.lightning_engine.service.AssociativeStateFacade
import com.lightningbi.lightning_engine.service.ChartService
import com.lightningbi.lightning_engine.service.CryptoService
import com.lightningbi.lightning_engine.service.MetadataService
import com.lightningbi.lightning_engine.service.PermissionCheckService
import com.lightningbi.lightning_engine.service.PivotViewService
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
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.AfterNavigationObserver
import com.vaadin.flow.router.BeforeEvent
import com.vaadin.flow.router.HasUrlParameter
import com.vaadin.flow.router.OptionalParameter
import com.vaadin.flow.router.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import com.lightningbi.lightning_engine.service.AuthService

/**
 * Controller/Presenter: decide COSA succede quando, orchestrando Data
 * (query) e Ui (disegno). Non fa query direttamente (usa data), non
 * costruisce componenti Vaadin direttamente oltre a quelli necessari
 * per i propri dialog (wizard, conferme) - il grosso del disegno vive
 * in AssociativeExplorerUi.
 *
 * @Route("associative") risponde sia a /associative sia a
 * /associative/{id}. setParameter() si limita a memorizzare il
 * parametro; afterNavigation() (che gira dopo che la navigazione è
 * completamente avvenuta) innesca il vero caricamento.
 *
 * PIVOT: Righe/Colonne/Valori NON si costruiscono più qui. Vivono in
 * una PivotView (una per "foglio", condivisa per l'Area, gestite in
 * ConfigureAnalysisView) - questa vista legge sempre la vista ATTIVA
 * per l'utente corrente (PivotViewService.ensureActiveView) e la
 * ridisegna, ma non offre alcun drag&drop per modificarla.
 *
 * SELEZIONI: uniche e condivise per l'intera Area, indipendenti da
 * quale PivotView è attiva - comportamento Qlik confermato (le
 * selezioni sono a livello di app, non di foglio). Selezionare un
 * valore qui o in ConfigureAnalysisView è la stessa identica azione
 * sullo stesso stato. Non vengono MAI rimosse per il solo fatto che una
 * dimensione non è (più) in nessuna vista: restano attive e si
 * riflettono su griglia e grafici tramite la barra "selezioni attive",
 * sempre visibile, anche per campi non mostrati come card in questa
 * pagina.
 */
@Route("associative")
class AssociativeExplorerView(
    private val registryRepository: RegistryRepository,
    private val registryService: RegistryService,
    private val symbolTableService: SymbolTableService,
    private val areaSourceRepository: AreaSourceRepository,
    private val cryptoService: CryptoService,
    private val metadataService: MetadataService,
    private val viewSqlGenerator: ViewSqlGenerator,
    private val permissionCheckService: PermissionCheckService,
    private val userPivotStateRepository: UserPivotStateRepository,
    private val pivotViewService: PivotViewService,
    associativeStateService: AssociativeStateFacade,
    aggregateService: AggregateService,
    chartService: ChartService,
    versionService: VersionService,
    symbolLookupService: SymbolLookupService,
    sourceVerificationService: SourceVerificationService,
    private val authService: AuthService,
    etlOrchestrator: EtlOrchestrator
) : VerticalLayout(), HasUrlParameter<String>, AfterNavigationObserver, com.vaadin.flow.router.BeforeLeaveObserver {

    private val data = AssociativeExplorerData(
        registryRepository, areaSourceRepository, sourceVerificationService,
        associativeStateService, aggregateService, chartService,
        versionService, symbolLookupService, etlOrchestrator
    )

    private val ui = AssociativeExplorerUi(
        onFilterSelectionChanged = { dimId, values -> onFilterSelectionChanged(dimId, values) },
        onRemoveSelection = { dimId, valueId -> onRemoveSelection(dimId, valueId) }
    )

    private var areaId: UUID? = null
    private var pendingParameter: String? = null

    private val selections = mutableMapOf<UUID, Set<Long>>()
    private val dimensionNames = mutableMapOf<UUID, String>()
    private val dimensionColumns = mutableMapOf<UUID, String>()

    private var pivotRows: List<UUID> = emptyList()
    private var pivotColumns: List<UUID> = emptyList()
    private var pivotValues: List<UUID> = emptyList()

    private val requestCounter = AtomicLong(0)

    private lateinit var shell: LbiAppShell

    private var sourceStatus: SourceStatus? = null
    private var hasSource: Boolean = false

    private var viewScope: CoroutineScope? = null
    private var currentAreas: List<Area> = emptyList()

    init {
        setSizeFull()
        isPadding = false
        isSpacing = false

        currentAreas = data.findAllAree()

        shell = LbiAppShell(buildMenuGroups(), ui.root, authService)
        add(shell)
        setFlexGrow(1.0, shell)
    }

    override fun setParameter(event: BeforeEvent, @OptionalParameter parameter: String?) {
        pendingParameter = parameter
    }

    override fun afterNavigation(event: AfterNavigationEvent) {
        val requestedId = pendingParameter?.let {
            try { UUID.fromString(it) } catch (e: IllegalArgumentException) { null }
        }
        if (requestedId != null) {
            switchArea(requestedId)
        } else if (currentAreas.isNotEmpty() && areaId == null) {
            switchArea(currentAreas.first().id)
        } else if (areaId != null) {
            // Si torna su questa pagina (es. da ConfigureAnalysisView):
            // la PivotView attiva o le selezioni potrebbero essere
            // cambiate nel frattempo, va sempre riletto lo stato fresco.
            reloadActiveViewAndRefresh(areaId!!)
        }
    }

    override fun beforeLeave(event: com.vaadin.flow.router.BeforeLeaveEvent) {
        // Nessuna modifica pivot pendente da salvare qui: la struttura si
        // modifica solo in ConfigureAnalysisView, che salva ad ogni
        // cambiamento. Questa pagina non ha più nulla da chiedere prima
        // di lasciarla.
    }

    // ================= Sidebar =================

    private fun buildMenuGroups(): List<LbiSidebarMenu.MenuGroup> {
        val currentAreaId = areaId
        val groups = mutableListOf(
            LbiSidebarMenu.MenuGroup(
                label = "Analisi",
                entries = currentAreas.map { area ->
                    LbiSidebarMenu.MenuEntry(area.nome) { switchArea(area.id) }
                } + LbiSidebarMenu.MenuEntry("+ Nuova analisi") { openNewAnalysisWizard() },
                active = true
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Configura Analisi",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Righe, Colonne, Valori", enabled = currentAreaId != null) {
                        navigateToConfigure(currentAreaId)
                    }
                )
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Gestisci",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Verifica sorgente", enabled = hasSource) { verifySource() },
                    LbiSidebarMenu.MenuEntry("Mostra SQL view", enabled = hasSource) { showViewSql() },
                    LbiSidebarMenu.MenuEntry("Sincronizza", enabled = hasSource && sourceStatus == SourceStatus.VERIFIED) { runEtl() },
                    LbiSidebarMenu.MenuEntry("Modifica dimensioni", enabled = currentAreaId != null) { openEditDimensions() },
                    LbiSidebarMenu.MenuEntry("Modifica metriche", enabled = currentAreaId != null) { openEditMetrics() },
                    LbiSidebarMenu.MenuEntry("Elimina analisi", enabled = currentAreaId != null) { confirmDeleteArea() }
                )
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Grafici",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Gestisci grafici", enabled = currentAreaId != null) {
                        navigateToCharts(currentAreaId)
                    }
                )
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Report",
                entries = listOf(LbiSidebarMenu.MenuEntry("Stampe") { Notification.show("Funzione in arrivo") })
            )
        )

        // "Amministrazione" compare SOLO per chi ha il permesso MANAGE_USERS
        // sul proprio ruolo corrente - non un nome di ruolo fisso, coerente
        // col modello a permessi granulari. Stesso controllo viene rifatto
        // dentro AdminView.beforeEnter, perché l'URL /admin resta
        // raggiungibile a mano anche se la voce di menu è nascosta qui.
        val currentUser = CurrentUserHolder.get()
        if (currentUser != null && permissionCheckService.hasPermission(currentUser.roleName, "MANAGE_USERS")) {
            groups.add(
                LbiSidebarMenu.MenuGroup(
                    label = "Amministrazione",
                    entries = listOf(
                        LbiSidebarMenu.MenuEntry("Gestione utenti") {
                            getUI().ifPresent { it.navigate(AdminView::class.java) }
                        }
                    )
                )
            )
        }

        return groups
    }

    private fun refreshSidebar() {
        shell.updateMenuGroups(buildMenuGroups())
        val nome = currentAreas.find { it.id == areaId }?.nome
        shell.updateAnalysisName(nome?.let { "Analisi: $it" })
    }

    private fun navigateToCharts(currentAreaId: UUID?) {
        if (currentAreaId == null) return
        getUI().ifPresent { it.navigate(ChartsView::class.java, currentAreaId.toString()) }
    }

    private fun navigateToConfigure(currentAreaId: UUID?) {
        if (currentAreaId == null) return
        getUI().ifPresent { it.navigate(ConfigureAnalysisView::class.java, currentAreaId.toString()) }
    }

    // ================= Sorgente =================

    private fun refreshSourceStatus() {
        val currentAreaId = areaId
        if (currentAreaId == null) {
            hasSource = false
            sourceStatus = null
            shell.updateSourceStatus("")
            refreshSidebar()
            return
        }

        val source = data.findSourceByArea(currentAreaId)
        hasSource = source != null
        sourceStatus = source?.status

        shell.updateSourceStatus(
            when {
                source == null -> "Nessuna sorgente collegata"
                source.status == SourceStatus.VERIFIED -> "Sorgente verificata: ${source.config.viewName}"
                source.status == SourceStatus.ERROR -> "Sorgente in errore: ${source.errorDetail ?: "causa non registrata"}"
                else -> "View da creare sul database di origine (${source.config.viewName})"
            }
        )
        refreshSidebar()
    }

    private fun verifySource() {
        val currentAreaId = areaId ?: return
        val vaadinUi = getUI().orElse(null) ?: return
        val scope = viewScope ?: return

        shell.updateSourceStatus("Verifica in corso...")

        scope.launch {
            val results = try {
                data.verifySource(currentAreaId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vaadinUi.access {
                    Notification.show("Verifica fallita: ${e.message}", 6000, Notification.Position.MIDDLE)
                    refreshSourceStatus()
                }
                return@launch
            }
            vaadinUi.access {
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
        val vaadinUi = getUI().orElse(null) ?: return
        val scope = viewScope ?: return
        val sources = data.findSourcesByArea(currentAreaId)
        if (sources.isEmpty()) {
            Notification.show("Nessuna sorgente da sincronizzare")
            return
        }

        shell.updateSourceStatus("Sincronizzazione in corso...")

        scope.launch {
            try {
                sources.forEach { data.runEtl(currentAreaId, it) }
                vaadinUi.access {
                    if (areaId != currentAreaId) return@access
                    Notification.show("Sincronizzazione completata", 4000, Notification.Position.BOTTOM_END)
                    refreshSourceStatus()
                    refresh()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vaadinUi.access {
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
        val source = data.findSourceByArea(currentAreaId) ?: return

        val attese = data.expectedColumns(currentAreaId, source.config.syncMode)
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
    private fun openEditDimensions() {
        val currentAreaId = areaId ?: return
        EditDimensionsDialog(
            areaId = currentAreaId,
            registryService = registryService,
            areaSourceRepository = areaSourceRepository,
            cryptoService = cryptoService,
            metadataService = metadataService
        ) {
            refresh()
        }.open()
    }
    // ================= Metriche / Eliminazione =================

    private fun openEditMetrics() {
        val currentAreaId = areaId ?: return
        EditMetricsDialog(currentAreaId, registryService) {
            refresh()
        }.open()
    }

    private fun confirmDeleteArea() {
        val currentAreaId = areaId ?: return
        val areaNome = currentAreas.find { it.id == currentAreaId }?.nome ?: "questa analisi"

        val dialog = Dialog().apply {
            headerTitle = "Eliminare \"$areaNome\"?"
            width = "440px"
        }
        dialog.add(Span("L'analisi, le sue metriche, i collegamenti alle dimensioni e i dati caricati verranno rimossi. L'operazione non è reversibile."))
        val cancelButton = Button("Annulla") { dialog.close() }
        val confirmButton = Button("Elimina") {
            performDeleteArea(currentAreaId)
            dialog.close()
        }
        dialog.footer.add(cancelButton, confirmButton)
        dialog.open()
    }

    private fun performDeleteArea(targetAreaId: UUID) {
        try {
            registryService.deleteAreaCompleta(targetAreaId)
        } catch (e: Exception) {
            Notification.show("Errore durante l'eliminazione: ${e.message}", 8000, Notification.Position.MIDDLE)
        }
        currentAreas = data.findAllAree()
        if (areaId == targetAreaId) {
            areaId = null
            resetAreaState()
            currentAreas.firstOrNull()?.let { switchArea(it.id) } ?: refreshSourceStatus()
        } else {
            refreshSidebar()
        }
        Notification.show("Analisi eliminata", 4000, Notification.Position.BOTTOM_END)
    }

    private fun openNewAnalysisWizard() {
        NewAnalysisWizardDialog(
            registryService, registryRepository, symbolTableService,
            areaSourceRepository, cryptoService, metadataService, viewSqlGenerator
        ) {
            currentAreas = data.findAllAree()
            currentAreas.lastOrNull()?.let { switchArea(it.id) } ?: refreshSidebar()
        }.open()
    }

    // ================= Ciclo di vita =================

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        attachEvent.ui.page.addJavaScript("js/echarts.min.js")
        if (viewScope == null) {
            viewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
    }

    override fun onDetach(detachEvent: DetachEvent) {
        viewScope?.cancel()
        viewScope = null
        super.onDetach(detachEvent)
    }

    // ================= Cambio area =================

    private fun resetAreaState() {
        selections.clear()
        dimensionNames.clear()
        dimensionColumns.clear()
        pivotRows = emptyList()
        pivotColumns = emptyList()
        pivotValues = emptyList()
        ui.clearAll()
    }

    private fun switchArea(newAreaId: UUID) {
        areaId = newAreaId
        resetAreaState()

        data.findDimensioniByArea(newAreaId).forEach { ad ->
            data.findDimensione(ad.dimensioneId)?.let { dim ->
                dimensionNames[ad.dimensioneId] = dim.nome
                dimensionColumns[ad.dimensioneId] = ad.colonnaFisica
            }
        }
        refreshSourceStatus()
        reloadActiveViewAndRefresh(newAreaId)
    }

    /**
     * Rilegge la PivotView attiva per l'utente su quest'area (creandone
     * una di default se l'area non ne ha ancora nessuna - vedi
     * PivotViewService.ensureActiveView) e le selezioni correnti, poi
     * ridisegna. Punto unico richiamato sia al cambio area sia al
     * ritorno da ConfigureAnalysisView, perché entrambe le cose possono
     * essere cambiate nel frattempo in un'altra pagina.
     */
    private fun reloadActiveViewAndRefresh(currentAreaId: UUID) {
        val currentUser = CurrentUserHolder.get() ?: return

        val activeView = pivotViewService.ensureActiveView(currentUser.userId, currentAreaId)
        pivotRows = activeView.pivotRows
        pivotColumns = activeView.pivotColumns
        pivotValues = activeView.pivotValues

        selections.clear()
        userPivotStateRepository.find(currentUser.userId, currentAreaId)?.let { state ->
            selections.putAll(state.selections)
        }

        rebuildFilterCards(pivotRows, pivotColumns)
        refreshSidebar()
        refresh()
    }

    // ================= Filtri =================

    private fun rebuildFilterCards(rows: List<UUID>, columns: List<UUID>) {
        ui.rebuildFilterCards(
            rowDims = rows,
            columnDims = columns,
            dimensionNames = dimensionNames,
            columnFor = { dimId -> dimensionColumns[dimId] },
            countSameName = { name -> (rows + columns).count { dimensionNames[it] == name } }
        )
    }

    /**
     * Le selezioni sono uniche e condivise per l'Area: qui vengono solo
     * salvate su UserPivotState e applicate al refresh, MAI cancellate
     * per il fatto che una dimensione non è (più) nella vista attiva -
     * comportamento Qlik confermato (le selezioni valgono su tutta
     * l'app, non sul singolo foglio).
     */
    private fun onFilterSelectionChanged(dimId: UUID, values: Set<Long>) {
        selections[dimId] = values
        persistSelections()
        refresh()
    }

    private fun onRemoveSelection(dimId: UUID, valueId: Long) {
        val current = selections[dimId]?.toMutableSet() ?: return
        current.remove(valueId)
        if (current.isEmpty()) selections.remove(dimId) else selections[dimId] = current
        ui.deselectValue(dimId, valueId)
        persistSelections()
        refresh()
    }

    private fun persistSelections() {
        val currentAreaId = areaId ?: return
        val currentUser = CurrentUserHolder.get() ?: return
        val current = userPivotStateRepository.find(currentUser.userId, currentAreaId)
        userPivotStateRepository.save(
            UserPivotState(
                userId = currentUser.userId,
                areaId = currentAreaId,
                activeViewId = current?.activeViewId,
                selections = selections.filterValues { it.isNotEmpty() }
            )
        )
    }

    // ================= Refresh =================

    /**
     * Punto unico di ricalcolo. Apre il dialog di caricamento, delega a
     * data.refresh() il lavoro vero (query), poi delega a ui.render*()
     * il disegno del risultato. Non fa mai query né costruisce
     * componenti direttamente: coordina solo.
     */
    private fun refresh() {
        val currentAreaId = areaId ?: return
        val vaadinUi = getUI().orElse(null) ?: return
        val scope = viewScope ?: return
        val myRequestId = requestCounter.incrementAndGet()

        ui.loadingDialog.open()

        val selectionsSnapshot = selections.filterValues { it.isNotEmpty() }.mapValues { it.value.toSet() }
        val rowsSnapshot = pivotRows
        val columnsSnapshot = pivotColumns
        val valuesSnapshot = pivotValues

        scope.launch {
            try {
                val result = data.refresh(currentAreaId, rowsSnapshot, columnsSnapshot, valuesSnapshot, selectionsSnapshot, dimensionNames)

                vaadinUi.access {
                    if (myRequestId != requestCounter.get()) return@access
                    if (areaId != currentAreaId) return@access
                    ui.renderActiveSelections(selectionsSnapshot, dimensionNames, result.labels, data::labelOrFallback)
                    ui.renderStates(result.states, result.labels, data::labelOrFallback) { dimId -> dimensionColumns[dimId] }
                    ui.renderResultsGrid(result.aggregates, result.rowHierarchy, rowsSnapshot, dimensionNames)
                    ui.renderCharts(result.chartsData, rowsSnapshot)
                    ui.loadingDialog.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vaadinUi.access {
                    if (myRequestId != requestCounter.get()) return@access
                    ui.loadingDialog.close()
                    Notification.show("Errore aggiornamento: ${e.message}", 5000, Notification.Position.BOTTOM_END)
                }
            }
        }
    }
}