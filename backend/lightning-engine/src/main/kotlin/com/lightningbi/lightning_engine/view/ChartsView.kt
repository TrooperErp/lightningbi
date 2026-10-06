package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.AggregateOrder
import com.lightningbi.lightning_engine.model.Area
import com.lightningbi.lightning_engine.model.AreaChart
import com.lightningbi.lightning_engine.model.ChartResult
import com.lightningbi.lightning_engine.model.ChartType
import com.lightningbi.lightning_engine.model.MisuraAnalisi
import com.lightningbi.lightning_engine.model.PivotView
import com.lightningbi.lightning_engine.repository.MisuraAnalisiRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.repository.UserPivotStateRepository
import com.lightningbi.lightning_engine.service.AdminGuard
import com.lightningbi.lightning_engine.service.AnalisiService
import com.lightningbi.lightning_engine.service.AuthService
import com.lightningbi.lightning_engine.service.ChartService
import com.lightningbi.lightning_engine.service.DimensioneAnalisi
import com.lightningbi.lightning_engine.service.PivotViewService
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dependency.Uses
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.IntegerField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.BeforeEvent
import com.vaadin.flow.router.HasUrlParameter
import com.vaadin.flow.router.Route
import java.util.UUID

/**
 * Pagina "Grafici": i grafici di UN'analisi. Stessa impostazione della pagina Analisi
 * (guscio, pannelli Campi, Righe, Colonne e Misure, stessi pulsanti), con in più la scelta
 * del tipo di grafico, le opzioni e l'anteprima.
 *
 * - Un grafico appartiene a un'analisi e usa le SUE misure (si scrivono nella pagina
 *   Analisi con + Misura). Le selezioni sono quelle dell'utente, uguali per pivot e grafici.
 * - Le schede dei tipi aprono il modulo di creazione; il modulo si precompila con righe,
 *   colonne e misure dell'analisi da cui si arriva.
 * - Il tipo di un grafico esistente non si cambia: cambia i vincoli, si elimina e si ricrea.
 *
 * Route "charts/{dataset},{analisi}".
 */
@Route("charts")
@Uses(Icon::class)
class ChartsView(
    private val chartService: ChartService,
    private val registryRepository: RegistryRepository,
    private val userPivotStateRepository: UserPivotStateRepository,
    private val misuraAnalisiRepository: MisuraAnalisiRepository,
    private val pivotViewService: PivotViewService,
    private val analisiService: AnalisiService,
    private val adminGuard: AdminGuard,
    private val authService: AuthService
) : VerticalLayout(), HasUrlParameter<String> {

    private var areaId: UUID? = null
    private var pivotViewId: UUID? = null
    private var area: Area? = null
    private var analisi: List<PivotView> = emptyList()
    private var dimensioni: List<DimensioneAnalisi> = emptyList()
    private var misure: List<MisuraAnalisi> = emptyList()

    // Stato del modulo (creazione o modifica).
    private var tipoForm: ChartType? = null
    private var graficoInModifica: AreaChart? = null
    private var aggiornandoForm = false
    private var listenerInstallati = false
    private val righe = mutableListOf<UUID>()
    private val colonne = mutableListOf<UUID>()
    private val valori = mutableListOf<UUID>()

    private val nomiDimensioni: Map<UUID, String> get() = dimensioni.associate { it.dimensioneId to it.nome }

    private val formArea = Div().apply {
        className = "lbi-qv-form"
        isVisible = false
    }
    private val titolo = TextField("Titolo").apply { setWidthFull() }
    private val ordinamento = ComboBox<AggregateOrder>("Ordinamento").apply {
        setItems(AggregateOrder.entries)
        setItemLabelGenerator {
            when (it) {
                AggregateOrder.DIMENSION -> "Per etichetta (A-Z)"
                AggregateOrder.METRIC_DESC -> "Per valore, decrescente"
                AggregateOrder.METRIC_ASC -> "Per valore, crescente"
            }
        }
        value = AggregateOrder.DIMENSION
        setWidthFull()
    }
    private val limite = IntegerField("Limite righe (vuoto = automatico)").apply { setWidthFull() }
    private val seguiColonne = Checkbox("Segui le Colonne (una serie per valore, es. una per anno)")
    private val evidenziaCali = Checkbox("Evidenzia i cali (rosso) confrontando l'ultima colonna con la precedente")
        .apply { isEnabled = false }

    private val ricercaCampi = TextField().apply {
        placeholder = "Cerca"
        isClearButtonVisible = true
        valueChangeMode = ValueChangeMode.LAZY
        valueChangeTimeout = 200
        setWidthFull()
        addClassName("lbi-qv-search")
    }
    private val elencoCampi = Div().apply { className = "lbi-qv-panel-body" }
    private val contenitoreRighe = Div().apply { className = "lbi-qv-zone" }
    private val contenitoreColonne = Div().apply { className = "lbi-qv-zone" }
    private val contenitoreValori = Div().apply { className = "lbi-qv-zone" }
    private val anteprima = Div().apply { className = "lbi-qv-anteprima" }
    private val carteTipo = mutableMapOf<ChartType, Div>()
    private val griglia = Grid<AreaChart>()

    /** La libreria che disegna i grafici si carica quando la pagina è attaccata al browser. */
    override fun onAttach(attachEvent: com.vaadin.flow.component.AttachEvent) {
        super.onAttach(attachEvent)
        attachEvent.ui.page.addJavaScript("js/echarts.min.js")
    }

    override fun setParameter(event: BeforeEvent, parameter: String) {
        val parti = parameter.split(",")
        areaId = try {
            UUID.fromString(parti[0])
        } catch (e: IllegalArgumentException) {
            Notification.show("Dataset non valido")
            null
        }
        pivotViewId = parti.getOrNull(1)?.let {
            try { UUID.fromString(it) } catch (e: IllegalArgumentException) { null }
        }
        if (pivotViewId == null) {
            val dataset = areaId
            val utente = CurrentUserHolder.get()
            if (dataset != null && utente != null) {
                pivotViewId = pivotViewService.ensureActiveView(utente.userId, dataset).id
            }
        }
        costruisci()
    }

    // ================= Pagina =================

    private fun costruisci() {
        removeAll()
        setSizeFull()
        isPadding = false
        isSpacing = false


        tipoForm = null
        tipoForm = null
        graficoInModifica = null
        carteTipo.clear()

        val dataset = areaId?.let { registryRepository.findAreaById(it) }
        area = dataset
        val aree = registryRepository.findAllAree().sortedBy { it.nome.lowercase() }
        if (dataset == null) {
            val vuota = Div(Span("Dataset non trovato.").apply { className = "lbi-qv-empty" }).apply { className = "lbi-qv-page" }
            val shell = LbiAppShell(menu(null, aree, emptyList(), null), vuota, authService)
            add(shell)
            setFlexGrow(1.0, shell)
            return
        }

        analisi = analisiService.elenco(dataset.id)
        dimensioni = analisiService.dimensioniDisponibili(dataset.id)
        misure = pivotViewId?.let { misuraAnalisiRepository.findByView(it) } ?: emptyList()
        val vista = analisi.firstOrNull { it.id == pivotViewId }

        val contenuto = Div(
            barraAlta(dataset, vista),
            schedeTipo(),
            formArea.also { costruisciForm() },
            pannelloElenco()
        ).apply { className = "lbi-qv-page" }

        val shell = LbiAppShell(
            menu(dataset, aree, analisi, vista), contenuto, authService,
            analysisName = listOfNotNull(dataset.nome, vista?.nome, "Grafici").joinToString(" · ")
        )
        add(shell)
        setFlexGrow(1.0, shell)
        ricaricaElenco()
    }

    private fun barraAlta(dataset: Area, vista: PivotView?): Component {
        val titoloPagina = Div(
            Span("GRAFICI").apply { className = "lbi-qv-title-text" },
            Span("${dataset.nome} · ${vista?.nome ?: ""}").apply { className = "lbi-qv-title-sub" }
        ).apply { className = "lbi-qv-title" }
        val indietro = Button("Torna all'analisi", Icon(VaadinIcon.ARROW_LEFT)) { tornaAllAnalisi() }
            .apply {
                addClassName("lbi-qv-clear")
                addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            }
        return Div(titoloPagina, indietro).apply { className = "lbi-qv-top" }
    }

    private fun tornaAllAnalisi() {
        val dataset = areaId ?: return
        val param = if (pivotViewId != null) "$dataset,$pivotViewId" else dataset.toString()
        getUI().ifPresent { it.navigate(AnalisiView::class.java, param) }
    }

    // ---------- schede dei tipi ----------

    /** Le schede di scelta del tipo, con la miniatura di ogni grafico: aprono il modulo di creazione. */
    private fun schedeTipo(): Component {
        val contenitore = Div().apply { className = "lbi-qv-tipi" }
        ChartType.entries.forEach { tipo ->
            val miniatura = Div().apply { element.setProperty("innerHTML", chartTypeIcon(tipo)) }
            val scheda = Div(miniatura, Span(chartTypeLabel(tipo)).apply { className = "lbi-qv-tipo-nome" })
                .apply {
                    className = "lbi-qv-tipo"
                    addClickListener { apriForm(tipo, null) }
                }
            carteTipo[tipo] = scheda
            contenitore.add(scheda)
        }
        return Div(Div(Span("Nuovo grafico: scegli il tipo")).apply { className = "lbi-qv-panel-title" }, contenitore)
            .apply { className = "lbi-qv-panel" }
    }

    private fun evidenziaTipo() {
        carteTipo.forEach { (tipo, scheda) -> scheda.classNames.set("lbi-qv-tipo-selezionato", tipo == tipoForm) }
    }

    // ---------- modulo ----------

    private fun costruisciForm() {
        formArea.removeAll()
        // I campi del modulo vivono quanto la pagina: i listener si installano una sola volta.
        if (!listenerInstallati) {
            listenerInstallati = true
            ricercaCampi.addValueChangeListener { aggiornaCampi() }
            seguiColonne.addValueChangeListener {
                evidenziaCali.isEnabled = it.value
                if (!it.value) evidenziaCali.value = false
                if (!aggiornandoForm) aggiornaAnteprima()
            }
            titolo.addValueChangeListener { if (!aggiornandoForm) aggiornaAnteprima() }
            ordinamento.addValueChangeListener { if (!aggiornandoForm) aggiornaAnteprima() }
            limite.addValueChangeListener { if (!aggiornandoForm) aggiornaAnteprima() }
            evidenziaCali.addValueChangeListener { if (!aggiornandoForm) aggiornaAnteprima() }
        }

        val campi = Div(Div(Span("Campi")).apply { className = "lbi-qv-panel-title" }, ricercaCampi, elencoCampi)
            .apply { className = "lbi-qv-panel lbi-qv-left-panel" }

        val zone = Div(
            pannelloZona("Righe", contenitoreRighe),
            pannelloZona("Colonne", contenitoreColonne),
            pannelloZona("Misure", contenitoreValori)
        ).apply { className = "lbi-qv-zones" }

        val opzioni = Div(
            Div(Span("Grafico")).apply { className = "lbi-qv-panel-title" },
            Div(titolo, ordinamento, limite, seguiColonne, evidenziaCali).apply { className = "lbi-qv-opzioni" }
        ).apply { className = "lbi-qv-panel" }

        val anteprimaPannello = Div(Div(Span("Anteprima")).apply { className = "lbi-qv-panel-title" }, anteprima)
            .apply { className = "lbi-qv-panel" }

        val annulla = Button("Annulla") { chiudiForm() }
        val salva = Button("Salva", Icon(VaadinIcon.CHECK)) { salvaGrafico() }
            .apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        val pulsanti = Div(annulla, salva).apply { className = "lbi-qv-form-buttons" }

        val centro = Div(zone, opzioni, anteprimaPannello, pulsanti).apply { className = "lbi-qv-boxes lbi-qv-analisi-centro" }
        formArea.add(Div(campi, centro).apply { className = "lbi-qv-body" })
    }

    private fun apriForm(tipo: ChartType, esistente: AreaChart?) {
        if (misure.isEmpty()) {
            Notification.show("L'analisi non ha misure: aggiungine una dalla pagina Analisi con + Misura", 5000, Notification.Position.MIDDLE)
            return
        }
        aggiornandoForm = true
        try {
            tipoForm = tipo
            graficoInModifica = esistente
            righe.clear(); colonne.clear(); valori.clear()
            val misureIds = misure.map { it.id }.toSet()
            if (esistente != null) {
                righe += esistente.pivotRows
                colonne += esistente.pivotColumns
                valori += chartService.getMetricheDelGrafico(esistente.id).sortedBy { it.posizione }
                    .map { it.metricaId }.filter { it in misureIds }
                titolo.value = esistente.titolo
                ordinamento.value = esistente.orderBy
                limite.value = esistente.maxItems
                seguiColonne.value = esistente.followsColumns
                evidenziaCali.value = esistente.highlightDecline
            } else {
                titolo.value = ""
                ordinamento.value = AggregateOrder.DIMENSION
                limite.value = null
                seguiColonne.value = false
                evidenziaCali.value = false
                // Come partenza: righe, colonne e misure dell'analisi da cui si arriva.
                pivotViewId?.let { vistaId ->
                    pivotViewService.findById(vistaId)?.let { vista ->
                        righe += vista.pivotRows
                        colonne += vista.pivotColumns
                        valori += vista.pivotValues.filter { it in misureIds }
                    }
                }
            }
        } finally {
            aggiornandoForm = false
        }
        formArea.isVisible = true
        evidenziaTipo()
        aggiornaCampi()
        aggiornaZone()
        aggiornaAnteprima()
    }

    private fun chiudiForm() {
        formArea.isVisible = false
        tipoForm = null
        graficoInModifica = null
        evidenziaTipo()
        anteprima.removeAll()
    }

    private fun pannelloZona(testo: String, contenitore: Div): Component =
        Div(Div(Span(testo)).apply { className = "lbi-qv-panel-title" }, contenitore)
            .apply { className = "lbi-qv-panel lbi-qv-zone-panel" }

    /** I campi dell'analisi per tabella, con i pulsanti per metterli in Righe o in Colonne, e le misure da usare. */
    private fun aggiornaCampi() {
        elencoCampi.removeAll()
        val filtro = ricercaCampi.value.orEmpty().trim().lowercase()
        val visibili = dimensioni.filter { filtro.isEmpty() || it.nome.lowercase().contains(filtro) }

        if (misure.isNotEmpty()) {
            elencoCampi.add(Div(Span("Misure dell'analisi")).apply { className = "lbi-qv-group-title" })
            misure.filter { filtro.isEmpty() || it.nome.lowercase().contains(filtro) }.forEach { misura ->
                val inUso = misura.id in valori
                elencoCampi.add(
                    Div(
                        Span(misura.nome).apply { className = "lbi-qv-field-name" },
                        Button("Misure") { aggiungiMisura(misura.id) }
                    ).apply {
                        className = "lbi-qv-field-row lbi-qv-field-add"
                        classNames.set("lbi-qv-field-selected", inUso)
                    }
                )
            }
        }

        visibili.groupBy { it.tabella }.forEach { (tabella, campiTabella) ->
            elencoCampi.add(Div(Span(tabella)).apply { className = "lbi-qv-group-title" })
            campiTabella.forEach { campo ->
                val inUso = campo.dimensioneId in righe || campo.dimensioneId in colonne
                elencoCampi.add(
                    Div(
                        Span(campo.nome).apply { className = "lbi-qv-field-name" },
                        Button("Righe") { aggiungiDimensione(campo.dimensioneId, true) },
                        Button("Colonne") { aggiungiDimensione(campo.dimensioneId, false) }
                    ).apply {
                        className = "lbi-qv-field-row lbi-qv-field-add"
                        classNames.set("lbi-qv-field-selected", inUso)
                    }
                )
            }
        }
        if (visibili.isEmpty() && misure.isEmpty()) elencoCampi.add(Span("Nessun campo").apply { className = "lbi-qv-empty" })
    }

    private fun aggiornaZone() {
        contenitoreRighe.removeAll()
        contenitoreColonne.removeAll()
        contenitoreValori.removeAll()
        riempiDimensioni(contenitoreRighe, righe)
        riempiDimensioni(contenitoreColonne, colonne)

        if (valori.isEmpty()) contenitoreValori.add(Span("Scegli una misura").apply { className = "lbi-qv-empty" })
        val perId = misure.associateBy { it.id }
        valori.forEachIndexed { i, id ->
            val misura = perId[id] ?: return@forEachIndexed
            contenitoreValori.add(
                pillola(misura.nome, { sposta(valori, i, -1) }, { sposta(valori, i, 1) }, { valori.removeAt(i); dopoModifica() })
            )
        }
    }

    private fun riempiDimensioni(contenitore: Div, lista: MutableList<UUID>) {
        if (lista.isEmpty()) contenitore.add(Span("Aggiungi un campo").apply { className = "lbi-qv-empty" })
        lista.forEachIndexed { i, id ->
            contenitore.add(
                pillola(nomiDimensioni[id] ?: "?", { sposta(lista, i, -1) }, { sposta(lista, i, 1) }, { lista.removeAt(i); dopoModifica() })
            )
        }
    }

    /** Una voce di zona: nome e pulsanti sposta su, sposta giù, togli. */
    private fun pillola(testo: String, su: () -> Unit, giu: () -> Unit, togli: () -> Unit): Component {
        fun piccolo(simbolo: String, titoloPulsante: String, azione: () -> Unit) = Button(simbolo) { azione() }.apply {
            addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL)
            element.setAttribute("title", titoloPulsante)
        }
        val pulsanti = Div(
            piccolo("▲", "Sposta su") { su() },
            piccolo("▼", "Sposta giù") { giu() },
            piccolo("✕", "Togli") { togli() }
        ).apply { className = "lbi-qv-pill-buttons" }
        return Div(Span(testo).apply { className = "lbi-qv-pill-text" }, pulsanti).apply { className = "lbi-qv-pill" }
    }

    private fun sposta(lista: MutableList<UUID>, indice: Int, verso: Int) {
        val destinazione = indice + verso
        if (destinazione !in lista.indices) return
        val elemento = lista.removeAt(indice)
        lista.add(destinazione, elemento)
        dopoModifica()
    }

    private fun aggiungiDimensione(id: UUID, nelleRighe: Boolean) {
        righe.remove(id)
        colonne.remove(id)
        if (nelleRighe) righe.add(id) else colonne.add(id)
        dopoModifica()
    }

    private fun aggiungiMisura(id: UUID) {
        if (id !in valori) valori.add(id)
        dopoModifica()
    }

    private fun dopoModifica() {
        aggiornaCampi()
        aggiornaZone()
        aggiornaAnteprima()
    }

    // ---------- anteprima e salvataggio ----------

    /** Le selezioni correnti dell'utente per questo dataset: le stesse di Dataset e Analisi. */
    private fun selezioni(): Map<UUID, Set<Long>> {
        val dataset = areaId ?: return emptyMap()
        val utente = CurrentUserHolder.get() ?: return emptyMap()
        return userPivotStateRepository.find(utente.userId, dataset)?.selections ?: emptyMap()
    }

    private fun bozza(tipo: ChartType): AreaChart? {
        val dataset = areaId ?: return null
        val vistaId = pivotViewId ?: return null
        return AreaChart(
            id = graficoInModifica?.id ?: UUID.randomUUID(),
            areaId = dataset,
            pivotViewId = vistaId,
            titolo = titolo.value?.trim()?.ifBlank { "Anteprima" } ?: "Anteprima",
            tipo = tipo,
            orderBy = ordinamento.value ?: AggregateOrder.DIMENSION,
            maxItems = limite.value,
            posizione = graficoInModifica?.posizione ?: 0,
            followsColumns = seguiColonne.value,
            highlightDecline = evidenziaCali.value,
            pivotRows = righe.toList(),
            pivotColumns = colonne.toList()
        )
    }

    private fun aggiornaAnteprima() {
        anteprima.removeAll()
        val tipo = tipoForm ?: return
        if (righe.isEmpty() || valori.isEmpty()) {
            anteprima.add(Span("Aggiungi almeno un campo alle Righe e una misura per vedere l'anteprima.").apply { className = "lbi-qv-empty" })
            return
        }
        val provvisorio = bozza(tipo) ?: return
        try {
            val esito = chartService.getChartDataForPreview(
                provvisorio, valori.toList(), dimensioni.map { it.dimensioneId }.toSet(), selezioni()
            )
            when (esito) {
                is ChartResult.Ready -> {
                    val grafico = EChartComponent()
                    anteprima.add(grafico)
                    grafico.render(esito.data)
                }
                is ChartResult.Incoherent -> anteprima.add(Span(esito.reason).apply { className = "lbi-qv-empty" })
            }
        } catch (e: Exception) {
            anteprima.add(Span("Impossibile calcolare l'anteprima: ${e.message}").apply { className = "lbi-qv-empty" })
        }
    }

    private fun salvaGrafico() {
        val tipo = tipoForm ?: return
        val dataset = areaId ?: return
        val nome = titolo.value?.trim()
        if (nome.isNullOrBlank()) {
            Notification.show("Il titolo è obbligatorio", 4000, Notification.Position.MIDDLE)
            return
        }
        if (righe.isEmpty()) {
            Notification.show("Metti almeno un campo nelle Righe", 4000, Notification.Position.MIDDLE)
            return
        }
        if (valori.isEmpty()) {
            Notification.show("Scegli almeno una misura", 4000, Notification.Position.MIDDLE)
            return
        }
        if (seguiColonne.value && valori.size > 1) {
            Notification.show(
                "\"Segui le Colonne\" richiede una sola misura: togline alcune o disattiva l'opzione",
                5000, Notification.Position.MIDDLE
            )
            return
        }
        try {
            val esistente = graficoInModifica
            if (esistente == null) {
                chartService.create(
                    areaId = dataset,
                    pivotViewId = pivotViewId ?: error("Analisi non indicata"),
                    titolo = nome,
                    tipo = tipo,
                    metricaIds = valori.toList(),
                    pivotRows = righe.toList(),
                    pivotColumns = colonne.toList(),
                    orderBy = ordinamento.value ?: AggregateOrder.DIMENSION,
                    maxItems = limite.value,
                    followsColumns = seguiColonne.value,
                    highlightDecline = evidenziaCali.value
                )
                Notification.show("Grafico creato", 3000, Notification.Position.BOTTOM_END)
            } else {
                chartService.update(
                    esistente.copy(
                        titolo = nome,
                        orderBy = ordinamento.value ?: esistente.orderBy,
                        maxItems = limite.value,
                        followsColumns = seguiColonne.value,
                        highlightDecline = evidenziaCali.value,
                        pivotRows = righe.toList(),
                        pivotColumns = colonne.toList()
                    ),
                    valori.toList()
                )
                Notification.show("Grafico aggiornato", 3000, Notification.Position.BOTTOM_END)
            }
            chiudiForm()
            ricaricaElenco()
        } catch (e: Exception) {
            Notification.show("Errore: ${e.message}", 5000, Notification.Position.MIDDLE)
        }
    }

    // ---------- elenco dei grafici ----------

    private fun pannelloElenco(): Component {
        griglia.apply {
            removeAllColumns()
            setWidthFull()
            height = "260px"
            addColumn { it.titolo }.setHeader("Titolo").setAutoWidth(true)
            addColumn { chartTypeLabel(it.tipo) }.setHeader("Tipo").setAutoWidth(true)
            addColumn { grafico ->
                val perId = misure.associateBy { it.id }
                chartService.getMetricheDelGrafico(grafico.id).mapNotNull { perId[it.metricaId]?.nome }.joinToString(", ")
            }.setHeader("Misure").setAutoWidth(true)
            addComponentColumn { grafico ->
                Div(
                    Button("Modifica") { apriForm(grafico.tipo, grafico) },
                    Button("Elimina") { confermaElimina(grafico) }
                ).apply { className = "lbi-qv-pill-buttons" }
            }.setHeader("")
        }
        return Div(Div(Span("Grafici dell'analisi")).apply { className = "lbi-qv-panel-title" }, griglia)
            .apply { className = "lbi-qv-panel" }
    }

    private fun ricaricaElenco() {
        griglia.setItems(pivotViewId?.let { chartService.findByView(it) } ?: emptyList())
    }

    private fun confermaElimina(grafico: AreaChart) {
        val dialog = Dialog().apply {
            headerTitle = "Eliminare \"${grafico.titolo}\"?"
            width = "440px"
        }
        dialog.add(Span("Il grafico sarà rimosso definitivamente."))
        dialog.footer.add(
            Button("Annulla") { dialog.close() },
            Button("Elimina") {
                chartService.delete(grafico.id)
                if (graficoInModifica?.id == grafico.id) chiudiForm()
                ricaricaElenco()
                dialog.close()
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        )
        dialog.open()
    }

    // ================= Menu =================

    private fun menu(
        dataset: Area?,
        aree: List<Area>,
        elencoAnalisi: List<PivotView>,
        attiva: PivotView?
    ): List<LbiSidebarMenu.MenuGroup> {
        val id = dataset?.id
        val gruppi = mutableListOf(
            LbiSidebarMenu.MenuGroup(
                "Dataset",
                aree.map { a ->
                    LbiSidebarMenu.MenuEntry(a.nome, icon = VaadinIcon.TABLE) {
                        getUI().ifPresent { it.navigate(DatasetFiltriView::class.java, a.id.toString()) }
                    }
                },
                icon = VaadinIcon.DATABASE
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Analisi di ${dataset?.nome ?: ""}",
                entries = if (id == null) emptyList() else elencoAnalisi.map { v ->
                    LbiSidebarMenu.MenuEntry(v.nome, icon = VaadinIcon.TABLE) {
                        getUI().ifPresent { it.navigate(AnalisiView::class.java, "$id,${v.id}") }
                    }
                },
                icon = VaadinIcon.CHART
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Grafici",
                entries = listOf(LbiSidebarMenu.MenuEntry("Gestisci grafici", enabled = false, icon = VaadinIcon.PIE_CHART) {}),
                active = true,
                icon = VaadinIcon.PIE_CHART
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Report",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Stampe", icon = VaadinIcon.PRINT) { Notification.show("Funzione in arrivo") }
                ),
                icon = VaadinIcon.PRINT
            )
        )
        if (adminGuard.isAdmin()) {
            gruppi.add(
                LbiSidebarMenu.MenuGroup(
                    label = "Amministrazione",
                    entries = listOf(
                        LbiSidebarMenu.MenuEntry("Tabelle importate", icon = VaadinIcon.DATABASE) {
                            getUI().ifPresent { it.navigate(TabelleImportateView::class.java) }
                        },
                        LbiSidebarMenu.MenuEntry("+ Nuovo dataset", icon = VaadinIcon.PLUS) {
                            getUI().ifPresent { it.navigate(NewDatasetView::class.java) }
                        },
                        LbiSidebarMenu.MenuEntry("Modello dati", enabled = id != null, icon = VaadinIcon.TABLE) {
                            id?.let { dsId -> getUI().ifPresent { it.navigate(NewDatasetView::class.java, dsId.toString()) } }
                        },
                        LbiSidebarMenu.MenuEntry("Gestione utenti", icon = VaadinIcon.USERS) {
                            getUI().ifPresent { it.navigate(AdminView::class.java) }
                        }
                    ),
                    icon = VaadinIcon.COG
                )
            )
        }
        return gruppi
    }

    // ================= Tipi di grafico =================

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
     * grafici veri (ECharts).
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
}