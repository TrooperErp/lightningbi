package com.lightning.bi.ui

import com.lightning.bi.model.Area
import com.lightning.bi.model.MisuraAnalisi
import com.lightning.bi.model.PivotView
import com.lightning.bi.model.TipoAggregazione
import com.lightning.bi.repository.RegistryRepository
import com.lightning.bi.repository.UserPivotStateRepository
import com.lightning.bi.service.AccessoDatasetService
import com.lightning.bi.service.AnalisiService
import com.lightning.bi.service.CalendarioService
import com.lightning.bi.service.CampoMisurabile
import com.lightning.bi.service.ChartService
import com.lightning.bi.service.ContatoreCampo
import com.lightning.bi.service.DimensioneAnalisi
import com.lightning.bi.service.FiltriService
import com.lightning.bi.service.PivotEngine
import com.lightning.bi.service.PivotViewService
import com.lightning.bi.service.PoolQuery
import com.lightning.bi.service.SezioneAccessoService
import com.lightning.bi.view.ChartsPanelUi
import com.lightning.bi.view.ResultsGridUi
import com.lightning.piattaforma.ui.LightningLayout
import com.lightning.piattaforma.ui.PaginaLightning
import com.lightning.piattaforma.view.CurrentUserHolder
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.contextmenu.ContextMenu
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.AfterNavigationObserver
import com.vaadin.flow.router.BeforeEvent
import com.vaadin.flow.router.HasUrlParameter
import com.vaadin.flow.router.OptionalParameter
import com.vaadin.flow.router.Route
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import com.lightning.bi.service.setanalysis.EspressioneSet
import com.lightning.bi.service.setanalysis.Identificatore
import com.lightning.bi.service.setanalysis.SetAnalysisParser
import com.lightning.bi.service.setanalysis.chiaveCampo
import com.vaadin.flow.component.textfield.TextArea
import com.lightning.bi.service.setanalysis.Operatore
import com.lightning.piattaforma.ui.Notifiche

/**
 * Un foglio pivot del dataset (come un foglio di Qlik con una tabella pivot), dentro la shell:
 * campi a sinistra, schede dei fogli, barra delle selezioni, disposizione (Righe, Colonne,
 * Misure), pivot e grafici del foglio. Le selezioni sono quelle del dataset, condivise con Filtri.
 *
 * Rotta "bi/analisi/{dataset}" oppure "bi/analisi/{dataset},{foglio}".
 */
@Route(value = "bi/analisi", layout = LightningLayout::class)
class AnalisiView(
    private val accessoDatasetService: AccessoDatasetService,
    private val analisiService: AnalisiService,
    private val pivotViewService: PivotViewService,
    private val registryRepository: RegistryRepository,
    private val userPivotStateRepository: UserPivotStateRepository,
    private val filtriService: FiltriService,
    private val chartService: ChartService,
    private val calendarioService: CalendarioService,
    private val sezioneAccessoService: SezioneAccessoService,
    private val poolQuery: PoolQuery,
    private val fogliBi: FogliBi
) : Div(), PaginaLightning, HasUrlParameter<String>, AfterNavigationObserver {

    override val moduloId = "bi"

    private val log = LoggerFactory.getLogger(AnalisiView::class.java)

    private var area: Area? = null
    private var vista: PivotView? = null
    private var fogli: List<Foglio> = emptyList()
    private var dimensioni: List<DimensioneAnalisi> = emptyList()
    private var misure: List<MisuraAnalisi> = emptyList()
    private val righe = mutableListOf<UUID>()
    private val colonne = mutableListOf<UUID>()
    private val ordineMisure = mutableListOf<UUID>()
    private var stato: StatoSelezioni? = null
    private var campi: PannelloCampi? = null
    private var generazione = 0
    private var espanso = false
    private val nomi: Map<UUID, String> get() = dimensioni.associate { it.dimensioneId to it.nome }

    private val zonaRighe = Div().apply { className = "lt-zona-voci" }
    private val zonaColonne = Div().apply { className = "lt-zona-voci" }
    private val zonaMisure = Div().apply { className = "lt-zona-voci" }

    private val risultato = ResultsGridUi { calendarioService.formatta(it) }.apply {
        resultsGrid.setSizeFull()
        root.setSizeFull()
    }
    private val grafici = ChartsPanelUi()
    private val messaggio = Span().apply { className = "lt-vuoto lt-box-nota" }
    private val infoPivot = Span().apply { className = "lt-box-info" }
    private val espandi = Button("Espandi tutto") { espandiTutto() }.apply { addThemeName("tertiary") }

    private var boxGrafici: Div? = null
    private val mostraNascondi = Button(Icon(VaadinIcon.ANGLE_DOUBLE_RIGHT)) { cambiaGrafici() }.apply {
        addThemeName("tertiary")
        className = "lt-grafici-toggle"
    }

    /** Grafici nascosti: vale per tutta la sessione (cambiando foglio restano nascosti). */
    private var graficiNascosti: Boolean
        get() = com.vaadin.flow.server.VaadinSession.getCurrent()?.getAttribute("lt-grafici-nascosti") == true
        set(valore) { com.vaadin.flow.server.VaadinSession.getCurrent()?.setAttribute("lt-grafici-nascosti", valore) }

    init {
        className = "lt-pagina-bi"
    }

    /** La libreria dei grafici si carica quando la pagina è nel browser. */
    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        attachEvent.ui.page.addJavaScript("js/echarts.min.js")
    }

    override fun setParameter(event: BeforeEvent, @OptionalParameter parameter: String?) {
        val aree = accessoDatasetService.areeVisibili().sortedBy { it.nome.lowercase() }
        val parti = parameter?.split(',').orEmpty()
        val scelta = parti.getOrNull(0)?.let { id -> aree.firstOrNull { it.id.toString() == id } } ?: aree.firstOrNull()
        val vistaRichiesta = parti.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        costruisci(scelta, vistaRichiesta)
    }

    override fun afterNavigation(event: AfterNavigationEvent) {
        layout()?.apply {
            impostaPercorso(percorso())
            impostaPannello(pannello())
        }
        ricalcolaTutto()
    }

    override fun percorso() = listOfNotNull(area?.nome, vista?.nome)

    override fun pannello(): Component? = campi

    private fun layout() = findAncestor(LightningLayout::class.java)

    // ================= Costruzione =================

    private fun costruisci(scelta: Area?, vistaRichiesta: UUID?) {
        removeAll()
        area = scelta
        vista = null
        campi = null
        stato = null
        espanso = false
        espandi.text = "Espandi tutto"
        righe.clear(); colonne.clear(); ordineMisure.clear()

        if (scelta == null) {
            fogli = emptyList()
            add(Div(Span("Non hai ancora accesso a nessun dataset.").apply { className = "lt-vuoto" }).apply { className = "lt-pagina" })
            return
        }

        val utente = CurrentUserHolder.get()
        val elenco = analisiService.elenco(scelta.id)
        val v = elenco.firstOrNull { it.id == vistaRichiesta }
            ?: utente?.let { pivotViewService.ensureActiveView(it.userId, scelta.id) }
            ?: elenco.firstOrNull()
        if (v != null && utente != null) pivotViewService.setActiveView(utente.userId, scelta.id, v.id)
        vista = v
        fogli = fogliBi.di(scelta.id)

        if (v == null) {
            add(Div(Span("Questo dataset non ha ancora fogli pivot.").apply { className = "lt-vuoto" }).apply { className = "lt-pagina" })
            return
        }

        dimensioni = analisiService.dimensioniDisponibili(scelta.id)
        caricaAnalisi()

        val st = StatoSelezioni(scelta.id, filtriService, userPivotStateRepository, registryRepository).also { it.carica() }
        stato = st
        campi = PannelloCampi(scelta.id, filtriService, st, dimensioni, ::zonaDi, ::metti)

        val barraFogli = BarraFogli(scelta.id, fogli, v.id.toString(), fogliBi).apply {

            aggiungiAzione(menuFoglio(scelta, v))
        }
        add(
            barraFogli,
            BarraSelezioni(st, filtriService) { nomi[it] ?: "?" },
            disposizione(),
            areaLavoro(scelta, v)
        )
        aggiornaZone()
        st.alCambio { cambio -> if (cambio == StatoSelezioni.Cambio.DATI) ricalcolaTutto() }
    }

    // ================= Barra dei fogli: Esporta e azioni del foglio =================

    /** "Esporta ▾" sulla testata della pivot: è chiaro che cosa si esporta. */
    private fun menuEsporta(): Component {
        val pulsante = Button("Esporta", Icon(VaadinIcon.DOWNLOAD_ALT)).apply {
            addThemeName("tertiary")
            className = "lt-esporta"
        }
        ContextMenu(pulsante).apply {
            isOpenOnClick = true
            listOf("Excel (.xlsx)", "CSV", "Copia negli appunti", "PDF").forEach { voce ->
                addItem("$voce · in arrivo").isEnabled = false
            }
        }
        return pulsante
    }

    private fun menuFoglio(a: Area, v: PivotView): Component =
        MenuBar().apply {
            className = "lt-menu-foglio"
            val radice = addItem(Icon(VaadinIcon.ELLIPSIS_DOTS_V))
            radice.element.setAttribute("aria-label", "Azioni sul foglio")
            radice.subMenu.addItem("Rinomina foglio…") {
                Finestre.chiediNome("Rinomina foglio", v.nome) { nome ->
                    try {
                        analisiService.rinomina(v.id, nome)
                        UI.getCurrent().navigate(RotteBi.analisi(a.id, v.id))
                    } catch (e: Exception) {
                        Notifiche.errore(log, e, "rinominare il foglio")
                    }
                }
            }
            radice.subMenu.addItem("Elimina foglio…") { confermaElimina(a, v) }
        }

    private fun confermaElimina(a: Area, v: PivotView) {
        val testo = Span("Il foglio \"${v.nome}\", le sue misure e i suoi grafici verranno eliminati. Le selezioni e il dataset non cambiano.")
        Finestre.apri("Eliminare \"${v.nome}\"?", testo, "Elimina", pericolo = true) {
            try {
                if (!analisiService.elimina(a.id, v.id)) {
                    Notification.show("È l'ultimo foglio pivot del dataset: non si può eliminare", 5000, Notification.Position.MIDDLE)
                } else {
                    UI.getCurrent().navigate(RotteBi.filtri(a.id))
                }
            } catch (e: Exception) {
                Notifiche.errore(log, e, "eliminare il foglio")
            }
            true
        }
    }

    // ================= Disposizione: Righe, Colonne, Misure =================

    private fun disposizione(): Component {
        fun zona(titolo: String, voci: Div, extra: Component? = null) =
            Div(Span(titolo).apply { className = "lt-zona-titolo" }, voci).apply {
                className = "lt-zona"
                extra?.let { add(it) }
            }
        val nuovaMisura = NativeButton("+ Misura") { apriMisura(null) }.apply { className = "lt-zona-aggiungi" }
        return Div(
            zona("RIGHE", zonaRighe),
            zona("COLONNE", zonaColonne),
            zona("MISURE", zonaMisure, nuovaMisura)
        ).apply {
            className = "lt-disposizione"
            element.setAttribute("aria-label", "Disposizione della pivot")
        }
    }

    private fun zonaDi(id: UUID): Zona? = when (id) {
        in righe -> Zona.RIGHE
        in colonne -> Zona.COLONNE
        else -> null
    }

    private fun metti(id: UUID, zona: Zona?) {
        righe.remove(id)
        colonne.remove(id)
        when (zona) {
            Zona.RIGHE -> righe.add(id)
            Zona.COLONNE -> colonne.add(id)
            null -> {}
        }
        salvaDisposizione()
    }

    private fun aggiornaZone() {
        listOf(zonaRighe to righe, zonaColonne to colonne).forEach { (contenitore, lista) ->
            contenitore.removeAll()
            if (lista.isEmpty()) contenitore.add(Span("Aggiungi un campo dal pannello Campi (⋮)").apply { className = "lt-vuoto lt-zona-vuota" })
            lista.forEachIndexed { i, id -> contenitore.add(voceDimensione(id, i, lista)) }
        }
        zonaMisure.removeAll()
        val perId = misure.associateBy { it.id }
        if (ordineMisure.isEmpty()) zonaMisure.add(Span("Nessuna misura").apply { className = "lt-vuoto lt-zona-vuota" })
        ordineMisure.forEachIndexed { i, id -> perId[id]?.let { zonaMisure.add(voceMisura(it, i)) } }
        campi?.aggiornaZone()
    }

    /** Una voce di Righe o Colonne: clic sul nome per spostarla, × per toglierla. */
    private fun voceDimensione(id: UUID, indice: Int, lista: MutableList<UUID>): Component {
        val nelleRighe = lista === righe
        val testo = NativeButton(nomi[id] ?: "?").apply { className = "lt-voce-testo" }
        ContextMenu(testo).apply {
            isOpenOnClick = true
            addItem("Sposta prima") { sposta(lista, indice, -1) }.isEnabled = indice > 0
            addItem("Sposta dopo") { sposta(lista, indice, 1) }.isEnabled = indice < lista.lastIndex
            addItem(if (nelleRighe) "Sposta nelle Colonne" else "Sposta nelle Righe") {
                metti(id, if (nelleRighe) Zona.COLONNE else Zona.RIGHE)
            }
            addItem("Togli") { metti(id, null) }
        }
        val togli = NativeButton("×") { metti(id, null) }.apply {
            className = "lt-voce-togli"
            element.setAttribute("aria-label", "Togli ${nomi[id] ?: ""}")
        }
        return Div(testo, togli).apply { className = "lt-voce lt-voce-dim" }
    }

    /** Una misura: clic per modificarla o spostarla, × per eliminarla. */
    private fun voceMisura(misura: MisuraAnalisi, indice: Int): Component {
        val conCondizione = misura.condizione != null
        val testo = NativeButton(
            "${misura.nome} · ${etichettaAggregazione(misura.tipo)}" + if (conCondizione) " · ƒ" else ""
        ).apply {
            className = "lt-voce-testo"
            // Passando sopra si legge la condizione intera.
            misura.condizione?.let { element.setAttribute("title", "Condizione: $it") }
        }
        ContextMenu(testo).apply {
            isOpenOnClick = true
            addItem("Modifica…") { apriMisura(misura) }
            addItem("Sposta prima") { sposta(ordineMisure, indice, -1) }.isEnabled = indice > 0
            addItem("Sposta dopo") { sposta(ordineMisure, indice, 1) }.isEnabled = indice < ordineMisure.lastIndex
            addItem("Elimina") { eliminaMisura(misura) }
        }
        val togli = NativeButton("×") { eliminaMisura(misura) }.apply {
            className = "lt-voce-togli"
            element.setAttribute("aria-label", "Elimina la misura ${misura.nome}")
        }
        return Div(testo, togli).apply { className = "lt-voce lt-voce-misura" }
    }

    private fun sposta(lista: MutableList<UUID>, indice: Int, verso: Int) {
        val destinazione = indice + verso
        if (destinazione !in lista.indices) return
        lista.add(destinazione, lista.removeAt(indice))
        salvaDisposizione()
    }

    /** Salva la disposizione e ricalcola pivot e grafici. */
    private fun salvaDisposizione() {
        val v = vista ?: return
        try {
            vista = analisiService.impostaDisposizione(v.id, righe.toList(), colonne.toList(), ordineMisure.toList())
        } catch (e: Exception) {
            Notifiche.errore(log, e, "salvare la disposizione")
        }
        caricaAnalisi()
        aggiornaZone()
        ricalcola()
    }

    /** Rilegge righe, colonne e misure del foglio. */
    private fun caricaAnalisi() {
        val v = vista ?: return
        val completa = analisiService.carica(v.id) ?: return
        vista = completa.vista
        misure = completa.misure
        righe.clear(); righe.addAll(completa.vista.pivotRows)
        colonne.clear(); colonne.addAll(completa.vista.pivotColumns)
        ordineMisure.clear(); ordineMisure.addAll(completa.vista.pivotValues.filter { id -> misure.any { it.id == id } })
    }

    // ================= Area di lavoro: pivot e grafici =================

    private fun areaLavoro(a: Area, v: PivotView): Component {
        val testaPivot = Div(
            Span("Pivot").apply { className = "lt-box-nome" },
            infoPivot,
            menuEsporta(),
            Div().apply { className = "lt-spazio" },
            espandi
        ).apply { className = "lt-box-testa" }
        val pivot = Div(testaPivot, messaggio, Div(risultato.root).apply { className = "lt-pivot-corpo" })
            .apply { className = "lt-box lt-box-pivot" }

        val gestisci = Button("Gestisci grafici") { UI.getCurrent().navigate(RotteBi.grafici(a.id, v.id)) }
            .apply { addThemeName("tertiary") }
        val testaGrafici = Div(
            Span("Grafici").apply { className = "lt-box-nome lt-grafici-titolo" },
            Div().apply { className = "lt-spazio" },
            gestisci.apply { addClassName("lt-grafici-gestisci") },
            mostraNascondi
        ).apply { className = "lt-box-testa" }
        val boxGrafici = Div(testaGrafici, Div(grafici.root).apply { className = "lt-grafici-corpo" })
            .apply { className = "lt-box lt-box-grafici" }
        this.boxGrafici = boxGrafici
        applicaGrafici()

        return Div(pivot, boxGrafici).apply { className = "lt-area-lavoro" }
    }

    private fun cambiaGrafici() {
        graficiNascosti = !graficiNascosti
        applicaGrafici()
        // Riaperti: si calcolano adesso (da nascosti non si calcolano).
        if (!graficiNascosti) vista?.let { aggiornaGrafici(it, stato?.selezioni ?: emptyMap()) }
    }

    private fun applicaGrafici() {
        val nascosti = graficiNascosti
        boxGrafici?.classNames?.set("lt-nascosto", nascosti)
        mostraNascondi.icon = Icon(if (nascosti) VaadinIcon.ANGLE_DOUBLE_LEFT else VaadinIcon.ANGLE_DOUBLE_RIGHT)
        val testo = if (nascosti) "Mostra i grafici" else "Nascondi i grafici"
        mostraNascondi.setTooltipText(testo)
        mostraNascondi.element.setAttribute("aria-label", testo)
    }

    private fun espandiTutto() {
        @Suppress("UNCHECKED_CAST")
        val dati = risultato.resultsGrid.dataProvider as? TreeDataProvider<PivotEngine.PivotNode> ?: return
        val radici = dati.treeData.rootItems
        if (espanso) risultato.resultsGrid.collapseRecursively(radici, Int.MAX_VALUE)
        else risultato.resultsGrid.expandRecursively(radici, Int.MAX_VALUE)
        espanso = !espanso
        espandi.text = if (espanso) "Comprimi tutto" else "Espandi tutto"
    }

    // ================= Calcolo =================

    /**
     * Dopo un cambio di selezioni (o all'apertura): i contatori dei campi partono subito in
     * parallelo, poi pivot e grafici; i contatori arrivano col push quando sono pronti, se nel
     * frattempo non c'è stato un clic più recente.
     */
    private fun ricalcolaTutto() {
        val a = area ?: return
        val st = stato ?: return
        val gen = ++generazione
        val sel = st.selezioni
        val vincolo = try {
            sezioneAccessoService.vincolo(a.id)
        } catch (e: SecurityException) {
            Notification.show(e.message ?: "Accesso non consentito", 6000, Notification.Position.MIDDLE)
            return
        }
        val ids = dimensioni.map { it.dimensioneId }
        val futuro = poolQuery.avvia { filtriService.contatori(a.id, sel, ids, vincolo) }

        ricalcola()
        campi?.aggiornaListe()

        if (futuro.isDone) {
            mostraContatori(futuro, gen)
        } else {
            val ui = UI.getCurrent()
            futuro.whenComplete { _, _ -> ui?.access { mostraContatori(futuro, gen) } }
        }
    }

    private fun mostraContatori(futuro: CompletableFuture<Map<UUID, ContatoreCampo>>, gen: Int) {
        if (gen != generazione) return
        val contatori = try {
            poolQuery.attendi(futuro)
        } catch (e: Exception) {
            log.warn("Calcolo dei contatori fallito", e)
            emptyMap()
        }
        campi?.mostraContatori(contatori)
    }

    /** Pivot e grafici del foglio con le selezioni correnti. */
    private fun ricalcola() {
        val a = area ?: return
        val v = vista ?: return
        val sel = stato?.selezioni ?: emptyMap()
        val inizio = System.nanoTime()
        messaggio.text = ""
        messaggio.isVisible = false
        infoPivot.text = ""
        espanso = false
        espandi.text = "Espandi tutto"

        if (!graficiNascosti) aggiornaGrafici(v, sel)
        var righeMostrate = 0
        if (ordineMisure.isEmpty()) {
            risultato.clearAll()
            avvisa("Aggiungi almeno una misura per vedere la pivot.")
        } else {
            try {
                val esito = analisiService.calcola(v.id, sel)
                risultato.render(esito.risultato, esito.gerarchia, righe.toList(), nomi)
                righeMostrate = esito.gerarchia.size
                if (righe.isEmpty() && colonne.isEmpty()) avvisa("Totali sul dataset: metti un campo nelle Righe per scomporli.")
            } catch (e: IllegalArgumentException) {
                // Una regola del motore spiegata all'utente (es. troppi valori in Colonne): non è un guasto.
                log.info("Foglio {}: {}", v.id, e.message)
                risultato.clearAll()
                avvisa(e.message ?: "La disposizione scelta non si può calcolare.")
            } catch (e: Exception) {
                risultato.clearAll()
                avvisa(Notifiche.testo(log, e, "calcolare il foglio") + ". Se il dataset non è stato sincronizzato, sincronizzalo.")
            }
        }
        val ms = (System.nanoTime() - inizio) / 1_000_000
        if (ordineMisure.isNotEmpty()) infoPivot.text = "· $righeMostrate righe · $ms ms"
        layout()?.impostaStato(a.nome, v.nome, "Pivot e grafici $ms ms", "${sel.size} campi selezionati")
    }

    private fun avvisa(testo: String) {
        messaggio.text = testo
        messaggio.isVisible = true
    }

    private fun aggiornaGrafici(v: PivotView, sel: Map<UUID, Set<Long>>) {
        val st = stato ?: return
        try {
            val ids = dimensioni.map { it.dimensioneId }
            val dati = chartService.getChartsDataDellAnalisi(v.id, ids.toSet(), sel)
            grafici.render(dati, ids) { chiavi ->
                st.daGrafico(chiavi)?.let { Notification.show(it, 3000, Notification.Position.BOTTOM_START) }
            }
        } catch (e: Exception) {
            log.warn("Calcolo dei grafici del foglio {} fallito", v.id, e)
            grafici.clearAll()
        }
    }

    // ================= Misure =================

    private fun etichettaAggregazione(tipo: TipoAggregazione): String = when (tipo) {
        TipoAggregazione.SUM -> "Somma"
        TipoAggregazione.AVG -> "Media"
        TipoAggregazione.COUNT -> "Conteggio righe"
        TipoAggregazione.COUNT_DISTINCT -> "Conteggio distinti"
        TipoAggregazione.MIN -> "Minimo"
        TipoAggregazione.MAX -> "Massimo"
    }

    private fun eliminaMisura(misura: MisuraAnalisi) {
        val v = vista ?: return
        try {
            analisiService.eliminaMisura(v.id, misura.id)
        } catch (e: Exception) {
            Notifiche.errore(log, e, "eliminare la misura")
        }
        caricaAnalisi()
        aggiornaZone()
        ricalcola()
    }

    /** Aggiunge (esistente = null) o modifica una misura: aggregazione, campo e nome, come in Qlik. */
    /** Aggiunge (esistente = null) o modifica una misura: aggregazione, campo, nome e condizione (set analysis). */
    private fun apriMisura(esistente: MisuraAnalisi?) {
        val a = area ?: return
        val v = vista ?: return
        val fatti = analisiService.tabelleFatti(a.id)
        if (fatti.isEmpty()) {
            Notification.show("Il dataset non ha una tabella Fatti", 5000, Notification.Position.MIDDLE)
            return
        }
        val campiMisurabili = analisiService.campiMisurabili(a.id)

        val nome = TextField("Nome della misura").apply { setWidthFull(); value = esistente?.nome.orEmpty() }
        val tipo = ComboBox<TipoAggregazione>("Aggregazione").apply {
            setWidthFull()
            setItems(TipoAggregazione.entries)
            setItemLabelGenerator { etichettaAggregazione(it) }
            value = esistente?.tipo ?: TipoAggregazione.SUM
        }
        val tabella = ComboBox<Pair<UUID, String>>("Tabella Fatti").apply {
            setWidthFull()
            setItems(fatti)
            setItemLabelGenerator { it.second }
            value = fatti.firstOrNull { it.first == esistente?.areaTabellaId } ?: fatti.first()
            isVisible = fatti.size > 1
        }
        val campo = ComboBox<CampoMisurabile>("Campo").apply {
            setWidthFull()
            setItemLabelGenerator { it.nome }
        }

        fun riempiCampi() {
            val soloNumerici = tipo.value in setOf(
                TipoAggregazione.SUM, TipoAggregazione.AVG, TipoAggregazione.MIN, TipoAggregazione.MAX
            )
            val candidati = campiMisurabili.filter { it.areaTabellaId == tabella.value?.first && (!soloNumerici || it.numerico) }
            campo.setItems(candidati)
            campo.isEnabled = tipo.value != TipoAggregazione.COUNT
            campo.value = candidati.firstOrNull { it.colonna == esistente?.colonna }
        }
        tipo.addValueChangeListener { riempiCampi() }
        tabella.addValueChangeListener { riempiCampi() }
        riempiCampi()

        // ---- Condizione (set analysis): costruttore guidato + testo modificabile ----
        val condizione = TextArea("Condizione (facoltativa)").apply {
            setWidthFull()
            value = esistente?.condizione.orEmpty()
            placeholder = "{\$<Codice_Articolo -= {\"TRASP*\"}>}"
            helperText = "Vuota = la misura segue le selezioni. Sintassi set analysis di Qlik."
        }
        // Nel testo va il nome del campo, non l'etichetta mostrata.
        val nomiCampo = registryRepository.findDimensioniByIds(dimensioni.map { it.dimensioneId }).associate { it.id to it.nome }
        val condCampo = ComboBox<DimensioneAnalisi>("Campo").apply {
            setItems(dimensioni)
            setItemLabelGenerator { "${it.nome} · ${it.tabella}" }
            setWidthFull()
        }
        val condModo = ComboBox<Operatore>("Modo").apply {
            setItems(Operatore.entries)
            setItemLabelGenerator { etichettaOperatore(it) }
            value = Operatore.ESCLUDI
            setWidthFull()
        }
        val condValori = TextField("Valori").apply {
            setWidthFull()
            placeholder = "TRASP*, C-FATRIEPDDT, >=2025"
            helperText = "Separati da virgola. Jolly * e ?, confronti >= <"
        }
        val aggiungiCond = Button("Aggiungi alla condizione", Icon(VaadinIcon.PLUS)) {
            val dim = condCampo.value
            if (dim == null) {
                Notification.show("Scegli il campo", 3000, Notification.Position.MIDDLE)
                return@Button
            }
            try {
                condizione.value = aggiungiModificatore(
                    condizione.value, nomiCampo[dim.dimensioneId] ?: dim.nome, condModo.value ?: Operatore.ESCLUDI, condValori.value
                )
                condValori.clear()
            } catch (e: IllegalArgumentException) {
                Notification.show(e.message ?: "Condizione non valida", 5000, Notification.Position.MIDDLE)
            }
        }.apply { addThemeName("tertiary") }
        val costruttore = Div(condCampo, condModo, condValori, aggiungiCond).apply { className = "lt-cond-costruttore" }

        val contenuto = Div(
            nome, tipo, tabella, campo,
            Span("Condizione").apply { className = "lt-modulo-sezione" },
            costruttore, condizione
        ).apply { className = "lt-modulo-campi" }

        Finestre.apri(if (esistente == null) "Aggiungi misura" else "Modifica misura", contenuto, "Salva") {
            val aggregazione = tipo.value ?: return@apri false
            val fatto = tabella.value?.first ?: return@apri false
            val colonnaScelta = if (aggregazione == TipoAggregazione.COUNT) null else campo.value?.colonna
            val nomeMisura = nome.value.orEmpty().trim().ifEmpty {
                if (aggregazione == TipoAggregazione.COUNT) "Numero righe"
                else "${etichettaAggregazione(aggregazione)} ${campo.value?.nome.orEmpty()}".trim()
            }
            val testoCondizione = condizione.value?.trim()?.ifEmpty { null }
            try {
                if (esistente == null) {
                    analisiService.aggiungiMisura(v.id, nomeMisura, aggregazione, fatto, colonnaScelta, testoCondizione)
                } else {
                    analisiService.modificaMisura(esistente.id, nomeMisura, aggregazione, fatto, colonnaScelta, testoCondizione)
                }
            } catch (e: IllegalArgumentException) {
                Notification.show(e.message ?: "Misura non valida", 6000, Notification.Position.MIDDLE)
                return@apri false
            } catch (e: Exception) {
                Notifiche.errore(log, e, "salvare la misura")
                return@apri false
            }
            caricaAnalisi()
            aggiornaZone()
            ricalcola()
            true
        }
    }

    private fun etichettaOperatore(o: Operatore): String = when (o) {
        Operatore.ESCLUDI -> "Escludi questi valori  (-=)"
        Operatore.SOSTITUISCI -> "Solo questi, ignora la selezione  (=)"
        Operatore.INTERSECA -> "Solo questi, con la selezione  (*=)"
        Operatore.AGGIUNGI -> "Aggiungi alla selezione  (+=)"
    }

    /**
     * Aggiunge al testo della condizione il modificatore per [campo] (se il campo c'era già, lo
     * sostituisce) e restituisce il testo canonico. I valori scritti dall'utente diventano:
     * con * ? < > una ricerca o un confronto, un numero resta numero, il resto è un valore esatto.
     *
     * @throws IllegalArgumentException se il testo attuale o i valori non sono validi
     */
    private fun aggiungiModificatore(testo: String?, campo: String, operatore: Operatore, valori: String?): String {
        val attuale = testo?.trim()?.takeIf { it.isNotEmpty() }?.let { SetAnalysisParser.analizza(it) }
            ?: EspressioneSet(Identificatore.CORRENTI, emptyList())
        val elementi = valori.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") { valore ->
            when {
                valore.startsWith("'") || valore.startsWith("\"") -> valore
                valore.any { it in "*?<>" } -> "\"" + valore.replace("\"", "\"\"") + "\""
                valore.matches(Regex("-?\\d+(\\.\\d+)?")) -> valore
                else -> "'" + valore.replace("'", "''") + "'"
            }
        }
        val nuovo = SetAnalysisParser.analizza("{<[${campo.replace("]", "]]")}] ${operatore.testo} {$elementi}>}")
            .modificatori.first()
        val altri = attuale.modificatori.filter { chiaveCampo(it.campo) != chiaveCampo(campo) }
        return attuale.copy(modificatori = altri + nuovo).testo()
    }
}