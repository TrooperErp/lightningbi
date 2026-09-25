package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.etl.EtlOrchestrator
import com.lightningbi.lightning_engine.model.Area
import com.lightningbi.lightning_engine.model.PivotView
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
import com.lightningbi.lightning_engine.service.EmailService
import com.vaadin.flow.component.dependency.Uses
import com.vaadin.flow.component.icon.Icon

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
 * GERARCHIA (Dataset -> Analisi): "Area" è il Dataset (i dati grezzi
 * sincronizzati, derivati da una view sul DB origine); ogni Dataset ha
 * N PivotView, che qui sono presentate come "Analisi" - ognuna con la
 * sua propria struttura Righe/Colonne/Valori, scelta liberamente tra i
 * campi del Dataset (un'Analisi può usare 3 campi, un'altra Analisi
 * sullo stesso Dataset può usarne 3 completamente diversi).
 *
 * PIVOT: il PivotPanel vive DENTRO questa pagina (non più su una
 * pagina "Configura Analisi" separata): trascinare un campo in Righe/
 * Colonne/Valori aggiorna subito AggregateService (query leggera), e
 * salva subito la struttura sulla PivotView attiva. Non serve un
 * bottone "Applica": cambiare la struttura del pivot NON tocca il
 * motore associativo (quello pesante), tocca solo l'aggregazione.
 *
 * SELEZIONI: uniche e condivise per l'intero Dataset (Area), indipendenti
 * da quale Analisi (PivotView) è attiva - comportamento Qlik confermato
 * (le selezioni sono a livello di app, non di singolo foglio). Non
 * vengono MAI rimosse per il solo fatto che una dimensione non è (più)
 * in Righe/Colonne dell'Analisi corrente.
 */
import com.vaadin.flow.spring.annotation.UIScope

@Route("associative")
@Uses(Icon::class)

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
    private val emailService: EmailService,
    etlOrchestrator: EtlOrchestrator
) : VerticalLayout(), HasUrlParameter<String>, AfterNavigationObserver, com.vaadin.flow.router.BeforeLeaveObserver {

    private val data = AssociativeExplorerData(
        registryRepository, areaSourceRepository, sourceVerificationService,
        associativeStateService, aggregateService, chartService,
        versionService, symbolLookupService, etlOrchestrator
    )

    private val ui = AssociativeExplorerUi(
        onFilterSelectionChanged = { dimId, values -> onFilterSelectionChanged(dimId, values) },
        onRemoveSelection = { dimId, valueId -> onRemoveSelection(dimId, valueId) },
        onPivotChanged = { rows, columns, values -> onPivotChanged(rows, columns, values) }
    )

    private var areaId: UUID? = null
    private var pendingParameter: String? = null

    private val selections = mutableMapOf<UUID, Set<Long>>()
    private val dimensionNames = mutableMapOf<UUID, String>()
    private val dimensionColumns = mutableMapOf<UUID, String>()

    private var pivotRows: List<UUID> = emptyList()
    private var pivotColumns: List<UUID> = emptyList()
    private var pivotValues: List<UUID> = emptyList()

    /** Le Analisi (PivotView) del Dataset corrente e quella attiva in questo momento. */
    private var analyses: List<PivotView> = emptyList()
    private var activeView: PivotView? = null

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
        }
    }


//==========================================================================================================================
    override fun beforeLeave(event: com.vaadin.flow.router.BeforeLeaveEvent) {
        // Il pivot si salva ad ogni modifica (PivotViewService.updatePivot),
        // le selezioni ad ogni click (persistSelections): nessuna modifica
        // pendente da confermare prima di lasciare la pagina.
    }

    // ================= Sidebar =================

    /**
     * Gruppo "Dataset": elenco delle Aree (i Dataset), invariato nel
     * meccanismo (switchArea), solo l'etichetta riflette la nuova
     * terminologia.
     *
     * Gruppo "Analisi": elenco delle PivotView del Dataset corrente. Ogni
     * voce chiama switchToAnalysis(view.id) per rendere quella vista
     * attiva senza cambiare Dataset. "+ Nuova Analisi" crea una PivotView
     * vuota e la apre subito.
     */
    private fun buildMenuGroups(): List<LbiSidebarMenu.MenuGroup> {
        val currentAreaId = areaId
        val groups = mutableListOf(
            LbiSidebarMenu.MenuGroup(
                label = "Dataset",
                entries = currentAreas.map { area ->
                    LbiSidebarMenu.MenuEntry(area.nome) { switchArea(area.id) }
                } + listOf(
                    LbiSidebarMenu.MenuEntry("+ Nuovo dataset") { openNewAnalysisWizard() },
                    LbiSidebarMenu.MenuEntry("Modifica Schema", enabled = currentAreaId != null) {
                        if (currentAreaId != null) getUI().ifPresent { it.navigate(EditFieldsView::class.java, currentAreaId.toString()) }
                    },
                    LbiSidebarMenu.MenuEntry("Sincronizza", enabled = hasSource && sourceStatus == SourceStatus.VERIFIED) { runEtl() }
                ),
                active = true
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Analisi di ${currentAreas.find { it.id == currentAreaId }?.nome ?: ""}",
                entries = if (currentAreaId != null) {
                    analyses.map { view ->
                        LbiSidebarMenu.MenuEntry(view.nome) { switchToAnalysis(view.id) }
                    } + listOf(
                        LbiSidebarMenu.MenuEntry("+ Nuova Analisi") { createNewAnalysis(currentAreaId) },
                        LbiSidebarMenu.MenuEntry("Elimina Analisi corrente", enabled = activeView != null) {
                            activeView?.let { deleteAnalysis(it.id) }
                        }
                    )
                } else emptyList()
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
        // sul proprio ruolo corrente. Raggruppa "Connessioni" (verifica
        // sorgente, SQL view, eliminazione dataset - operazioni sulla
        // connessione al DB origine, non sul contenuto analitico) e
        // "Gestione utenti". Stesso controllo viene rifatto dentro
        // AdminView.beforeEnter, perché l'URL /admin resta raggiungibile a
        // mano anche se la voce di menu è nascosta qui.
        val currentUser = CurrentUserHolder.get()
        if (currentUser != null && permissionCheckService.hasPermission(currentUser.roleName, "MANAGE_USERS")) {
            groups.add(
                LbiSidebarMenu.MenuGroup(
                    label = "Amministrazione",
                    entries = listOf(
                        LbiSidebarMenu.MenuEntry("Verifica sorgente", enabled = hasSource) { verifySource() },
                        LbiSidebarMenu.MenuEntry("Mostra SQL view", enabled = hasSource) { showViewSql() },
                        LbiSidebarMenu.MenuEntry("Elimina dataset", enabled = currentAreaId != null) { confirmDeleteArea() },
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
        val nomeArea = currentAreas.find { it.id == areaId }?.nome
        val nomeAnalisi = activeView?.nome
        shell.updateAnalysisName(
            when {
                nomeArea != null && nomeAnalisi != null -> "$nomeArea · $nomeAnalisi"
                nomeArea != null -> nomeArea
                else -> null
            }
        )
    }

    private fun navigateToCharts(currentAreaId: UUID?) {
        if (currentAreaId == null) return
        val viewId = activeView?.id
        val param = if (viewId != null) "$currentAreaId,$viewId" else currentAreaId.toString()
        getUI().ifPresent { it.navigate(ChartsView::class.java, param) }
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
            refreshPivotFields(currentAreaId)
            refresh()
        }.open()
    }
    // ================= Metriche / Eliminazione =================

    private fun openEditMetrics() {
        val currentAreaId = areaId ?: return
        EditMetricsDialog(currentAreaId, registryService) {
            refreshPivotFields(currentAreaId)
            refresh()
        }.open()
    }

    private fun confirmDeleteArea() {
        val currentAreaId = areaId ?: return
        val areaNome = currentAreas.find { it.id == currentAreaId }?.nome ?: "questo dataset"

        val dialog = Dialog().apply {
            headerTitle = "Eliminare \"$areaNome\"?"
            width = "440px"
        }
        dialog.add(Span("Il dataset, le sue metriche, i collegamenti alle dimensioni e i dati caricati verranno rimossi. L'operazione non è reversibile."))
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
        Notification.show("Dataset eliminato", 4000, Notification.Position.BOTTOM_END)
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

        // DEBUG TEMPORANEO: cattura qualsiasi eccezione lato server non
        // gestita durante il ciclo di vita della UI, per capire se il
        // logout imprevisto è causato da un crash silenzioso in una delle
        // classi Ui (FilterCardsUi/ResultsGridUi/ChartsPanelUi).
        attachEvent.ui.session.errorHandler = com.vaadin.flow.server.ErrorHandler { event ->
            org.slf4j.LoggerFactory.getLogger("LBI-UI-ERROR").error("Errore UI non gestito", event.throwable)
        }

        if (viewScope == null) {
            viewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        ui.pivotPanel.ensureDropTargetsAttached()
    }

    override fun onDetach(detachEvent: DetachEvent) {
        viewScope?.cancel()
        viewScope = null
        super.onDetach(detachEvent)
    }

    // ================= Cambio Dataset (Area) =================

    private fun resetAreaState() {
        selections.clear()
        dimensionNames.clear()
        dimensionColumns.clear()
        pivotRows = emptyList()
        pivotColumns = emptyList()
        pivotValues = emptyList()
        analyses = emptyList()
        activeView = null
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
        refreshPivotFields(newAreaId)
        refreshSourceStatus()
        reloadActiveViewAndRefresh(newAreaId)
    }

    /** Popola il PivotPanel con tutti i campi (dimensioni+metriche) del Dataset corrente. */
    private fun refreshPivotFields(currentAreaId: UUID) {
        val dims = data.findDimensioniByArea(currentAreaId)
            .mapNotNull { ad -> data.findDimensione(ad.dimensioneId)?.let { ad.dimensioneId to it.nome } }
        val metriche = data.findMetricheByArea(currentAreaId).map { it.id to it.nome }
        ui.pivotPanel.setFieldsWithIds(dims, metriche)
    }

    // ================= Cambio Analisi (PivotView) =================

    /** Rende attiva una diversa Analisi (PivotView) dello stesso Dataset, senza cambiare Area. */
    private fun switchToAnalysis(viewId: UUID) {
        val currentAreaId = areaId ?: return
        val currentUser = CurrentUserHolder.get() ?: return
        pivotViewService.setActiveView(currentUser.userId, currentAreaId, viewId)
        reloadActiveViewAndRefresh(currentAreaId)
    }

    /** Crea una nuova Analisi (PivotView) vuota sul Dataset corrente e la apre subito. */
    private fun createNewAnalysis(currentAreaId: UUID) {
        val currentUser = CurrentUserHolder.get() ?: return
        pivotViewService.createView(currentUser.userId, currentAreaId, "Analisi ${analyses.size + 1}")
        reloadActiveViewAndRefresh(currentAreaId)
    }

    /**
     * Rilegge le PivotView del Dataset e quella ATTIVA per l'utente
     * corrente (creandone una di default se il Dataset non ne ha ancora
     * nessuna - vedi PivotViewService.ensureActiveView), popola il
     * PivotPanel con quella struttura, rilegge le selezioni (uniche per
     * Dataset), e ridisegna. Punto unico richiamato sia al cambio
     * Dataset sia al cambio Analisi.
     */
    private fun reloadActiveViewAndRefresh(currentAreaId: UUID) {
        val currentUser = CurrentUserHolder.get() ?: return

        analyses = pivotViewService.findByArea(currentAreaId)
        val active = pivotViewService.ensureActiveView(currentUser.userId, currentAreaId)
        analyses = pivotViewService.findByArea(currentAreaId)
        activeView = active

        // DEBUG TEMPORANEO: verifica che l'Analisi attiva erediti i campi
        // del Dataset corretto.
        println("DEBUG ANALISI: areaId=$currentAreaId activeView.id=${active.id} activeView.nome=${active.nome} activeView.areaId=${active.areaId}")

        pivotRows = active.pivotRows
        pivotColumns = active.pivotColumns
        pivotValues = active.pivotValues
        ui.pivotPanel.restoreState(pivotRows, pivotColumns, pivotValues)

        selections.clear()
        userPivotStateRepository.find(currentUser.userId, currentAreaId)?.let { state ->
            selections.putAll(state.selections)
        }

        rebuildFilterCards(pivotRows, pivotColumns)
        refreshSidebar()
        refresh()
    }

    // ================= Pivot =================

    /**
     * Il PivotPanel ha cambiato Righe/Colonne/Valori: salva subito sulla
     * PivotView attiva (persistenza immediata, nessun bottone "Applica" -
     * cambiare la struttura non tocca il motore associativo, solo
     * AggregateService), aggiorna lo stato locale, ricostruisce le card
     * filtro (i campi in Righe/Colonne sono cambiati) e ricalcola.
     */
    private fun onPivotChanged(rows: List<UUID>, columns: List<UUID>, values: List<UUID>) {
        val currentView = activeView ?: return
        activeView = pivotViewService.updatePivot(currentView, rows, columns, values)
        pivotRows = rows
        pivotColumns = columns
        pivotValues = values
        rebuildFilterCards(rows, columns)
        refresh()
    }

    // ================= Filtri =================

    private fun rebuildFilterCards(rows: List<UUID>, columns: List<UUID>) {
        val hiddenDims = mutableSetOf<UUID>()
        // Se l'utente è monotenant (Ditta assegnata), nascondi la dimensione
        // il cui campo fisico è "codice_ditta": il valore è fisso dal
        // profilo, non deve comparire come scelta.
        val currentUser = CurrentUserHolder.get()
        if (currentUser?.codiceDittaAssegnata != null) {
            dimensionColumns.entries.find { it.value == "codice_ditta" }?.let { hiddenDims.add(it.key) }
        }

        ui.rebuildFilterCards(
            allDimIds = dimensionNames.keys.toList(),
            dimensionNames = dimensionNames,
            columnFor = { dimId -> dimensionColumns[dimId] },
            countSameName = { name -> dimensionNames.values.count { it == name } },
            hiddenDimIds = hiddenDims
        )
    }

    /**
     * Le selezioni sono uniche e condivise per il Dataset (Area): qui
     * vengono solo salvate su UserPivotState e applicate al refresh, MAI
     * cancellate per il fatto che una dimensione non è (più) nell'Analisi
     * attiva - comportamento Qlik confermato (le selezioni valgono su
     * tutta l'app, non sul singolo foglio).
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
                    // Il dialog va sempre chiuso quando UNA richiesta qualsiasi
                    // torna, anche se superata da una più recente: altrimenti,
                    // se una richiesta più vecchia non arriva mai a chiuderlo (o
                    // arriva dopo la sua "vittoria" su un'altra), il dialog
                    // resta aperto indefinitamente. Solo l'aggiornamento della
                    // UI (griglia/stati/grafici) va scartato se superato.
                    ui.loadingDialog.close()

                    if (myRequestId != requestCounter.get()) return@access
                    if (areaId != currentAreaId) return@access
                    ui.renderStates(result.states, result.labels, data::labelOrFallback) { dimId -> dimensionColumns[dimId] }
                    ui.renderResultsGrid(result.aggregates, result.rowHierarchy, rowsSnapshot, dimensionNames)
                    ui.renderCharts(result.chartsData, rowsSnapshot)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vaadinUi.access {
                    ui.loadingDialog.close()
                    if (myRequestId != requestCounter.get()) return@access
                    Notification.show("Errore aggiornamento: ${e.message}", 5000, Notification.Position.MIDDLE)
                }
            }
        }
    }


    private fun deleteAnalysis(viewId: UUID) {
        val currentAreaId = areaId ?: return
        val view = analyses.find { it.id == viewId } ?: return

        val dialog = Dialog().apply {
            headerTitle = "Eliminare \"${view.nome}\"?"
            width = "440px"
        }
        dialog.add(Span("L'Analisi e la sua configurazione di Righe/Colonne/Valori verranno eliminate. L'operazione non è reversibile."))
        val cancelButton = Button("Annulla") { dialog.close() }
        val confirmButton = Button("Elimina") {
            val ok = pivotViewService.deleteView(currentAreaId, viewId)
            dialog.close()
            if (!ok) {
                Notification.show("Non puoi eliminare l'ultima Analisi rimasta del Dataset")
            } else {
                reloadActiveViewAndRefresh(currentAreaId)
            }
        }
        dialog.footer.add(cancelButton, confirmButton)
        dialog.open()
    }
}