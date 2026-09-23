package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.PivotView
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.repository.UserPivotStateRepository
import com.lightningbi.lightning_engine.service.AssociativeStateFacade
import com.lightningbi.lightning_engine.service.AuthService
import com.lightningbi.lightning_engine.service.DimensionState
import com.lightningbi.lightning_engine.service.PivotViewService
import com.lightningbi.lightning_engine.service.SymbolLookupService
import com.lightningbi.lightning_engine.service.VersionService
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Pagina "Configura Analisi": costruzione delle PivotView di un'Area
 * (Righe/Colonne/Valori, come i fogli di un'app Qlik) più le card
 * filtro di TUTTI i campi dell'Area, non solo quelli nella vista
 * attiva - equivalente al selections tool di Qlik (sezione "App
 * dimensions").
 *
 * Le tab in alto elencano le PivotView dell'Area (condivise, chiunque
 * apra l'Analisi le vede uguali); il PivotPanel sotto edita quella
 * attiva con salvataggio immediato ad ogni drag&drop, nessun bottone
 * "Salva" - stesso comportamento Qlik: il foglio riflette subito la
 * struttura scelta.
 *
 * Le selezioni (verde/bianco/grigio) sono le STESSE, uniche e condivise
 * per l'Area, che si vedono in AssociativeExplorerView: selezionare un
 * valore qui è la stessa identica azione. Ogni card parte chiusa (un
 * chip con solo "+"), tranne quelle che hanno già una selezione attiva:
 * il calcolo (via AssociativeStateFacade, con dimensioniDaCalcolare
 * limitato alle card aperte) parte solo quando una card si apre, per
 * non pagare una query per ognuna delle dimensioni dell'Area ad ogni
 * refresh.
 *
 * Ogni azione che cambia viste (crea/rinomina/elimina/seleziona tab)
 * ricostruisce l'intera pagina con buildPage(): più semplice e robusto
 * che sostituire singoli componenti nel loro parent.
 */
@Route("configure")
class ConfigureAnalysisView(
    private val registryRepository: RegistryRepository,
    private val userPivotStateRepository: UserPivotStateRepository,
    private val pivotViewService: PivotViewService,
    private val associativeStateService: AssociativeStateFacade,
    private val versionService: VersionService,
    private val symbolLookupService: SymbolLookupService,
    private val authService: AuthService
) : VerticalLayout(), HasUrlParameter<String> {

    private var areaId: UUID? = null
    private var views: List<PivotView> = emptyList()
    private var activeView: PivotView? = null

    private val dimensionNames = mutableMapOf<UUID, String>()
    private val dimensionColumns = mutableMapOf<UUID, String>()
    private val selections = mutableMapOf<UUID, Set<Long>>()

    /** Dimensioni la cui card è aperta in questo momento: solo queste vengono ricalcolate ad ogni refresh. */
    private val openDims = mutableSetOf<UUID>()

    private lateinit var shell: LbiAppShell
    private lateinit var renameField: TextField

    /** Per ogni dimensione: il contenitore della card e, se aperta, la griglia di span. */
    private val fieldCards = mutableMapOf<UUID, Div>()
    private val fieldGrids = mutableMapOf<UUID, Div>()
    private val fieldSpans = mutableMapOf<UUID, MutableMap<Long, Span>>()
    private val currentFieldItems = mutableMapOf<UUID, List<Long>>()

    private var viewScope: CoroutineScope? = null

    override fun setParameter(event: BeforeEvent, parameter: String) {
        areaId = try {
            UUID.fromString(parameter)
        } catch (e: IllegalArgumentException) {
            Notification.show("Analisi non valida")
            null
        }
        buildPage()
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        attachEvent.ui.page.addJavaScript("js/echarts.min.js")
        if (viewScope == null) {
            viewScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        areaId?.let { refreshOpenCards(it) }
    }

    override fun onDetach(detachEvent: DetachEvent) {
        viewScope?.cancel()
        viewScope = null
        super.onDetach(detachEvent)
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
            loadAreaState(area.id)
            buildContent(area.id, area.nome)
        }

        val menuGroups = listOf(
            LbiSidebarMenu.MenuGroup(
                label = "Analisi",
                entries = if (area != null) {
                    listOf(LbiSidebarMenu.MenuEntry(area.nome) { navigateToAssociative(area.id) })
                } else emptyList()
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Configura Analisi",
                entries = listOf(LbiSidebarMenu.MenuEntry("Righe, Colonne, Valori", enabled = false) {}),
                active = true
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Gestisci",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Torna all'analisi", enabled = area != null) {
                        navigateToAssociative(area?.id)
                    }
                )
            )
        )

        shell = LbiAppShell(menuGroups, content, authService)
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

    /** Nomi/colonne delle dimensioni e selezioni correnti dell'utente per quest'area, prima di disegnare. */
    private fun loadAreaState(currentAreaId: UUID) {
        dimensionNames.clear()
        dimensionColumns.clear()
        registryRepository.findDimensioniByArea(currentAreaId).forEach { ad ->
            registryRepository.findDimensione(ad.dimensioneId)?.let { dim ->
                dimensionNames[ad.dimensioneId] = dim.nome
                dimensionColumns[ad.dimensioneId] = ad.colonnaFisica
            }
        }

        selections.clear()
        val currentUser = CurrentUserHolder.get()
        val savedSelections = currentUser?.let { userPivotStateRepository.find(it.userId, currentAreaId)?.selections }
        if (savedSelections != null) selections.putAll(savedSelections)

        // Le card con una selezione già attiva partono aperte, coerente
        // con Qlik (i campi con selezioni sono sempre in vista, quelli
        // senza restano collassati finché l'utente non li apre).
        openDims.clear()
        openDims.addAll(selections.filterValues { it.isNotEmpty() }.keys)
    }

    private fun buildContent(currentAreaId: UUID, areaNome: String): Component {
        views = pivotViewService.findByArea(currentAreaId)
        val currentUser = CurrentUserHolder.get()
        activeView = currentUser?.let { pivotViewService.ensureActiveView(it.userId, currentAreaId) }
            ?: views.firstOrNull()
        // ensureActiveView può aver creato la prima vista se non ce n'erano: rileggo l'elenco.
        views = pivotViewService.findByArea(currentAreaId)

        val tabs = buildTabs(currentAreaId)

        val newViewButton = Button("+ Nuova vista") {
            val currentUserId = CurrentUserHolder.get()?.userId ?: return@Button
            val created = pivotViewService.createView(currentUserId, currentAreaId, "Vista ${views.size + 1}")
            activeView = created
            buildPage()
        }

        renameField = TextField().apply {
            placeholder = "Nome vista"
            value = activeView?.nome ?: ""
            addValueChangeListener {
                val view = activeView ?: return@addValueChangeListener
                val nuovoNome = value
                if (nuovoNome.isNullOrBlank()) return@addValueChangeListener
                activeView = pivotViewService.renameView(view, nuovoNome)
            }
        }

        val deleteViewButton = Button("Elimina vista") {
            val view = activeView ?: return@Button
            confirmDeleteView(currentAreaId, view)
        }

        val viewToolbar = HorizontalLayout(renameField, deleteViewButton).apply {
            isPadding = false
        }

        val pivotPanel = buildPivotPanel(currentAreaId)

        val fieldsContainer = Div().apply { className = "lbi-config-fields-grid" }
        rebuildFieldCards(currentAreaId, fieldsContainer)

        return VerticalLayout(
            Span("Configura Analisi: \"$areaNome\"").apply { className = "lbi-section-title" },
            HorizontalLayout(tabs, newViewButton).apply { isPadding = false },
            viewToolbar,
            Span("Righe, Colonne, Valori").apply { className = "lbi-section-title" },
            pivotPanel,
            Span("Campi e selezioni").apply { className = "lbi-section-title" },
            fieldsContainer
        ).apply {
            className = "lbi-center"
            isPadding = true
            setSizeFull()
        }
    }

    // ================= Tab viste =================

    private fun buildTabs(currentAreaId: UUID): Tabs {
        val t = Tabs()
        views.forEach { view -> t.add(Tab(view.nome)) }
        val activeIndex = views.indexOfFirst { it.id == activeView?.id }.coerceAtLeast(0)
        if (views.isNotEmpty()) t.selectedIndex = activeIndex

        t.addSelectedChangeListener {
            val index = t.selectedIndex
            val selected = views.getOrNull(index) ?: return@addSelectedChangeListener
            if (selected.id == activeView?.id) return@addSelectedChangeListener
            val currentUserId = CurrentUserHolder.get()?.userId ?: return@addSelectedChangeListener
            pivotViewService.setActiveView(currentUserId, currentAreaId, selected.id)
            activeView = selected
            buildPage()
        }
        return t
    }

    private fun confirmDeleteView(currentAreaId: UUID, view: PivotView) {
        val dialog = Dialog().apply {
            headerTitle = "Eliminare \"${view.nome}\"?"
            width = "440px"
        }
        dialog.add(Span("La vista sarà rimossa. Le altre viste dell'analisi non sono toccate."))
        val cancelButton = Button("Annulla") { dialog.close() }
        val confirmButton = Button("Elimina") {
            val ok = pivotViewService.deleteView(currentAreaId, view.id)
            dialog.close()
            if (!ok) {
                Notification.show("Non puoi eliminare l'unica vista rimasta", 4000, Notification.Position.MIDDLE)
                return@Button
            }
            buildPage()
        }
        dialog.footer.add(cancelButton, confirmButton)
        dialog.open()
    }

    // ================= PivotPanel =================

    private fun buildPivotPanel(currentAreaId: UUID): PivotPanel {
        val dims = registryRepository.findDimensioniByArea(currentAreaId)
            .mapNotNull { ad -> registryRepository.findDimensione(ad.dimensioneId)?.let { ad.dimensioneId to it.nome } }
        val metriche = registryRepository.findMetricheByArea(currentAreaId).map { it.id to it.nome }

        // setFieldsWithIds scatena onChange in modo SINCRONO, prima che
        // restoreState possa ripristinare lo stato salvato: senza questo
        // flag, quel primo onChange (Righe/Colonne vuote) sovrascriverebbe
        // su DB la vista appena letta, prima ancora di mostrarla.
        var inizializzazione = true

        val panel = PivotPanel { rows, columns, values ->
            if (inizializzazione) return@PivotPanel
            val view = activeView ?: return@PivotPanel
            activeView = pivotViewService.updatePivot(view, rows, columns, values)
        }
        panel.setFieldsWithIds(dims, metriche)
        activeView?.let { panel.restoreState(it.pivotRows, it.pivotColumns, it.pivotValues) }
        inizializzazione = false

        return panel
    }

    // ================= Card filtro (tutti i campi) =================

    private fun rebuildFieldCards(currentAreaId: UUID, fieldsContainer: Div) {
        fieldsContainer.removeAll()
        fieldCards.clear()
        fieldGrids.clear()
        fieldSpans.clear()
        currentFieldItems.clear()

        val dims = registryRepository.findDimensioniByArea(currentAreaId)
        dims.forEach { ad ->
            val dimName = dimensionNames[ad.dimensioneId] ?: return@forEach
            val card = buildFieldCard(ad.dimensioneId, dimName)
            fieldCards[ad.dimensioneId] = card
            fieldsContainer.add(card)
        }

        // Il calcolo delle card aperte parte da onAttach, non da qui:
        // in questo momento (dentro buildContent, chiamato da
        // setParameter) la pagina non è ancora attaccata al browser,
        // quindi ui.orElse(null) tornerebbe vuoto o un valore stantio e
        // il risultato della coroutine andrebbe perso. Qui disegniamo
        // solo i "+" delle card chiuse.
        fieldCards.keys.forEach { dimId ->
            if (dimId !in openDims) renderClosedCard(dimId)
        }
    }

    /**
     * Una card per campo: titolo + area valori. Chiusa mostra un singolo
     * chip "+" al posto della griglia (nessun calcolo, nessuna query);
     * al click la card entra in openDims e si ricalcola solo quella.
     */
    private fun buildFieldCard(dimId: UUID, dimName: String): Div {
        val title = Span(dimName).apply { className = "lbi-filter-title" }
        val grid = Div().apply { className = "lbi-filter-grid" }
        fieldGrids[dimId] = grid
        fieldSpans[dimId] = mutableMapOf()

        return Div(title, grid).apply { className = "lbi-filter-card" }
    }

    /** Ridisegna la griglia di una card come "chiusa": un solo chip "+" cliccabile per aprirla. */
    private fun renderClosedCard(dimId: UUID) {
        val grid = fieldGrids[dimId] ?: return
        grid.removeAll()
        val plus = Span("+").apply {
            className = "lbi-pivot-chip"
            addClickListener {
                openDims.add(dimId)
                areaId?.let { refreshOpenCards(it) }
            }
        }
        grid.add(plus)
        currentFieldItems[dimId] = emptyList()
        fieldSpans[dimId]?.clear()
    }

    /** Ricalcola e disegna solo le card in openDims; le altre restano/diventano il chip "+". */
    /** Ricalcola e disegna solo le card in openDims; le altre restano/diventano il chip "+". */
    private fun refreshOpenCards(currentAreaId: UUID) {
        // I chip "+" delle card chiuse si disegnano SEMPRE, anche prima che
        // la pagina sia attaccata al browser (chiamata da setParameter):
        // non richiedono query né UI.
        fieldCards.keys.forEach { dimId ->
            if (dimId !in openDims) renderClosedCard(dimId)
        }
        if (openDims.isEmpty()) return

        // Il calcolo delle card aperte richiede UI e scope, disponibili solo
        // dopo onAttach: se non ci sono ancora, lo farà onAttach stesso.
        val vaadinUi = ui.orElse(null) ?: return
        val scope = viewScope ?: return

        val selectionsSnapshot = selections.filterValues { it.isNotEmpty() }
        val target = openDims.toSet()

        scope.launch {
            try {
                val versions = versionService.snapshotVersions(currentAreaId)
                val states = associativeStateService.getStates(currentAreaId, selectionsSnapshot, versions, target)
                val labels = target.associateWith { dimId ->
                    val dimName = dimensionNames[dimId] ?: return@associateWith emptyMap<Long, String>()
                    val ids = (states[dimId]?.let { it.verdi + it.grigi + it.selezionati }) ?: emptySet()
                    symbolLookupService.resolveLabels(dimName, ids)
                }
                vaadinUi.access {
                    if (areaId != currentAreaId) return@access
                    states.forEach { (dimId, state) -> renderOpenCard(dimId, state, labels[dimId] ?: emptyMap()) }
                }
            } catch (e: Exception) {
                vaadinUi.access {
                    Notification.show("Errore aggiornamento campi: ${e.message}", 5000, Notification.Position.BOTTOM_END)
                }
            }
        }
    }

    private fun renderOpenCard(dimId: UUID, state: DimensionState, labels: Map<Long, String>) {
        val grid = fieldGrids[dimId] ?: return
        val spanMap = fieldSpans[dimId] ?: return

        val colonna = dimensionColumns[dimId]
        val naturalOrder = colonna != null && com.lightningbi.lightning_engine.service.DimensionSortOrders.usesNaturalOrder(colonna)
        val allValues = (state.verdi + state.grigi + state.selezionati)
            .distinct()
            .let { values -> if (naturalOrder) values.sorted() else values.sortedBy { labelOrFallback(labels, it) } }

        if (currentFieldItems[dimId] != allValues) {
            grid.removeAll()
            spanMap.clear()
            allValues.forEach { valueId ->
                val fullLabel = colonna?.let { com.lightningbi.lightning_engine.service.DimensionFormatters.formatOrNull(it, valueId) } ?: labelOrFallback(labels, valueId)
                val shortLabel = if (fullLabel.length > 6) fullLabel.take(6) + "…" else fullLabel
                val span = Span(shortLabel).apply {
                    element.setAttribute("title", fullLabel)
                    addClickListener {
                        val isCurrentlySelected = className == "state-selected"
                        val selectedNow = spanMap.filterValues { it.className == "state-selected" }.keys.toMutableSet()
                        if (isCurrentlySelected) selectedNow.remove(valueId) else selectedNow.add(valueId)
                        onFieldSelectionChanged(dimId, selectedNow)
                    }
                }
                spanMap[valueId] = span
                grid.add(span)
            }
            currentFieldItems[dimId] = allValues
        }

        spanMap.forEach { (valueId, span) ->
            val stateClass = when {
                valueId in state.selezionati -> "state-selected"
                valueId in state.verdi -> "state-possible"
                else -> "state-excluded"
            }
            span.className = stateClass
            val (bg, color, border) = when (stateClass) {
                "state-selected" -> Triple("#22c55e", "#ffffff", "none")
                "state-possible" -> Triple("transparent", "#201f1e", "1px solid #e1dfdd")
                else -> Triple("#e1dfdd", "#a19f9d", "none")
            }
            span.style.set("background", bg)
            span.style.set("color", color)
            span.style.set("border", border)
        }
    }

    private fun onFieldSelectionChanged(dimId: UUID, values: Set<Long>) {
        selections[dimId] = values
        persistSelections()
        areaId?.let { refreshOpenCards(it) }
    }

    private fun persistSelections() {
        val currentAreaId = areaId ?: return
        val currentUser = CurrentUserHolder.get() ?: return
        val current = userPivotStateRepository.find(currentUser.userId, currentAreaId)
        userPivotStateRepository.save(
            com.lightningbi.lightning_engine.model.UserPivotState(
                userId = currentUser.userId,
                areaId = currentAreaId,
                activeViewId = current?.activeViewId,
                selections = selections.filterValues { it.isNotEmpty() }
            )
        )
    }

    private fun labelOrFallback(labels: Map<Long, String>, id: Long?): String =
        symbolLookupService.labelOrFallback(labels, id)
}