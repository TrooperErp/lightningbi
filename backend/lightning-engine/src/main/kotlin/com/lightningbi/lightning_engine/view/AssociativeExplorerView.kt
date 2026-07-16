package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.AggregateRequest
import com.lightningbi.lightning_engine.model.AggregateResult
import com.lightningbi.lightning_engine.model.Area
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.service.AggregateService
import com.lightningbi.lightning_engine.service.AssociativeStateService
import com.lightningbi.lightning_engine.service.DimensionState
import com.lightningbi.lightning_engine.service.RegistryService
import com.lightningbi.lightning_engine.service.VersionService
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.listbox.MultiSelectListBox
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
    private val registryService: RegistryService
) : VerticalLayout() {

    private var areaId: UUID? = null

    private val selections = mutableMapOf<UUID, Set<Long>>()
    private val dimensionBoxes = mutableMapOf<UUID, MultiSelectListBox<Long>>()
    private val currentItems = mutableMapOf<UUID, List<Long>>()

    private val requestCounter = AtomicLong(0)
    private val kpiArea = Div()
    private val dimensionsLayout = HorizontalLayout()
    private val areaSelector = ComboBox<Area>("Area")

    private var viewScope: CoroutineScope? = null
    private var isDark = false

    init {
        className = "lbi-page"
        dimensionsLayout.className = "lbi-dimensions-row"
        kpiArea.className = "lbi-kpi-row"

        val areas = registryRepository.findAllAree()
        areaSelector.setItems(areas)
        areaSelector.setItemLabelGenerator { it.nome }
        areaSelector.className = "lbi-area-selector"
        areaSelector.addValueChangeListener { event ->
            val selected = event.value ?: return@addValueChangeListener
            switchArea(selected.id)
        }

        val addAreaButton = Button("➕ Aggiungi Analisi").apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            addClickListener {
                AddAreaWizardDialog(registryService, registryRepository) {
                    val areas = registryRepository.findAllAree()
                    areaSelector.setItems(areas)
                    areaSelector.value = areas.lastOrNull()
                }.open()
            }
        }

        val themeToggle = Button("🌙 Dark").apply {
            addThemeVariants(ButtonVariant.LUMO_TERTIARY)
            addClickListener {
                isDark = !isDark
                element.executeJs(
                    "document.documentElement.setAttribute('theme', \$0)",
                    if (isDark) "dark" else ""
                )
                text = if (isDark) "☀️ Light" else "🌙 Dark"
            }
        }
        val topBar = HorizontalLayout(areaSelector, addAreaButton, themeToggle).apply {
            className = "lbi-topbar"
            justifyContentMode = FlexComponent.JustifyContentMode.BETWEEN
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            setWidthFull()
        }

        add(topBar)
        add(dimensionsLayout)
        add(kpiArea)

        if (areas.isNotEmpty()) {
            areaSelector.value = areas.first()
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
        dimensionsLayout.removeAll()
        kpiArea.removeAll()
        buildDimensionBoxes(newAreaId)
        refresh()
    }

    private fun buildDimensionBoxes(currentAreaId: UUID) {
        val dims = registryRepository.findDimensioniByArea(currentAreaId)
        dims.forEach { areaDim ->
            val dimensione = registryRepository.findDimensione(areaDim.dimensioneId) ?: return@forEach
            val dimId = areaDim.dimensioneId

            val box = MultiSelectListBox<Long>()
            box.width = "250px"
            box.height = "300px"
            box.setRenderer(neutralRenderer())
            box.addSelectionListener { event ->
                if (!event.isFromClient) return@addSelectionListener
                selections[dimId] = event.value.toSet()
                refresh()
            }
            dimensionBoxes[dimId] = box

            val title = Span(dimensione.nome).apply { className = "lbi-dimension-title" }
            val card = VerticalLayout(title, box).apply { className = "lbi-dimension-card" }
            dimensionsLayout.add(card)
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
                    if (areaId != currentAreaId) return@access // area cambiata nel frattempo
                    renderStates(states)
                    renderAggregates(aggregates)
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

    private fun renderAggregates(result: AggregateResult) {
        kpiArea.removeAll()
        result.rows.firstOrNull()?.values?.forEach { (nome, valore) ->
            val card = Div().apply {
                className = "lbi-kpi-card"
                add(Span(nome.uppercase()).apply { className = "lbi-kpi-label" })
                add(Div(Span(valore.toString())).apply { className = "lbi-kpi-value" })
            }
            kpiArea.add(card)
        }
        if (result.truncated) {
            kpiArea.add(Span("(risultato troncato)"))
        }
    }

    private fun neutralRenderer() = ComponentRenderer<Span, Long> { valueId ->
        Span(valueId.toString()).apply { className = "state-possible" }
    }
}