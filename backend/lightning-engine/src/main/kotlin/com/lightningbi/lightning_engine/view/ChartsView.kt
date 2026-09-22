package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.AreaChart
import com.lightningbi.lightning_engine.model.ChartType
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.repository.UserPivotStateRepository
import com.lightningbi.lightning_engine.service.ChartService
import com.lightningbi.lightning_engine.service.SourceVerificationService
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
 * subito la creazione con quel tipo già deciso. Tutte le card sono
 * sempre cliccabili, nessun blocco preventivo in base al pivot o alle
 * metriche - le incompatibilità (es. torta con più di una metrica)
 * emergono nel dialog di creazione o, per il vincolo sulle dimensioni,
 * nella vista principale dove il grafico verrebbe mostrato opaco con un
 * messaggio se il pivot corrente non è coerente.
 *
 * Righe/Colonne/Valori del grafico sono ora scelte con un PivotPanel
 * dedicato dentro il dialog (create/edit), non più con ComboBox/
 * MultiSelectListBox: stesso componente della pagina Analisi, ma
 * "subordinato" - i campi ammessi sono solo quelli presenti nell'ULTIMO
 * pivot salvato dall'utente per quest'area (userPivotStateRepository),
 * tutti gli altri campi dell'area restano visibili ma disabilitati
 * (grigi) nel pool, per dare dinamismo: se l'utente amplia il pivot in
 * Analisi e lo salva, quei campi diventano scegliebili anche qui alla
 * prossima apertura del dialog.
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
                    Button("Modifica") { openEditDialog(chart) },
                    Button("Elimina") { confirmDelete(chart) }
                ).apply { isPadding = false }
            }.setHeader("")
        }
        reload(currentAreaId)

        return VerticalLayout(
            Span("Grafici di \"$areaNome\"").apply { className = "lbi-section-title" },
            Span("Scegli un tipo per creare un nuovo grafico. Righe, Colonne e Valori sono proprie del grafico, scelte fra i campi presenti nel pivot corrente dell'analisi.").apply {
                className = "lbi-wizard-label"
            },
            typeGallery,
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
            addClickListener { openCreateDialog(currentAreaId, tipo) }
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
     * grafici veri (ECharts, quando integrato).
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
     * Campi ammessi in questo momento per il PivotPanel del grafico:
     * quelli presenti nell'ULTIMO pivot salvato dall'utente per
     * quest'area (Righe+Colonne+Valori), letto da userPivotStateRepository.
     * Se l'utente non ha mai salvato una vista per quest'area, l'insieme
     * è vuoto - tutti i campi appaiono disabilitati, coerente col fatto
     * che senza un pivot di riferimento un grafico non avrebbe comunque
     * senso.
     */
    private fun campiAmmessi(currentAreaId: UUID): Set<UUID> {
        val userId = CurrentUserHolder.get()?.userId ?: return emptySet()
        val stato = userPivotStateRepository.find(userId, currentAreaId) ?: return emptySet()
        return (stato.pivotRows + stato.pivotColumns + stato.pivotValues).toSet()
    }

    /**
     * Costruisce il PivotPanel dedicato al grafico: stessi campi
     * dell'area (dimensioni + metriche) del PivotPanel della pagina
     * Analisi, ma con i campi non presenti nell'ultimo pivot salvato
     * dall'utente marcati come disabilitati (grigi) tramite
     * setDisabledFields - non rimossi, per dare visibilità che esistono
     * ma non sono selezionabili ora.
     */
    private fun buildChartPivotPanel(currentAreaId: UUID): PivotPanel {
        val dimensioni = registryRepository.findDimensioniByArea(currentAreaId)
            .mapNotNull { d -> registryRepository.findDimensione(d.dimensioneId)?.let { d.dimensioneId to it.nome } }
        val metriche = registryRepository.findMetricheByArea(currentAreaId).map { it.id to it.nome }

        val ammessi = campiAmmessi(currentAreaId)
        val tuttiCampi = (dimensioni.map { it.first } + metriche.map { it.first }).toSet()
        val disabilitati = tuttiCampi - ammessi

        val panel = PivotPanel { _, _, _ -> }
        panel.setFieldsWithIds(dimensioni, metriche)
        panel.setDisabledFields(disabilitati)
        return panel
    }

    /**
     * Dialog di creazione: il tipo arriva già deciso dal click sulla
     * card, qui si scelgono titolo, metriche e Righe/Colonne proprie del
     * grafico via PivotPanel dedicato. Se il tipo richiede un numero
     * minimo di metriche (torta = 1, scatter >= 2, radar >= 3) e la
     * scelta non lo rispetta, il messaggio arriva alla conferma, non
     * come blocco preventivo sulla card.
     */
    private fun openCreateDialog(currentAreaId: UUID, tipo: ChartType) {
        val metriche = registryRepository.findMetricheByArea(currentAreaId)
        if (metriche.isEmpty()) {
            Notification.show("L'analisi non ha metriche configurate: aggiungine prima dalla vista principale")
            return
        }

        val dialog = Dialog().apply {
            headerTitle = "Nuovo grafico: ${chartTypeLabel(tipo)}"
            width = "560px"
        }

        val titoloField = TextField("Titolo").apply { setWidthFull() }

        val pivotPanel = buildChartPivotPanel(currentAreaId)

        dialog.add(
            VerticalLayout(
                titoloField,
                Span("Righe, Colonne e Valori del grafico (solo i campi presenti nel pivot corrente dell'analisi sono selezionabili)")
                    .apply { className = "lbi-wizard-label" },
                pivotPanel
            ).apply { isPadding = false }
        )

        val cancelButton = Button("Annulla") { dialog.close() }
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

            try {
                chartService.create(
                    areaId = currentAreaId,
                    titolo = titolo,
                    tipo = tipo,
                    metricaIds = pivotValues,
                    pivotRows = pivotRows,
                    pivotColumns = pivotColumns
                )
                reload(currentAreaId)
                dialog.close()
            } catch (e: Exception) {
                Notification.show("Errore: ${e.message}", 5000, Notification.Position.MIDDLE)
            }
        }
        dialog.footer.add(cancelButton, createButton)
        dialog.open()
    }

    /**
     * Edit completo di un grafico esistente: titolo, Righe/Colonne/Valori
     * proprie (PivotPanel dedicato, precompilato con la configurazione
     * salvata - i campi non più ammessi nel pivot corrente dell'analisi
     * restano disabilitati anche se già scelti in precedenza, l'utente
     * viene così invitato a correggere e risalvare), ordinamento, limite
     * righe, e le due opzioni che legano il grafico all'asse Colonne
     * PROPRIO (followsColumns) e alla colorazione condizionale
     * (highlightDecline). Il TIPO di grafico non è modificabile qui:
     * cambiare tipo significa cambiare i vincoli (metriche minime,
     * comportamento assi), più semplice eliminare e ricreare col tipo
     * giusto che rivalidare tutto in place.
     */
    private fun openEditDialog(chart: AreaChart) {
        val currentAreaId = areaId ?: return
        val tutteMetriche = registryRepository.findMetricheByArea(currentAreaId)
        if (tutteMetriche.isEmpty()) {
            Notification.show("L'analisi non ha metriche configurate")
            return
        }

        val metricheAttuali = chartService.getMetricheDelGrafico(chart.id)
            .sortedBy { it.posizione }
            .mapNotNull { cm -> tutteMetriche.find { it.id == cm.metricaId } }

        val dialog = Dialog().apply {
            className = "lbi-wizard-dialog"
            headerTitle = "Modifica \"${chart.titolo}\" (${chartTypeLabel(chart.tipo)})"
            width = "560px"
        }

        val titoloField = TextField("Titolo").apply {
            setWidthFull()
            value = chart.titolo
        }

        val pivotPanel = buildChartPivotPanel(currentAreaId)
        pivotPanel.restoreState(chart.pivotRows, chart.pivotColumns, metricheAttuali.map { it.id })

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
        ).apply {
            value = chart.followsColumns
        }
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

        dialog.add(
            VerticalLayout(
                titoloField,
                Span("Righe, Colonne e Valori del grafico (solo i campi presenti nel pivot corrente dell'analisi sono selezionabili)")
                    .apply { className = "lbi-wizard-label" },
                pivotPanel,
                orderByCombo,
                maxItemsField,
                followsColumnsCheckbox,
                highlightDeclineCheckbox
            ).apply { isPadding = false }
        )

        val cancelButton = Button("Annulla") { dialog.close() }
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
                dialog.close()
                Notification.show("Grafico aggiornato", 3000, Notification.Position.BOTTOM_END)
            } catch (e: Exception) {
                Notification.show("Errore: ${e.message}", 5000, Notification.Position.MIDDLE)
            }
        }.apply { addThemeVariants(com.vaadin.flow.component.button.ButtonVariant.LUMO_PRIMARY) }

        dialog.footer.add(cancelButton, saveButton)
        dialog.open()
    }

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
            dialog.close()
        }
        dialog.footer.add(cancelButton, confirmButton)
        dialog.open()
    }
}