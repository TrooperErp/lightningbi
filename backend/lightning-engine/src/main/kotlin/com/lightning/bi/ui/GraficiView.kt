package com.lightning.bi.ui

import com.lightning.bi.model.AggregateOrder
import com.lightning.bi.model.Area
import com.lightning.bi.model.AreaChart
import com.lightning.bi.model.ChartResult
import com.lightning.bi.model.ChartType
import com.lightning.bi.model.MisuraAnalisi
import com.lightning.bi.model.PivotView
import com.lightning.bi.repository.RegistryRepository
import com.lightning.bi.repository.UserPivotStateRepository
import com.lightning.bi.service.AccessoDatasetService
import com.lightning.bi.service.AnalisiService
import com.lightning.bi.service.ChartService
import com.lightning.bi.service.DimensioneAnalisi
import com.lightning.bi.service.FiltriService
import com.lightning.bi.service.PivotViewService
import com.lightning.bi.service.PoolQuery
import com.lightning.bi.service.SezioneAccessoService
import com.lightning.bi.view.EChartComponent
import com.lightning.piattaforma.ui.LightningLayout
import com.lightning.piattaforma.ui.PaginaLightning
import com.lightning.piattaforma.view.CurrentUserHolder
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.contextmenu.ContextMenu
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.textfield.IntegerField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.AfterNavigationObserver
import com.vaadin.flow.router.BeforeEvent
import com.vaadin.flow.router.HasUrlParameter
import com.vaadin.flow.router.OptionalParameter
import com.vaadin.flow.router.Route
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * I grafici di un foglio pivot, dentro la shell: elenco dei grafici del foglio, scelta del
 * tipo per crearne uno nuovo, editor con Righe / Colonne / Misure, opzioni e anteprima.
 * Un grafico appartiene al foglio e usa le SUE misure (si scrivono nel foglio con + Misura).
 *
 * Rotta "bi/grafici/{dataset},{foglio}".
 */
@Route(value = "bi/grafici", layout = LightningLayout::class)
class GraficiView(
    private val accessoDatasetService: AccessoDatasetService,
    private val analisiService: AnalisiService,
    private val pivotViewService: PivotViewService,
    private val chartService: ChartService,
    private val registryRepository: RegistryRepository,
    private val userPivotStateRepository: UserPivotStateRepository,
    private val filtriService: FiltriService,
    private val sezioneAccessoService: SezioneAccessoService,
    private val poolQuery: PoolQuery
) : Div(), PaginaLightning, HasUrlParameter<String>, AfterNavigationObserver {

    override val moduloId = "bi"

    private val log = LoggerFactory.getLogger(GraficiView::class.java)

    private var area: Area? = null
    private var vista: PivotView? = null
    private var dimensioni: List<DimensioneAnalisi> = emptyList()
    private var misure: List<MisuraAnalisi> = emptyList()
    private var stato: StatoSelezioni? = null
    private var campi: PannelloCampi? = null
    private val nomi: Map<UUID, String> get() = dimensioni.associate { it.dimensioneId to it.nome }

    // Editor (creazione o modifica)
    private var tipo: ChartType? = null
    private var inModifica: AreaChart? = null
    private var caricando = false
    private val righe = mutableListOf<UUID>()
    private val colonne = mutableListOf<UUID>()
    private val valori = mutableListOf<UUID>()

    private val schedeTipo = mutableMapOf<ChartType, NativeButton>()
    private val elenco = Div().apply { className = "lt-grafici-elenco" }
    private val editor = Div().apply { className = "lt-editor-grafico"; isVisible = false }
    private val titoloEditor = Span().apply { className = "lt-box-nome" }
    private val zonaRighe = Div().apply { className = "lt-zona-voci" }
    private val zonaColonne = Div().apply { className = "lt-zona-voci" }
    private val zonaMisure = Div().apply { className = "lt-zona-voci" }
    private val anteprima = Div().apply { className = "lt-anteprima" }
    private var menuMisure: ContextMenu? = null

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
        setWidthFull()
    }
    private val limite = IntegerField("Limite righe (vuoto = automatico)").apply { setWidthFull() }
    private val seguiColonne = Checkbox("Segui le Colonne (una serie per valore, es. una per anno)")
    private val evidenziaCali = Checkbox("Evidenzia i cali (rosso) tra l'ultima colonna e la precedente").apply { isEnabled = false }

    init {
        className = "lt-pagina-bi"
        seguiColonne.addValueChangeListener {
            evidenziaCali.isEnabled = it.value
            if (!it.value) evidenziaCali.value = false
            if (!caricando) aggiornaAnteprima()
        }
        titolo.addValueChangeListener { if (!caricando) aggiornaAnteprima() }
        ordinamento.addValueChangeListener { if (!caricando) aggiornaAnteprima() }
        limite.addValueChangeListener { if (!caricando) aggiornaAnteprima() }
        evidenziaCali.addValueChangeListener { if (!caricando) aggiornaAnteprima() }
    }

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        attachEvent.ui.page.addJavaScript("js/echarts.min.js")
    }

    override fun setParameter(event: BeforeEvent, @OptionalParameter parameter: String?) {
        val aree = accessoDatasetService.areeVisibili()
        val parti = parameter?.split(',').orEmpty()
        val scelta = parti.getOrNull(0)?.let { id -> aree.firstOrNull { it.id.toString() == id } }
        val vistaId = parti.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        costruisci(scelta, vistaId)
    }

    override fun afterNavigation(event: AfterNavigationEvent) {
        layout()?.apply {
            impostaPercorso(percorso())
            impostaPannello(pannello())
            impostaStato(*listOfNotNull(area?.nome, vista?.nome, "Grafici").toTypedArray())
        }
        aggiornaContatori()
    }

    override fun percorso() = listOfNotNull(area?.nome, vista?.nome, "Grafici")

    override fun pannello(): Component? = campi

    private fun layout() = findAncestor(LightningLayout::class.java)

    // ================= Costruzione =================

    private fun costruisci(scelta: Area?, vistaId: UUID?) {
        removeAll()
        area = scelta
        vista = null
        campi = null
        stato = null
        chiudiEditor()

        if (scelta == null) {
            add(Div(Span("Dataset non trovato o non accessibile.").apply { className = "lt-vuoto" }).apply { className = "lt-pagina" })
            return
        }
        val utente = CurrentUserHolder.get()
        val v = vistaId?.let { id -> analisiService.elenco(scelta.id).firstOrNull { it.id == id } }
            ?: utente?.let { pivotViewService.ensureActiveView(it.userId, scelta.id) }
        if (v == null) {
            add(Div(Span("Foglio non trovato.").apply { className = "lt-vuoto" }).apply { className = "lt-pagina" })
            return
        }
        vista = v
        dimensioni = analisiService.dimensioniDisponibili(scelta.id)
        misure = analisiService.carica(v.id)?.misure ?: emptyList()

        val st = StatoSelezioni(scelta.id, filtriService, userPivotStateRepository, registryRepository).also { it.carica() }
        stato = st
        campi = PannelloCampi(scelta.id, filtriService, st, dimensioni, ::zonaDi, ::metti)

        add(
            barra(scelta, v),
            BarraSelezioni(st, filtriService) { nomi[it] ?: "?" },
            boxNuovo(),
            editor.also { costruisciEditor() },
            boxElenco()
        )
        ricaricaElenco()
        st.alCambio { cambio ->
            if (cambio == StatoSelezioni.Cambio.DATI) {
                aggiornaContatori()
                campi?.aggiornaListe()
                aggiornaAnteprima()
            }
        }
    }

    private fun barra(a: Area, v: PivotView): Component {
        val indietro = Button("Torna al foglio", Icon(VaadinIcon.ARROW_LEFT)) {
            UI.getCurrent().navigate(RotteBi.analisi(a.id, v.id))
        }.apply { addThemeName("tertiary") }
        return Div(
            indietro,
            Span("Grafici di «${v.nome}»").apply { className = "lt-barra-titolo" }
        ).apply { className = "lt-barra-fogli lt-barra-grafici" }
    }

    /** Le schede dei tipi: un clic apre l'editor per un grafico nuovo di quel tipo. */
    private fun boxNuovo(): Component {
        val tipi = Div().apply { className = "lt-tipi" }
        ChartType.entries.forEach { t ->
            val scheda = NativeButton().apply {
                className = "lt-tipo"
                add(
                    Div().apply { element.setProperty("innerHTML", iconaTipo(t)) },
                    Span(etichettaTipo(t)).apply { className = "lt-tipo-nome" }
                )
                element.setAttribute("aria-label", "Nuovo grafico: ${etichettaTipo(t)}")
                addClickListener { apriEditor(t, null) }
            }
            schedeTipo[t] = scheda
            tipi.add(scheda)
        }
        val testa = Div(Span("Nuovo grafico").apply { className = "lt-box-nome" }, Span("scegli il tipo").apply { className = "lt-box-info" })
            .apply { className = "lt-box-testa" }
        return Div(testa, tipi).apply { className = "lt-box lt-box-largo" }
    }

    private fun boxElenco(): Component {
        val testa = Div(Span("Grafici del foglio").apply { className = "lt-box-nome" }).apply { className = "lt-box-testa" }
        return Div(testa, elenco).apply { className = "lt-box lt-box-largo" }
    }

    private fun ricaricaElenco() {
        val v = vista ?: return
        elenco.removeAll()
        val grafici = chartService.findByView(v.id)
        if (grafici.isEmpty()) {
            elenco.add(Span("Nessun grafico: scegli un tipo qui sopra per crearne uno.").apply { className = "lt-vuoto lt-box-nota" })
            return
        }
        val perId = misure.associateBy { it.id }
        grafici.forEach { g ->
            val nomiMisure = chartService.getMetricheDelGrafico(g.id).mapNotNull { perId[it.metricaId]?.nome }.joinToString(", ")
            elenco.add(
                Div(
                    Div().apply { className = "lt-grafico-icona"; element.setProperty("innerHTML", iconaTipo(g.tipo)) },
                    Div(
                        Span(g.titolo).apply { className = "lt-grafico-titolo" },
                        Span("${etichettaTipo(g.tipo)} · ${nomiMisure.ifEmpty { "nessuna misura" }}").apply { className = "lt-box-info" }
                    ).apply { className = "lt-grafico-testo" },
                    Button("Modifica") { apriEditor(g.tipo, g) }.apply { addThemeName("tertiary") },
                    Button("Elimina") { confermaElimina(g) }.apply { addThemeName("tertiary"); addThemeName("error") }
                ).apply {
                    className = "lt-grafico-riga"
                    classNames.set("lt-attiva", inModifica?.id == g.id)
                }
            )
        }
    }

    private fun confermaElimina(g: AreaChart) {
        Finestre.apri("Eliminare \"${g.titolo}\"?", Span("Il grafico sarà rimosso definitivamente."), "Elimina", pericolo = true) {
            chartService.delete(g.id)
            if (inModifica?.id == g.id) chiudiEditor()
            ricaricaElenco()
            true
        }
    }

    // ================= Editor =================

    private fun costruisciEditor() {
        editor.removeAll()
        fun zona(t: String, voci: Div, extra: Component? = null) =
            Div(Span(t).apply { className = "lt-zona-titolo" }, voci).apply {
                className = "lt-zona"
                extra?.let { add(it) }
            }
        val aggiungiMisura = NativeButton("+ Misura ▾").apply { className = "lt-zona-aggiungi" }
        menuMisure = ContextMenu(aggiungiMisura).apply { isOpenOnClick = true }
        val disposizione = Div(
            zona("RIGHE", zonaRighe),
            zona("COLONNE", zonaColonne),
            zona("MISURE", zonaMisure, aggiungiMisura)
        ).apply { className = "lt-disposizione lt-disposizione-editor" }

        val salva = Button("Salva", Icon(VaadinIcon.CHECK)) { salva() }.apply { addThemeName("primary") }
        val annulla = Button("Annulla") { chiudiEditor() }
        val opzioni = Div(
            Div(titoloEditor).apply { className = "lt-box-testa" },
            Div(titolo, ordinamento, limite, seguiColonne, evidenziaCali).apply { className = "lt-modulo-campi lt-box-corpo" },
            Div(annulla, salva).apply { className = "lt-box-piede" }
        ).apply { className = "lt-box lt-box-opzioni" }
        val boxAnteprima = Div(
            Div(Span("Anteprima").apply { className = "lt-box-nome" }).apply { className = "lt-box-testa" },
            anteprima
        ).apply { className = "lt-box lt-box-anteprima" }

        editor.add(disposizione, Div(opzioni, boxAnteprima).apply { className = "lt-area-lavoro" })
    }

    private fun apriEditor(t: ChartType, esistente: AreaChart?) {
        val v = vista ?: return
        if (misure.isEmpty()) {
            Notification.show("Il foglio non ha misure: aggiungine una dal foglio con + Misura", 5000, Notification.Position.MIDDLE)
            return
        }
        caricando = true
        try {
            tipo = t
            inModifica = esistente
            righe.clear(); colonne.clear(); valori.clear()
            val ids = misure.map { it.id }.toSet()
            if (esistente != null) {
                righe += esistente.pivotRows
                colonne += esistente.pivotColumns
                valori += chartService.getMetricheDelGrafico(esistente.id).sortedBy { it.posizione }.map { it.metricaId }.filter { it in ids }
                titolo.value = esistente.titolo
                ordinamento.value = esistente.orderBy
                limite.value = esistente.maxItems
                seguiColonne.value = esistente.followsColumns
                evidenziaCali.value = esistente.highlightDecline
            } else {
                // Come partenza: righe, colonne e misure del foglio.
                val attuale = pivotViewService.findById(v.id) ?: v
                righe += attuale.pivotRows
                colonne += attuale.pivotColumns
                valori += attuale.pivotValues.filter { it in ids }
                titolo.value = ""
                ordinamento.value = AggregateOrder.DIMENSION
                limite.value = null
                seguiColonne.value = false
                evidenziaCali.value = false
            }
        } finally {
            caricando = false
        }
        titoloEditor.text = if (esistente == null) "Nuovo grafico · ${etichettaTipo(t)}" else "Modifica «${esistente.titolo}» · ${etichettaTipo(t)}"
        editor.isVisible = true
        schedeTipo.forEach { (k, s) -> s.classNames.set("lt-attiva", k == t && esistente == null) }
        ricaricaElenco()
        aggiornaZone()
        aggiornaAnteprima()
        titolo.focus()
    }

    private fun chiudiEditor() {
        editor.isVisible = false
        tipo = null
        inModifica = null
        anteprima.removeAll()
        righe.clear(); colonne.clear(); valori.clear()
        schedeTipo.values.forEach { it.classNames.remove("lt-attiva") }
        campi?.aggiornaZone()
        if (vista != null) ricaricaElenco()
    }

    private fun zonaDi(id: UUID): Zona? = when {
        tipo == null -> null
        id in righe -> Zona.RIGHE
        id in colonne -> Zona.COLONNE
        else -> null
    }

    /** Dal pannello Campi: mette un campo nel grafico in modifica. */
    private fun metti(id: UUID, zona: Zona?) {
        if (tipo == null) {
            Notification.show("Scegli prima un tipo di grafico o un grafico da modificare", 3000, Notification.Position.BOTTOM_START)
            return
        }
        righe.remove(id)
        colonne.remove(id)
        when (zona) {
            Zona.RIGHE -> righe.add(id)
            Zona.COLONNE -> colonne.add(id)
            null -> {}
        }
        dopoModifica()
    }

    private fun dopoModifica() {
        aggiornaZone()
        aggiornaAnteprima()
    }

    private fun aggiornaZone() {
        listOf(zonaRighe to righe, zonaColonne to colonne).forEach { (contenitore, lista) ->
            contenitore.removeAll()
            if (lista.isEmpty()) contenitore.add(Span("Aggiungi un campo dal pannello Campi (⋮)").apply { className = "lt-vuoto lt-zona-vuota" })
            lista.forEachIndexed { i, id -> contenitore.add(voce(nomi[id] ?: "?", i, lista, "lt-voce-dim")) }
        }
        zonaMisure.removeAll()
        val perId = misure.associateBy { it.id }
        if (valori.isEmpty()) zonaMisure.add(Span("Scegli una misura").apply { className = "lt-vuoto lt-zona-vuota" })
        valori.forEachIndexed { i, id -> perId[id]?.let { zonaMisure.add(voce(it.nome, i, valori, "lt-voce-misura")) } }
        campi?.aggiornaZone()
        aggiornaMenuMisure()
    }

    /** Il menu "+ Misura": le misure del foglio non ancora nel grafico. */
    private fun aggiornaMenuMisure() {
        val menu = menuMisure ?: return
        menu.removeAll()
        if (misure.isEmpty()) menu.addItem("Il foglio non ha misure").isEnabled = false
        misure.forEach { m ->
            menu.addItem(m.nome) {
                if (m.id !in valori) valori.add(m.id)
                dopoModifica()
            }.isEnabled = m.id !in valori
        }
    }

    /** Una voce di zona: clic sul nome per spostarla, × per toglierla. */
    private fun voce(testo: String, indice: Int, lista: MutableList<UUID>, classe: String): Component {
        val nome = NativeButton(testo).apply { className = "lt-voce-testo" }
        ContextMenu(nome).apply {
            isOpenOnClick = true
            addItem("Sposta prima") { sposta(lista, indice, -1) }.isEnabled = indice > 0
            addItem("Sposta dopo") { sposta(lista, indice, 1) }.isEnabled = indice < lista.lastIndex
            addItem("Togli") { lista.removeAt(indice); dopoModifica() }
        }
        val togli = NativeButton("×") { lista.removeAt(indice); dopoModifica() }.apply {
            className = "lt-voce-togli"
            element.setAttribute("aria-label", "Togli $testo")
        }
        return Div(nome, togli).apply { className = "lt-voce $classe" }
    }

    private fun sposta(lista: MutableList<UUID>, indice: Int, verso: Int) {
        val destinazione = indice + verso
        if (destinazione !in lista.indices) return
        lista.add(destinazione, lista.removeAt(indice))
        dopoModifica()
    }

    // ================= Anteprima e salvataggio =================

    private fun bozza(t: ChartType): AreaChart? {
        val a = area ?: return null
        val v = vista ?: return null
        return AreaChart(
            id = inModifica?.id ?: UUID.randomUUID(),
            areaId = a.id,
            pivotViewId = v.id,
            titolo = titolo.value?.trim()?.ifBlank { "Anteprima" } ?: "Anteprima",
            tipo = t,
            orderBy = ordinamento.value ?: AggregateOrder.DIMENSION,
            maxItems = limite.value,
            posizione = inModifica?.posizione ?: 0,
            followsColumns = seguiColonne.value,
            highlightDecline = evidenziaCali.value,
            pivotRows = righe.toList(),
            pivotColumns = colonne.toList()
        )
    }

    private fun aggiornaAnteprima() {
        anteprima.removeAll()
        val t = tipo ?: return
        if (righe.isEmpty() || valori.isEmpty()) {
            anteprima.add(Span("Metti almeno un campo nelle Righe e una misura per vedere l'anteprima.").apply { className = "lt-vuoto lt-box-nota" })
            return
        }
        val provvisorio = bozza(t) ?: return
        try {
            val esito = chartService.getChartDataForPreview(
                provvisorio, valori.toList(), dimensioni.map { it.dimensioneId }.toSet(), stato?.selezioni ?: emptyMap()
            )
            when (esito) {
                is ChartResult.Ready -> {
                    val grafico = EChartComponent { chiavi ->
                        stato?.daGrafico(chiavi)?.let { Notification.show(it, 3000, Notification.Position.BOTTOM_START) }
                    }
                    anteprima.add(grafico)
                    grafico.render(esito.data)
                }
                is ChartResult.Incoherent -> anteprima.add(Span(esito.reason).apply { className = "lt-vuoto lt-box-nota" })
            }
        } catch (e: Exception) {
            log.warn("Anteprima del grafico fallita", e)
            anteprima.add(Span("Impossibile calcolare l'anteprima: ${e.message}").apply { className = "lt-vuoto lt-box-nota" })
        }
    }

    private fun salva() {
        val t = tipo ?: return
        val a = area ?: return
        val v = vista ?: return
        val nome = titolo.value?.trim()
        when {
            nome.isNullOrBlank() -> return avviso("Il titolo è obbligatorio")
            righe.isEmpty() -> return avviso("Metti almeno un campo nelle Righe")
            valori.isEmpty() -> return avviso("Scegli almeno una misura")
            seguiColonne.value && valori.size > 1 ->
                return avviso("\"Segui le Colonne\" richiede una sola misura: togline alcune o disattiva l'opzione")
        }
        try {
            val esistente = inModifica
            if (esistente == null) {
                chartService.create(
                    areaId = a.id,
                    pivotViewId = v.id,
                    titolo = nome!!,
                    tipo = t,
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
                        titolo = nome!!,
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
            chiudiEditor()
        } catch (e: Exception) {
            Notification.show("Errore: ${e.message}", 5000, Notification.Position.MIDDLE)
        }
    }

    private fun avviso(testo: String) {
        Notification.show(testo, 4000, Notification.Position.MIDDLE)
    }

    // ================= Contatori del pannello Campi =================

    private fun aggiornaContatori() {
        val a = area ?: return
        val st = stato ?: return
        try {
            val vincolo = sezioneAccessoService.vincolo(a.id)
            val sel = st.selezioni
            val ids = dimensioni.map { it.dimensioneId }
            campi?.mostraContatori(poolQuery.attendi(poolQuery.avvia { filtriService.contatori(a.id, sel, ids, vincolo) }))
        } catch (e: Exception) {
            log.warn("Calcolo dei contatori fallito per il dataset {}", a.id, e)
        }
    }

    // ================= Tipi di grafico =================

    private fun etichettaTipo(t: ChartType): String = when (t) {
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

    /** Miniature dei tipi, nei colori delle serie del tema. */
    private fun iconaTipo(t: ChartType): String {
        val a = "var(--lt-serie-2)"
        val b = "var(--lt-serie-1)"
        val c = "var(--lt-accent)"
        return when (t) {
            ChartType.BAR -> """<svg viewBox="0 0 64 48" width="44" height="33" aria-hidden="true"><rect x="6" y="24" width="10" height="20" rx="1" style="fill:$a"/><rect x="20" y="12" width="10" height="32" rx="1" style="fill:$b"/><rect x="34" y="18" width="10" height="26" rx="1" style="fill:$a"/><rect x="48" y="6" width="10" height="38" rx="1" style="fill:$b"/></svg>"""
            ChartType.BAR_HORIZONTAL -> """<svg viewBox="0 0 64 48" width="44" height="33" aria-hidden="true"><rect x="4" y="6" width="38" height="8" rx="1" style="fill:$a"/><rect x="4" y="20" width="52" height="8" rx="1" style="fill:$b"/><rect x="4" y="34" width="28" height="8" rx="1" style="fill:$a"/></svg>"""
            ChartType.LINE -> """<svg viewBox="0 0 64 48" width="44" height="33" aria-hidden="true"><polyline points="6,36 20,20 34,28 48,10 58,16" fill="none" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" style="stroke:$a"/></svg>"""
            ChartType.AREA -> """<svg viewBox="0 0 64 48" width="44" height="33" aria-hidden="true"><polygon points="6,36 20,20 34,28 48,10 58,16 58,44 6,44" fill-opacity="0.35" stroke-width="2" style="fill:$a;stroke:$a"/></svg>"""
            ChartType.PIE -> """<svg viewBox="0 0 48 48" width="36" height="36" aria-hidden="true"><circle cx="24" cy="24" r="20" style="fill:$a"/><path d="M24 24 L24 4 A20 20 0 0 1 41.3 34 Z" style="fill:$b"/></svg>"""
            ChartType.DONUT -> """<svg viewBox="0 0 48 48" width="36" height="36" aria-hidden="true"><circle cx="24" cy="24" r="20" style="fill:$a"/><path d="M24 24 L24 4 A20 20 0 0 1 41.3 34 Z" style="fill:$b"/><circle cx="24" cy="24" r="9" style="fill:var(--lt-surface)"/></svg>"""
            ChartType.SCATTER -> """<svg viewBox="0 0 64 48" width="44" height="33" aria-hidden="true"><circle cx="10" cy="34" r="3" style="fill:$a"/><circle cx="20" cy="18" r="3" style="fill:$b"/><circle cx="30" cy="30" r="3" style="fill:$a"/><circle cx="40" cy="12" r="3" style="fill:$b"/><circle cx="48" cy="24" r="3" style="fill:$a"/><circle cx="56" cy="8" r="3" style="fill:$b"/></svg>"""
            ChartType.RADAR -> """<svg viewBox="0 0 48 48" width="36" height="36" aria-hidden="true"><polygon points="24,6 42,18 34,40 14,40 6,18" fill="none" stroke-width="1" style="stroke:var(--lt-border)"/><polygon points="24,14 34,20 30,34 18,34 14,20" fill-opacity="0.4" stroke-width="2" style="fill:$a;stroke:$a"/></svg>"""
            ChartType.MAP -> """<svg viewBox="0 0 64 48" width="44" height="33" aria-hidden="true"><path d="M6 30 L14 12 L26 8 L34 20 L30 34 L46 26 L58 38 L44 44 L20 42 Z" fill-opacity="0.35" stroke-width="1.5" style="fill:$c;stroke:$c"/><circle cx="24" cy="24" r="3" style="fill:$b"/></svg>"""
        }
    }
}