package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.Area
import com.lightningbi.lightning_engine.model.MisuraAnalisi
import com.lightningbi.lightning_engine.model.PivotView
import com.lightningbi.lightning_engine.model.TipoAggregazione
import com.lightningbi.lightning_engine.model.UserPivotState
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.repository.UserPivotStateRepository
import com.lightningbi.lightning_engine.service.AdminGuard
import com.lightningbi.lightning_engine.service.AnalisiService
import com.lightningbi.lightning_engine.service.AuthService
import com.lightningbi.lightning_engine.service.CalendarioService
import com.lightningbi.lightning_engine.service.CampoMisurabile
import com.lightningbi.lightning_engine.service.DimensioneAnalisi
import com.lightningbi.lightning_engine.service.FiltriService
import com.lightningbi.lightning_engine.service.PivotViewService
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dependency.Uses
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.Tabs
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.value.ValueChangeMode
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.AfterNavigationObserver
import com.vaadin.flow.router.BeforeEvent
import com.vaadin.flow.router.HasUrlParameter
import com.vaadin.flow.router.OptionalParameter
import com.vaadin.flow.router.Route
import org.slf4j.LoggerFactory
import java.util.UUID
import com.lightningbi.lightning_engine.service.AccessoDatasetService
import com.lightningbi.lightning_engine.service.ContatoreCampo
import com.lightningbi.lightning_engine.service.StatoValore
import com.lightningbi.lightning_engine.service.ValoreFiltro
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.menubar.MenuBarVariant


/**
 * Pagina "Analisi": come un foglio di Qlik con una pivot. Un dataset ha molte
 * analisi (le schede in alto). Le SELEZIONI non sono dell'analisi: sono
 * dell'utente e del dataset e si fanno nella pagina Dataset; qui si vedono e si
 * possono togliere.
 *
 * - Campi (a sinistra): le dimensioni del dataset, da mettere nelle Righe o
 *   nelle Colonne.
 * - Righe, Colonne, Misure: la disposizione della pivot. Le MISURE si scrivono
 *   qui (campo, aggregazione, nome) e appartengono all'analisi.
 * - La pivot sotto: righe espandibili e colonne a più livelli.
 *
 * Route "analisi", "analisi/{dataset}" e "analisi/{dataset},{analisi}".
 */
@Route("analisi")
@Uses(Icon::class)
class AnalisiView(
    private val analisiService: AnalisiService,
    private val accessoDatasetService: AccessoDatasetService,
    private val pivotViewService: PivotViewService,
    private val userPivotStateRepository: UserPivotStateRepository,
    private val filtriService: FiltriService,
    private val calendarioService: CalendarioService,
    private val adminGuard: AdminGuard,
    private val authService: AuthService,
    private val chartService: com.lightningbi.lightning_engine.service.ChartService,
) : VerticalLayout(), HasUrlParameter<String>, AfterNavigationObserver {

    private val log = LoggerFactory.getLogger(AnalisiView::class.java)

    private var parametro: String? = null

    // Stato della pagina aperta.
    private var area: Area? = null
    private var vista: PivotView? = null
    private var analisi: List<PivotView> = emptyList()
    private var dimensioni: List<DimensioneAnalisi> = emptyList()
    private var misure: List<MisuraAnalisi> = emptyList()
    private val righe = mutableListOf<UUID>()
    private val colonne = mutableListOf<UUID>()
    private val ordineMisure = mutableListOf<UUID>()
    private val selezioni = mutableMapOf<UUID, Set<Long>>()
    private val dialoghiValori = mutableMapOf<UUID, Pair<Dialog, ListaValoriUi>>()

    private val nomiDimensioni: Map<UUID, String> get() = dimensioni.associate { it.dimensioneId to it.nome }

    private val risultato = ResultsGridUi { calendarioService.formatta(it) }.apply {
        resultsGrid.setSizeFull()
        root.setSizeFull()
    }

    private val grafici = ChartsPanelUi()
    private val messaggio = Span().apply { className = "lbi-qv-empty" }
    private val contenitoreRighe = Div().apply { className = "lbi-qv-zone" }
    private val contenitoreColonne = Div().apply { className = "lbi-qv-zone" }
    private val contenitoreMisure = Div().apply { className = "lbi-qv-zone" }
    private val corpoSelezioni = Div()
    private val elencoCampi = Div().apply { className = "lbi-qv-panel-body" }
    private val ricercaCampi = TextField().apply {
        placeholder = "Cerca campo"
        isClearButtonVisible = true
        valueChangeMode = ValueChangeMode.LAZY
        valueChangeTimeout = 200
        setWidthFull()
        addClassName("lbi-qv-search")
    }

    /** La libreria che disegna i grafici si carica quando la pagina è attaccata al browser. */
    override fun onAttach(attachEvent: com.vaadin.flow.component.AttachEvent) {
        super.onAttach(attachEvent)
        attachEvent.ui.page.addJavaScript("js/echarts.min.js")
    }

    override fun setParameter(event: BeforeEvent, @OptionalParameter parameter: String?) {
        parametro = parameter
    }

    override fun afterNavigation(event: AfterNavigationEvent) {
        setSizeFull()
        isPadding = false
        isSpacing = false

        val aree = accessoDatasetService.areeVisibili().sortedBy { it.nome.lowercase() }
        val parti = parametro?.split(',').orEmpty()
        val areaRichiesta = parti.getOrNull(0)?.let { id -> aree.firstOrNull { it.id.toString() == id } }
        val vistaRichiesta = parti.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        costruisci(areaRichiesta ?: aree.firstOrNull(), aree, vistaRichiesta)
    }

    // ================= Pagina =================

    private fun costruisci(areaScelta: Area?, aree: List<Area>, vistaRichiesta: UUID?) {
        removeAll()
        dialoghiValori.values.toList().forEach { it.first.close() }
        dialoghiValori.clear()
        area = areaScelta
        righe.clear(); colonne.clear(); ordineMisure.clear(); selezioni.clear()

        if (areaScelta == null) {
            val vuota = Div(Span("Non c'è ancora nessun dataset.").apply { className = "lbi-qv-empty" })
                .apply { className = "lbi-qv-page" }
            val shell = LbiAppShell(menu(null, aree, emptyList(), null), vuota, authService)
            add(shell)
            setFlexGrow(1.0, shell)
            return
        }

        val utente = CurrentUserHolder.get()
        analisi = analisiService.elenco(areaScelta.id)
        val scelta = analisi.firstOrNull { it.id == vistaRichiesta }
            ?: utente?.let { pivotViewService.ensureActiveView(it.userId, areaScelta.id) }
            ?: analisi.firstOrNull()
        if (scelta != null && utente != null) pivotViewService.setActiveView(utente.userId, areaScelta.id, scelta.id)
        analisi = analisiService.elenco(areaScelta.id)
        vista = scelta

        dimensioni = analisiService.dimensioniDisponibili(areaScelta.id)
        caricaAnalisi()
        caricaSelezioni()

        val contenuto: Component = if (scelta == null) {
            Div(Span("Non c'è nessuna analisi.").apply { className = "lbi-qv-empty" }).apply { className = "lbi-qv-page" }
        } else {
            pagina(areaScelta, scelta)
        }
        val shell = LbiAppShell(
            menu(areaScelta, aree, analisi, scelta), contenuto, authService,
            analysisName = listOfNotNull(areaScelta.nome, scelta?.nome).joinToString(" · ")
        )
        add(shell)
        setFlexGrow(1.0, shell)

        if (scelta != null) {
            aggiornaCampi()
            aggiornaZone()
            aggiornaSelezioniCorrenti()
            ricalcola()
        }
    }

    private fun pagina(areaScelta: Area, scelta: PivotView): Component {
        val sinistra = Div(pannelloCampi(), pannelloSelezioni()).apply { className = "lbi-qv-left" }
        val areaLavoro = Div(
            Div(messaggio, risultato.root).apply { className = "lbi-qv-pivot-box" },
            Div(grafici.root).apply { className = "lbi-qv-grafici-box" }
        ).apply { className = "lbi-qv-area-lavoro" }

        val zone = Div(
            pannelloZona("Righe", contenitoreRighe),
            pannelloZona("Colonne", contenitoreColonne),
            pannelloMisure()
        ).apply { className = "lbi-qv-zones" }

        val centro = Div(areaLavoro).apply { className = "lbi-qv-boxes lbi-qv-analisi-centro" }

        val corpo = Div(sinistra, centro).apply { className = "lbi-qv-body" }
        return Div(barraAlta(areaScelta, scelta, zone), schede(scelta), corpo).apply { className = "lbi-qv-page lbi-qv-page-analisi" }
    }

    // ---------- barra in alto e schede ----------

    private fun barraAlta(areaScelta: Area, scelta: PivotView, zone: Component): Component {
        val titolo = Div(
            Span("ANALISI").apply { className = "lbi-qv-title-text" },
            Span("${areaScelta.nome} · ${scelta.nome}").apply { className = "lbi-qv-title-sub" }
        ).apply { className = "lbi-qv-title" }

        val cancella = Button("Cancella selezioni", Icon(VaadinIcon.CLOSE_SMALL)) { cancellaSelezioni() }
            .apply {
                addClassName("lbi-qv-clear")
                addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            }
        val rinomina = Button("Rinomina", Icon(VaadinIcon.EDIT)) { chiediNome("Rinomina analisi", scelta.nome) { rinominaAnalisi(it) } }
            .apply { addClassName("lbi-qv-clear") }
        val elimina = Button("Elimina", Icon(VaadinIcon.TRASH)) { confermaElimina(scelta) }
            .apply {
                addClassName("lbi-qv-clear")
                addThemeVariants(ButtonVariant.LUMO_ERROR)
            }
        return Div(titolo, cancella, rinomina, elimina, zone).apply { className = "lbi-qv-top" }
    }

    /** Le analisi del dataset come schede, come i fogli di Qlik. */
    private fun schede(scelta: PivotView): Component {
        val tabs = Tabs()
        val perScheda = mutableMapOf<Tab, PivotView>()
        analisi.forEach { v ->
            val scheda = Tab(v.nome)
            perScheda[scheda] = v
            tabs.add(scheda)
        }
        tabs.selectedTab = perScheda.entries.firstOrNull { it.value.id == scelta.id }?.key
        tabs.addSelectedChangeListener { evento ->
            perScheda[evento.selectedTab]?.let { if (it.id != vista?.id) apri(it.id) }
        }
        val nuova = Button("Nuova analisi", Icon(VaadinIcon.PLUS)) {
            chiediNome("Nuova analisi", "Analisi ${analisi.size + 1}") { creaAnalisi(it) }
        }.apply { addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL) }
        return Div(tabs, nuova).apply { className = "lbi-qv-tabs" }
    }

    private fun apri(vistaId: UUID) {
        val a = area ?: return
        getUI().ifPresent { it.navigate(AnalisiView::class.java, "${a.id},$vistaId") }
    }

    private fun creaAnalisi(nome: String) {
        val a = area ?: return
        val utente = CurrentUserHolder.get() ?: return
        try {
            val nuova = analisiService.crea(utente.userId, a.id, nome.trim())
            apri(nuova.id)
        } catch (e: Exception) {
            Notification.show("Impossibile creare l'analisi: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
    }

    private fun rinominaAnalisi(nome: String) {
        val v = vista ?: return
        try {
            analisiService.rinomina(v.id, nome.trim())
            apri(v.id)
        } catch (e: Exception) {
            Notification.show("Impossibile rinominare: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
    }

    private fun confermaElimina(scelta: PivotView) {
        val a = area ?: return
        val testo = Span("L'analisi \"${scelta.nome}\" e le sue misure verranno eliminate. Le selezioni e il dataset non cambiano.")
        finestra("Eliminare \"${scelta.nome}\"?", testo, "Elimina") {
            try {
                analisiService.elimina(a.id, scelta.id)
                getUI().ifPresent { it.navigate(AnalisiView::class.java, a.id.toString()) }
            } catch (e: Exception) {
                Notification.show("Impossibile eliminare: ${e.message}", 6000, Notification.Position.MIDDLE)
            }
        }
    }

    // ---------- colonna sinistra ----------

    private fun pannelloCampi(): Component {
        ricercaCampi.addValueChangeListener { aggiornaCampi() }
        val titolo = Div(Span("Campi")).apply { className = "lbi-qv-panel-title" }
        return Div(titolo, ricercaCampi, elencoCampi).apply { className = "lbi-qv-panel" }
    }

    /** I campi del dataset raggruppati per tabella, con i pulsanti per metterli in Righe o in Colonne. */
    /** I campi del dataset per tabella: stato e contatore, clic per i valori, menu per Righe e Colonne. */
    private fun aggiornaCampi() {
        elencoCampi.removeAll()
        val filtro = ricercaCampi.value.orEmpty().trim().lowercase()
        val visibili = dimensioni.filter { filtro.isEmpty() || it.nome.lowercase().contains(filtro) }
        if (visibili.isEmpty()) {
            elencoCampi.add(Span("Nessun campo").apply { className = "lbi-qv-empty" })
            return
        }
        val contatori = contatoriCampi(visibili.map { it.dimensioneId })
        visibili.groupBy { it.tabella }.forEach { (tabella, campi) ->
            elencoCampi.add(Div(Span(tabella)).apply { className = "lbi-qv-group-title" })
            campi.forEach { campo ->
                val inUso = campo.dimensioneId in righe || campo.dimensioneId in colonne
                val c = contatori[campo.dimensioneId]
                val possibili = c?.possibili ?: 0L
                val totali = c?.totali ?: 0L
                val principale = Div(
                    Span(campo.nome).apply { className = "lbi-qv-field-name" },
                    Span("($possibili/$totali)").apply { className = "lbi-qv-field-count" }
                ).apply {
                    className = "lbi-qv-field-main"
                    addClickListener { apriValori(campo) }
                    element.setAttribute("title", "Clic per vedere e selezionare i valori")
                }
                val menu = MenuBar().apply {
                    addThemeVariants(MenuBarVariant.LUMO_TERTIARY_INLINE)
                    val radice = addItem(Icon(VaadinIcon.ELLIPSIS_DOTS_V).apply { style.set("color", "var(--lbi-text)") })
                    radice.subMenu.addItem("Metti nelle Righe") { aggiungiDimensione(campo.dimensioneId, true) }
                    radice.subMenu.addItem("Metti nelle Colonne") { aggiungiDimensione(campo.dimensioneId, false) }
                }
                elencoCampi.add(
                    Div(principale, menu).apply {
                        className = "lbi-qv-field-row lbi-qv-field-add"
                        classNames.set("lbi-qv-field-inuso", inUso)
                        classNames.set("lbi-qv-field-selected", (c?.selezionati ?: 0L) > 0)
                        classNames.set("lbi-qv-field-excluded", totali > 0 && possibili == 0L)
                    }
                )
            }
        }
    }

    private fun contatoriCampi(ids: List<UUID>): Map<UUID, ContatoreCampo> {
        val a = area ?: return emptyMap()
        return try {
            filtriService.contatori(a.id, selezioni, ids)
        } catch (e: Exception) {
            log.warn("Calcolo dei contatori fallito per il dataset {}", a.id, e)
            emptyMap()
        }
    }

    /** Finestra con i valori del campo (non modale: resta aperta mentre il pivot si aggiorna). */
    private fun apriValori(campo: DimensioneAnalisi) {
        val a = area ?: return
        dialoghiValori[campo.dimensioneId]?.let { (finestra, lista) ->
            finestra.open()
            lista.aggiorna()
            lista.ridimensiona()

            return
        }
        val lista = ListaValoriUi(
            filtriService, a.id, campo.dimensioneId,
            { selezioni },
            { valore, _ -> alClic(campo.dimensioneId, valore) }
        )
        val finestra = Dialog().apply {
            headerTitle = campo.nome
            isModal = false
            isDraggable = true
            isResizable = true
            width = "240px"
            top = "140px"
            left = "280px"
            header.add(
                Button(Icon(VaadinIcon.CLOSE)) { close() }.apply {
                    addThemeVariants(ButtonVariant.LUMO_TERTIARY_INLINE)
                    element.setAttribute("title", "Chiudi")
                }
            )
            add(lista)
        }
        dialoghiValori[campo.dimensioneId] = finestra to lista
        finestra.open()
        lista.ridimensiona()
    }

    private fun aggiornaDialoghi() {
        dialoghiValori.values.toList().forEach { (_, lista) ->
            try {
                lista.aggiorna()
            } catch (e: Exception) {
                log.warn("Aggiornamento dell'elenco dei valori fallito", e)
            }
        }
    }

    /** Come nella pagina Dataset: un clic aggiunge o toglie il valore; un valore grigio toglie le selezioni in conflitto. */
    private fun alClic(dimId: UUID, valore: ValoreFiltro) {
        val attuali = selezioni[dimId].orEmpty()
        if (valore.id in attuali) {
            impostaSelezione(dimId, attuali - valore.id)
            return
        }
        if (valore.stato == StatoValore.ESCLUSO) {
            val a = area ?: return
            val daTogliere = try {
                filtriService.conflitti(a.id, selezioni, dimId, valore.id)
            } catch (e: Exception) {
                log.warn("Calcolo dei conflitti fallito per la dimensione {}", dimId, e)
                emptySet()
            }
            daTogliere.forEach { selezioni.remove(it) }
        }
        impostaSelezione(dimId, attuali + valore.id)
    }

    private fun impostaSelezione(dimId: UUID, valori: Set<Long>) {
        if (valori.isEmpty()) selezioni.remove(dimId) else selezioni[dimId] = valori
        salvaSelezioni()
        aggiornaSelezioniCorrenti()
        aggiornaCampi()
        aggiornaDialoghi()
        ricalcola()
    }

    private fun pannelloSelezioni(): Component {
        val testata = Div(Span("Campi"), Span("Valori")).apply { className = "lbi-qv-sel-head" }
        val corpo = Div(testata, corpoSelezioni).apply { className = "lbi-qv-panel-body" }
        val minimizza = Span("−").apply {
            className = "lbi-qv-minimize"
            addClickListener { corpo.isVisible = !corpo.isVisible }
        }
        val titolo = Div(Span("Selezioni correnti"), minimizza).apply {
            className = "lbi-qv-panel-title lbi-qv-panel-title-split"
        }
        return Div(titolo, corpo).apply { className = "lbi-qv-panel" }
    }

    // ---------- zone: righe, colonne, misure ----------

    private fun pannelloZona(titolo: String, contenitore: Div): Component =
        Div(Div(Span(titolo)).apply { className = "lbi-qv-panel-title" }, contenitore)
            .apply { className = "lbi-qv-panel lbi-qv-zone-panel" }

    private fun pannelloMisure(): Component {
        val aggiungi = Button("Misura", Icon(VaadinIcon.PLUS)) { apriMisura(null) }
            .apply { addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL) }
        val titolo = Div(Span("Misure"), aggiungi).apply { className = "lbi-qv-panel-title lbi-qv-panel-title-split" }
        return Div(titolo, contenitoreMisure).apply { className = "lbi-qv-panel lbi-qv-zone-panel" }
    }

    private fun aggiornaZone() {
        contenitoreRighe.removeAll()
        contenitoreColonne.removeAll()
        contenitoreMisure.removeAll()

        riempiDimensioni(contenitoreRighe, righe)
        riempiDimensioni(contenitoreColonne, colonne)

        if (ordineMisure.isEmpty()) contenitoreMisure.add(Span("Aggiungi una misura").apply { className = "lbi-qv-empty" })
        val perId = misure.associateBy { it.id }
        ordineMisure.forEachIndexed { i, id ->
            val misura = perId[id] ?: return@forEachIndexed
            contenitoreMisure.add(
                pillola(
                    "${misura.nome} · ${etichettaAggregazione(misura.tipo)}",
                    su = { sposta(ordineMisure, i, -1) },
                    giu = { sposta(ordineMisure, i, 1) },
                    togli = { eliminaMisura(misura) },
                    modifica = { apriMisura(misura) }
                )
            )
        }
    }

    private fun riempiDimensioni(contenitore: Div, lista: MutableList<UUID>) {
        if (lista.isEmpty()) contenitore.add(Span("Aggiungi un campo").apply { className = "lbi-qv-empty" })
        lista.forEachIndexed { i, id ->
            contenitore.add(
                pillola(
                    nomiDimensioni[id] ?: "?",
                    su = { sposta(lista, i, -1) },
                    giu = { sposta(lista, i, 1) },
                    togli = { lista.removeAt(i); salvaDisposizione() },
                    modifica = null
                )
            )
        }
    }

    /** Una voce di zona: nome e pulsanti sposta su, sposta giù, togli (e modifica per le misure). */
    private fun pillola(testo: String, su: () -> Unit, giu: () -> Unit, togli: () -> Unit, modifica: (() -> Unit)?): Component {
        fun piccolo(simbolo: String, titolo: String, azione: () -> Unit) = Button(simbolo) { azione() }.apply {
            addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL)
            element.setAttribute("title", titolo)
        }
        val pulsanti = Div().apply { className = "lbi-qv-pill-buttons" }
        if (modifica != null) pulsanti.add(piccolo("✎", "Modifica") { modifica() })
        pulsanti.add(
            piccolo("▲", "Sposta su") { su() },
            piccolo("▼", "Sposta giù") { giu() },
            piccolo("✕", "Togli") { togli() }
        )
        return Div(Span(testo).apply { className = "lbi-qv-pill-text" }, pulsanti).apply { className = "lbi-qv-pill" }
    }

    private fun sposta(lista: MutableList<UUID>, indice: Int, verso: Int) {
        val destinazione = indice + verso
        if (destinazione !in lista.indices) return
        val elemento = lista.removeAt(indice)
        lista.add(destinazione, elemento)
        salvaDisposizione()
    }

    private fun aggiungiDimensione(id: UUID, nelleRighe: Boolean) {
        righe.remove(id)
        colonne.remove(id)
        if (nelleRighe) righe.add(id) else colonne.add(id)
        salvaDisposizione()
    }

    /** Salva la disposizione dell'analisi e ricalcola la pivot. */
    private fun salvaDisposizione() {
        val v = vista ?: return
        try {
            vista = analisiService.impostaDisposizione(v.id, righe.toList(), colonne.toList(), ordineMisure.toList())
        } catch (e: Exception) {
            Notification.show("Impossibile salvare la disposizione: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
        caricaAnalisi()
        aggiornaCampi()
        aggiornaZone()
        ricalcola()
    }

    /** Rilegge dal database righe, colonne e misure dell'analisi corrente. */
    private fun caricaAnalisi() {
        val v = vista ?: return
        val completa = analisiService.carica(v.id) ?: return
        vista = completa.vista
        misure = completa.misure
        righe.clear(); righe.addAll(completa.vista.pivotRows)
        colonne.clear(); colonne.addAll(completa.vista.pivotColumns)
        ordineMisure.clear(); ordineMisure.addAll(completa.vista.pivotValues.filter { id -> misure.any { it.id == id } })
    }

    // ---------- misure ----------

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
            Notification.show("Impossibile eliminare la misura: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
        caricaAnalisi()
        aggiornaZone()
        ricalcola()
    }

    /** Aggiunge (esistente = null) o modifica una misura: aggregazione, campo e nome, come in Qlik. */
    private fun apriMisura(esistente: MisuraAnalisi?) {
        val a = area ?: return
        val v = vista ?: return
        val fatti = analisiService.tabelleFatti(a.id)
        if (fatti.isEmpty()) {
            Notification.show("Il dataset non ha una tabella Fatti", 5000, Notification.Position.MIDDLE)
            return
        }
        val campi = analisiService.campiMisurabili(a.id)

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
            val candidati = campi.filter { it.areaTabellaId == tabella.value?.first && (!soloNumerici || it.numerico) }
            campo.setItems(candidati)
            campo.isEnabled = tipo.value != TipoAggregazione.COUNT
            campo.value = candidati.firstOrNull { it.colonna == esistente?.colonna }
        }
        tipo.addValueChangeListener { riempiCampi() }
        tabella.addValueChangeListener { riempiCampi() }
        riempiCampi()

        val contenuto = VerticalLayout(nome, tipo, tabella, campo).apply { isPadding = false }
        finestra(if (esistente == null) "Aggiungi misura" else "Modifica misura", contenuto, "Salva") {
            val aggregazione = tipo.value ?: return@finestra
            val fatto = tabella.value?.first ?: return@finestra
            val colonnaScelta = campo.value?.colonna
            val nomeMisura = nome.value.orEmpty().trim().ifEmpty {
                if (aggregazione == TipoAggregazione.COUNT) "Numero righe"
                else "${etichettaAggregazione(aggregazione)} ${campo.value?.nome.orEmpty()}".trim()
            }
            try {
                if (esistente == null) {
                    analisiService.aggiungiMisura(v.id, nomeMisura, aggregazione, fatto, if (aggregazione == TipoAggregazione.COUNT) null else colonnaScelta)
                } else {
                    analisiService.modificaMisura(esistente.id, nomeMisura, aggregazione, fatto, if (aggregazione == TipoAggregazione.COUNT) null else colonnaScelta)
                }
            } catch (e: Exception) {
                Notification.show(e.message ?: "Misura non valida", 6000, Notification.Position.MIDDLE)
                return@finestra
            }
            caricaAnalisi()
            aggiornaZone()
            ricalcola()
        }
    }

    // ---------- selezioni ----------

    private fun caricaSelezioni() {
        val a = area ?: return
        val utente = CurrentUserHolder.get() ?: return
        userPivotStateRepository.find(utente.userId, a.id)?.selections?.forEach { (dimId, valori) ->
            if (valori.isNotEmpty()) selezioni[dimId] = valori
        }
    }

    private fun salvaSelezioni() {
        val a = area ?: return
        val utente = CurrentUserHolder.get() ?: return
        val attuale = userPivotStateRepository.find(utente.userId, a.id)
        val nuove = selezioni.toMap()
        userPivotStateRepository.save(
            attuale?.copy(selections = nuove, updatedAt = java.time.Instant.now())
                ?: UserPivotState(utente.userId, a.id, vista?.id, nuove)
        )
    }

    private fun togliSelezione(dimId: UUID) {
        impostaSelezione(dimId, emptySet())
    }

    private fun cancellaSelezioni() {
        selezioni.clear()
        salvaSelezioni()
        aggiornaSelezioniCorrenti()
        aggiornaCampi()
        aggiornaDialoghi()
        ricalcola()
        Notification.show("Selezioni cancellate", 2500, Notification.Position.BOTTOM_START)
    }

    private fun aggiornaSelezioniCorrenti() {
        val a = area ?: return
        corpoSelezioni.removeAll()
        if (selezioni.isEmpty()) {
            corpoSelezioni.add(Span("Nessuna selezione").apply { className = "lbi-qv-empty" })
            return
        }
        selezioni.entries.sortedBy { (nomiDimensioni[it.key] ?: "").lowercase() }.forEach { (dimId, valori) ->
            val etichette = try {
                filtriService.etichette(a.id, dimId, valori.take(MAX_ETICHETTE))
            } catch (e: Exception) {
                log.warn("Etichette delle selezioni fallite per la dimensione {}", dimId, e)
                emptyList()
            }
            val testo = etichette.joinToString(", ") + if (valori.size > MAX_ETICHETTE) " … (${valori.size})" else ""
            val togli = Button("✕") { togliSelezione(dimId) }.apply {
                addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL)
                element.setAttribute("title", "Togli la selezione di questo campo")
            }
            corpoSelezioni.add(
                Div(
                    Span("●").apply { className = "lbi-qv-sel-dot" },
                    Span(nomiDimensioni[dimId] ?: "?").apply { className = "lbi-qv-sel-campo" },
                    Span(testo).apply { className = "lbi-qv-sel-valori" },
                    togli
                ).apply { className = "lbi-qv-sel-row" }
            )
        }
    }

    // ---------- calcolo ----------

    private fun ricalcola() {
        val v = vista ?: return
        messaggio.text = ""
        aggiornaGrafici(v)
        if (ordineMisure.isEmpty()) {
            risultato.clearAll()
            messaggio.text = "Aggiungi almeno una misura per vedere la pivot."
            return
        }
        try {
            val esito = analisiService.calcola(v.id, selezioni.toMap())
            risultato.render(esito.risultato, esito.gerarchia, righe.toList(), nomiDimensioni)
            if (righe.isEmpty() && colonne.isEmpty()) messaggio.text = "Totali sul dataset: aggiungi un campo alle Righe per scomporli."
        } catch (e: IllegalArgumentException) {
            // Una regola del motore spiegata all'utente (ad esempio troppi valori in Colonne): non è un guasto.
            log.info("Analisi {}: {}", v.id, e.message)
            risultato.clearAll()
            messaggio.text = e.message ?: "La disposizione scelta non si può calcolare."
        } catch (e: Exception) {
            log.warn("Calcolo dell'analisi {} fallito", v.id, e)
            risultato.clearAll()
            messaggio.text = "Impossibile calcolare: ${e.message ?: e::class.simpleName}. Se il dataset non è stato sincronizzato, premi Sincronizza."
        }
    }

    /** I grafici salvati di questa analisi, calcolati con le selezioni correnti dell'utente. */
    private fun aggiornaGrafici(v: PivotView) {
        try {
            val dati = chartService.getChartsDataDellAnalisi(v.id, dimensioni.map { it.dimensioneId }.toSet(), selezioni.toMap())
            grafici.render(dati, dimensioni.map { it.dimensioneId })
        } catch (e: Exception) {
            log.warn("Calcolo dei grafici dell'analisi {} fallito", v.id, e)
            grafici.clearAll()
        }
    }

    // ---------- finestre ----------

    private fun chiediNome(titolo: String, iniziale: String, azione: (String) -> Unit) {
        val campo = TextField("Nome").apply { setWidthFull(); value = iniziale }
        finestra(titolo, campo, "Conferma") {
            val nome = campo.value.orEmpty().trim()
            if (nome.isEmpty()) {
                Notification.show("Il nome è obbligatorio", 3000, Notification.Position.MIDDLE)
            } else {
                azione(nome)
            }
        }
    }

    private fun finestra(titolo: String, contenuto: Component, conferma: String, azione: () -> Unit) {
        val dialog = Dialog().apply {
            headerTitle = titolo
            width = "460px"
        }
        dialog.add(VerticalLayout(contenuto).apply { isPadding = false })
        dialog.footer.add(
            Button("Annulla") { dialog.close() },
            Button(conferma) {
                dialog.close()
                azione()
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        )
        dialog.open()
    }

    // ================= Menu =================

    private fun menu(
        areaScelta: Area?,
        aree: List<Area>,
        elencoAnalisi: List<PivotView>,
        attiva: PivotView?
    ): List<LbiSidebarMenu.MenuGroup> {
        val admin = adminGuard.isAdmin()
        val areaId = areaScelta?.id

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
                label = "Analisi di ${areaScelta?.nome ?: ""}",
                entries = if (areaId == null) emptyList() else elencoAnalisi.map { v ->
                    LbiSidebarMenu.MenuEntry(v.nome, icon = VaadinIcon.TABLE) { apri(v.id) }
                },
                active = true,
                icon = VaadinIcon.CHART
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Grafici",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Gestisci grafici", enabled = areaId != null, icon = VaadinIcon.PIE_CHART) {
                        areaId?.let { id ->
                            val param = if (attiva != null) "$id,${attiva.id}" else id.toString()
                            getUI().ifPresent { it.navigate(ChartsView::class.java, param) }
                        }
                    }
                ),
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

        if (admin) {
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
                        LbiSidebarMenu.MenuEntry("Modello dati", enabled = areaId != null, icon = VaadinIcon.TABLE) {
                            areaId?.let { id -> getUI().ifPresent { it.navigate(NewDatasetView::class.java, id.toString()) } }
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

    private companion object {
        const val MAX_ETICHETTE = 3
    }
}