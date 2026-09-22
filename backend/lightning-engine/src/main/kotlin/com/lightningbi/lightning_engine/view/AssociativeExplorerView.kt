package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.etl.EtlOrchestrator
import com.lightningbi.lightning_engine.model.Area
import com.lightningbi.lightning_engine.model.SourceStatus
import com.lightningbi.lightning_engine.model.UserPivotState
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.repository.UserPivotStateRepository
import com.lightningbi.lightning_engine.service.AggregateService
import com.lightningbi.lightning_engine.service.AssociativeStateService
import com.lightningbi.lightning_engine.service.ChartService
import com.lightningbi.lightning_engine.service.CryptoService
import com.lightningbi.lightning_engine.service.MetadataService
import com.lightningbi.lightning_engine.service.PermissionCheckService
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
 * Persistenza pivot: lo stato del pivot (Righe, Colonne, Valori,
 * selezioni) può essere salvato esplicitamente per utente+area
 * (UserPivotStateRepository), distinto dallo stato "in transito"
 * verso/da ChartsView (AnalysisWorkStateHolder, sessione Vaadin,
 * non persistito). Ogni modifica al pivot marca hasUnsavedChanges;
 * cambiare area con modifiche pendenti chiede conferma.
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
    associativeStateService: AssociativeStateService,
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

    private var hasUnsavedChanges: Boolean = false

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

    override fun beforeLeave(event: com.vaadin.flow.router.BeforeLeaveEvent) {
        if (!hasUnsavedChanges) return

        val continuation = event.postpone()
        val dialog = Dialog().apply {
            headerTitle = "Modifiche non salvate"
            width = "440px"
            isCloseOnEsc = false
            isCloseOnOutsideClick = false
            addDialogCloseActionListener {
                continuation.cancel()
                close()
            }
        }
        dialog.add(Span("Hai modifiche al pivot non ancora salvate. Vuoi salvarle prima di uscire?"))

        val cancelButton = Button("Annulla") {
            dialog.close()
            continuation.cancel()
        }
        val discardButton = Button("Scarta modifiche") {
            markSaved()
            dialog.close()
            continuation.proceed()
        }
        val saveButton = Button("Salva ed esci") {
            savePivotState()
            dialog.close()
            continuation.proceed()
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        dialog.footer.add(cancelButton, discardButton, saveButton)
        dialog.open()
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
                label = "Gestisci",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Salva vista", enabled = hasUnsavedChanges) { savePivotState() },
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
        AnalysisWorkStateHolder.save(
            AnalysisWorkState(
                areaId = currentAreaId,
                pivotRows = pivotRows,
                pivotColumns = pivotColumns,
                pivotValues = pivotValues,
                selections = selections.toMap()
            )
        )
        getUI().ifPresent { it.navigate(ChartsView::class.java, currentAreaId.toString()) }
    }

    // ================= Persistenza pivot =================

    /**
     * Marca lo stato pivot corrente come "non salvato" e rinfresca la
     * sidebar cosi' la voce "Salva vista" diventa cliccabile. Chiamato
     * da ogni punto che cambia pivotRows/pivotColumns/pivotValues/
     * selections.
     */
    private fun markUnsaved() {
        hasUnsavedChanges = true
        refreshSidebar()
    }

    private fun markSaved() {
        hasUnsavedChanges = false
        refreshSidebar()
    }

    /**
     * Salva lo stato corrente del pivot (Righe, Colonne, Valori,
     * selezioni) per l'utente loggato su quest'area. Una riga sola per
     * (utente, area): sovrascrive il salvataggio precedente, nessuno
     * storico di versioni.
     */
    private fun savePivotState() {
        val currentAreaId = areaId ?: return
        val currentUser = CurrentUserHolder.get() ?: run {
            Notification.show("Nessun utente autenticato: impossibile salvare", 4000, Notification.Position.MIDDLE)
            return
        }
        userPivotStateRepository.save(
            UserPivotState(
                userId = currentUser.userId,
                areaId = currentAreaId,
                pivotRows = pivotRows,
                pivotColumns = pivotColumns,
                pivotValues = pivotValues,
                selections = selections.filterValues { it.isNotEmpty() }
            )
        )
        markSaved()
        Notification.show("Vista salvata", 3000, Notification.Position.BOTTOM_END)
    }

    /**
     * Se ci sono modifiche non salvate, chiede conferma prima di
     * procedere (es. cambio area). onProceed viene eseguito solo se
     * l'utente sceglie "Salva ed esci" o "Scarta modifiche"; "Annulla"
     * non fa nulla, l'utente resta dove si trovava.
     */
    private fun confirmDiscardIfNeeded(onProceed: () -> Unit) {
        if (!hasUnsavedChanges) {
            onProceed()
            return
        }
        val dialog = Dialog().apply {
            headerTitle = "Modifiche non salvate"
            width = "440px"
        }
        dialog.add(Span("Hai modifiche al pivot non ancora salvate. Vuoi salvarle prima di continuare?"))

        val cancelButton = Button("Annulla") { dialog.close() }
        val discardButton = Button("Scarta modifiche") {
            markSaved()
            dialog.close()
            onProceed()
        }
        val saveButton = Button("Salva ed esci") {
            savePivotState()
            dialog.close()
            onProceed()
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        dialog.footer.add(cancelButton, discardButton, saveButton)
        dialog.open()
    }

    // ================= Sorgente =================

    private fun refreshSourceStatus() {
        val currentAreaId = areaId
        if (currentAreaId == null) {
            hasSource = false
            sourceStatus = null
            ui.setSourceStatusText("")
            refreshSidebar()
            return
        }

        val source = data.findSourceByArea(currentAreaId)
        hasSource = source != null
        sourceStatus = source?.status

        ui.setSourceStatusText(
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

        ui.setSourceStatusText("Verifica in corso...")

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

        ui.setSourceStatusText("Sincronizzazione in corso...")

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

    /**
     * Punto di ingresso pubblico per cambiare area: se ci sono modifiche
     * pivot non salvate sull'area corrente, chiede conferma prima di
     * procedere davvero (doSwitchArea).
     */
    private fun switchArea(newAreaId: UUID) {
        confirmDiscardIfNeeded { doSwitchArea(newAreaId) }
    }

    private fun doSwitchArea(newAreaId: UUID) {
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

        // Stato "in transito" da/verso ChartsView (sessione Vaadin, non
        // persistito): ha priorità sullo stato salvato esplicitamente,
        // perché rappresenta un lavoro appena interrotto un attimo fa.
        val saved = AnalysisWorkStateHolder.read()
        if (saved != null && saved.areaId == newAreaId) {
            pivotRows = saved.pivotRows
            pivotColumns = saved.pivotColumns
            pivotValues = saved.pivotValues
            selections.putAll(saved.selections)
            ui.pivotPanel.restoreState(pivotRows, pivotColumns, pivotValues)
            rebuildFilterCards(pivotRows, pivotColumns)
            AnalysisWorkStateHolder.clear()
        } else {
            // Nessuno stato "in transito": prova a caricare l'ultimo
            // stato salvato esplicitamente dall'utente per quest'area.
            // Id di dimensioni/metriche/valori non più esistenti vengono
            // scartati silenziosamente da PivotPanel.restoreState, stesso
            // comportamento già usato per lo stato "in transito" sopra.
            val currentUser = CurrentUserHolder.get()
            if (currentUser != null) {
                val savedPivotState = userPivotStateRepository.find(currentUser.userId, newAreaId)
                if (savedPivotState != null) {
                    pivotRows = savedPivotState.pivotRows
                    pivotColumns = savedPivotState.pivotColumns
                    pivotValues = savedPivotState.pivotValues
                    selections.putAll(savedPivotState.selections)
                    ui.pivotPanel.restoreState(pivotRows, pivotColumns, pivotValues)
                    rebuildFilterCards(pivotRows, pivotColumns)
                }
            }
        }
        markSaved()

        refresh()
    }

    private fun refreshPivotFields(currentAreaId: UUID) {
        val dims = data.findDimensioniByArea(currentAreaId)
            .mapNotNull { ad -> data.findDimensione(ad.dimensioneId)?.let { ad.dimensioneId to it.nome } }
        val metriche = data.findMetricheByArea(currentAreaId).map { it.id to it.nome }
        ui.pivotPanel.setFieldsWithIds(dims, metriche)
    }

    // ================= Pivot / Filtri =================

    private fun onPivotChanged(rows: List<UUID>, columns: List<UUID>, values: List<UUID>) {
        println("DEBUG: onPivotChanged chiamato, rows=$rows, columns=$columns")
        val removedDims = (pivotRows + pivotColumns).filter { it !in rows && it !in columns }
        pivotRows = rows
        pivotColumns = columns
        pivotValues = values
        removedDims.forEach { selections.remove(it) }
        rebuildFilterCards(rows, columns)
        markUnsaved()
        refresh()
    }

    private fun rebuildFilterCards(rows: List<UUID>, columns: List<UUID>) {
        ui.rebuildFilterCards(
            rowDims = rows,
            columnDims = columns,
            dimensionNames = dimensionNames,
            columnFor = { dimId -> dimensionColumns[dimId] },
            countSameName = { name -> (rows + columns).count { dimensionNames[it] == name } }
        )
    }

    private fun onFilterSelectionChanged(dimId: UUID, values: Set<Long>) {
        selections[dimId] = values
        markUnsaved()
        refresh()
    }

    private fun onRemoveSelection(dimId: UUID, valueId: Long) {
        val current = selections[dimId]?.toMutableSet() ?: return
        current.remove(valueId)
        if (current.isEmpty()) selections.remove(dimId) else selections[dimId] = current
        ui.deselectValue(dimId, valueId)
        markUnsaved()
        refresh()
    }

    // ================= Refresh =================

    /**
     * Punto unico di ricalcolo. Apre il dialog di caricamento, delega a
     * data.refresh() il lavoro vero (query), poi delega a ui.render*()
     * il disegno del risultato. Non fa mai query né costruisce
     * componenti direttamente: coordina solo.
     *
     * NOTA: pivotColumns non è ancora passato a data.refresh()/
     * AggregateService - il calcolo dell'asse Colonne non è ancora
     * cablato lato backend. Il pivot UI accetta già il drag&drop su
     * Colonne, ma finché AggregateService non aggrega su due assi il
     * risultato ignora pivotColumns.
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
                println("DEBUG: refresh completato, righe aggregati=${result.aggregates.rows.size}, grafici=${result.chartsData.size}")

                vaadinUi.access {
                    if (myRequestId != requestCounter.get()) return@access
                    if (areaId != currentAreaId) return@access
                    println("DEBUG: prima di renderStates")
                    ui.renderStates(result.states, result.labels, data::labelOrFallback) { dimId -> dimensionColumns[dimId] }
                    println("DEBUG: prima di renderResultsGrid")
                    ui.renderResultsGrid(result.aggregates, result.rowHierarchy, rowsSnapshot, dimensionNames)
                    println("DEBUG: prima di renderCharts")
                    ui.renderCharts(result.chartsData, rowsSnapshot)
                    println("DEBUG: dopo renderCharts, tutto ok")
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