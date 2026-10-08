package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.etl.EtlOrchestrator
import com.lightningbi.lightning_engine.model.Area
import com.lightningbi.lightning_engine.model.PivotView
import com.lightningbi.lightning_engine.model.UserPivotState
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.repository.UserPivotStateRepository
import com.lightningbi.lightning_engine.service.AdminGuard
import com.lightningbi.lightning_engine.service.AuthService
import com.lightningbi.lightning_engine.service.CalendarioService
import com.lightningbi.lightning_engine.service.CampoEffettivo
import com.lightningbi.lightning_engine.service.ContatoreCampo
import com.lightningbi.lightning_engine.service.DatasetBozza
import com.lightningbi.lightning_engine.service.DatasetService
import com.lightningbi.lightning_engine.service.FiltriService
import com.lightningbi.lightning_engine.service.PivotViewService
import com.lightningbi.lightning_engine.service.RiferimentoCampo
import com.lightningbi.lightning_engine.service.StatoValore
import com.lightningbi.lightning_engine.service.ValoreFiltro
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
import com.vaadin.flow.component.progressbar.ProgressBar
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.AfterNavigationObserver
import com.vaadin.flow.router.BeforeEvent
import com.vaadin.flow.router.HasUrlParameter
import com.vaadin.flow.router.OptionalParameter
import com.vaadin.flow.router.Route
import org.slf4j.LoggerFactory
import java.util.UUID
import com.lightningbi.lightning_engine.service.AccessoDatasetService

/**
 * Pagina "Dataset": il foglio "Impostazione filtri" di Qlik. Solo selezioni:
 * tabelle e grafici stanno nelle pagine Analisi e Grafici, che condividono le
 * stesse selezioni (UserPivotState, per utente e dataset).
 *
 * - Un box per ogni tabella del dataset, con i suoi campi e il contatore
 *   (possibili/totali). Un clic su un campo ne apre l'elenco dei valori.
 * - Valori: verde selezionato, bianco possibile, grigio escluso. Come in
 *   QlikView: clic = quel valore diventa l'unica selezione; Ctrl+clic aggiunge
 *   o toglie; un clic sull'unico valore selezionato lo deseleziona.
 * - Selezioni correnti (con la X per togliere un campo) e Cancella selezioni.
 * - La barra Anno / Mese / Giorno e i Preferiti sono ancora senza dati.
 *
 * Route "dataset" e "dataset/{id}".
 */
@Route("dataset")
@Uses(Icon::class)
class DatasetFiltriView(
    private val datasetService: DatasetService,
    private val registryRepository: RegistryRepository,
    private val accessoDatasetService: AccessoDatasetService,
    private val pivotViewService: PivotViewService,
    private val userPivotStateRepository: UserPivotStateRepository,
    private val calendarioService: CalendarioService,
    private val filtriService: FiltriService,
    private val etlOrchestrator: EtlOrchestrator,
    private val areaSourceRepository: AreaSourceRepository,
    private val adminGuard: AdminGuard,
    private val authService: AuthService
) : VerticalLayout(), HasUrlParameter<String>, AfterNavigationObserver {

    private val log = LoggerFactory.getLogger(DatasetFiltriView::class.java)

    private var parametro: String? = null

    // Stato della pagina aperta.
    private var areaCorrente: Area? = null
    private val selezioni = mutableMapOf<UUID, Set<Long>>()
    private val campiUi = mutableListOf<CampoUi>()
    private val nomiCampi = mutableMapOf<UUID, String>()
    private val corpoSelezioni = Div()

    // Barra del calendario: componente ("anno", "mese", "giorno") -> dimensione del campo derivato.
    private val dimCalendario = mutableMapOf<String, UUID>()
    private val contenitoreAnni = Div().apply { className = "lbi-qv-chips lbi-qv-chips-anno" }
    private val contenitoreMesi = Div().apply { className = "lbi-qv-chips lbi-qv-chips-mese" }
    private val contenitoreGiorni = Div().apply { className = "lbi-qv-chips lbi-qv-chips-giorno" }

    override fun setParameter(event: BeforeEvent, @OptionalParameter parameter: String?) {
        parametro = parameter
    }

    override fun afterNavigation(event: AfterNavigationEvent) {
        setSizeFull()
        isPadding = false
        isSpacing = false

        val aree = accessoDatasetService.areeVisibili().sortedBy { it.nome.lowercase() }
        val richiesta = parametro?.let {
            try { UUID.fromString(it) } catch (_: IllegalArgumentException) { null }
        }
        val area = aree.firstOrNull { it.id == richiesta } ?: aree.firstOrNull()
        costruisci(area, aree)
    }

    // ================= Pagina =================

    private fun costruisci(area: Area?, aree: List<Area>) {
        removeAll()
        campiUi.clear()
        nomiCampi.clear()
        dimCalendario.clear()
        selezioni.clear()
        areaCorrente = area

        val bozza = area?.let { datasetService.carica(it.id) }
        val analisi = area?.let { pivotViewService.findByArea(it.id) } ?: emptyList()
        if (area != null) caricaSelezioni(area)

        val contenuto: Component = if (area == null) {
            Div(Span("Non c'è ancora nessun dataset.").apply { className = "lbi-qv-empty" })
                .apply { className = "lbi-qv-page" }
        } else {
            pagina(area, bozza)
        }

        val shell = LbiAppShell(menu(area, aree, analisi), contenuto, authService, analysisName = area?.nome)
        add(shell)
        setFlexGrow(1.0, shell)

        if (area != null) aggiornaTutto()
    }

    private fun pagina(area: Area, bozza: DatasetBozza?): Component {
        val sinistra = Div(pannelloSelezioniCorrenti(), pannelloPreferiti()).apply { className = "lbi-qv-left" }

        val boxes = Div().apply { className = "lbi-qv-boxes" }
        if (bozza == null || bozza.occorrenze.isEmpty()) {
            boxes.add(Span("Il dataset non ha ancora tabelle: aggiungile da Modello dati.").apply { className = "lbi-qv-empty" })
        } else {
            // (occorrenza, colonna) -> dimensione, per sapere a quale dimensione appartiene ogni campo.
            val dimensioni = registryRepository.findDimensioniByArea(area.id)
                .filter { it.areaTabellaId != null }
                .associate { (it.areaTabellaId!! to it.colonnaFisica) to it.dimensioneId }

            // Il calendario: i derivati (Anno, Mese, Giorno) del campo data scelto in Modello dati.
            bozza.campiCalendario().forEach { (componente, campo) ->
                dimensioni[campo.occorrenzaId to campo.colonna]?.let { dimId ->
                    dimCalendario[componente] = dimId
                    nomiCampi[dimId] = campo.nomeOrigine
                }
            }

            val campi = bozza.campi().filter { !it.escluso }
            bozza.occorrenze.forEach { occ ->

                // I campi tecnici (chiavi, prefissi della connessione) non si mostrano: servono solo a collegare le tabelle.
                val suoi = campi.filter {
                    it.occorrenzaId == occ.id && !it.tecnico && RiferimentoCampo(occ.id, it.colonna) in bozza.dimensioni
                }
                boxes.add(boxTabella(area, occ.alias, occ.id, suoi, dimensioni))
            }
        }

        val corpo = Div(sinistra, boxes).apply { className = "lbi-qv-body" }
        return Div(barraAlta(area), corpo).apply { className = "lbi-qv-page" }
    }

    // ---------- barra in alto ----------

    private fun barraAlta(area: Area): Component {
        val titolo = Div(
            Span("IMPOSTAZIONE FILTRI").apply { className = "lbi-qv-title-text" },
            Span(area.nome).apply { className = "lbi-qv-title-sub" }
        ).apply { className = "lbi-qv-title" }

        val cancella = Button("Cancella selezioni", Icon(VaadinIcon.CLOSE_SMALL)) { cancellaSelezioni() }
            .apply {
                addClassName("lbi-qv-clear")
                addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            }

        val barra = Div(titolo, cancella)
        // Come il Reload di Qlik: solo chi costruisce il modello (admin) ricarica i dati.
        if (adminGuard.isAdmin()) {
            barra.add(
                Button("Sincronizza", Icon(VaadinIcon.REFRESH)) { sincronizza(area) }
                    .apply { addClassName("lbi-qv-clear") }
            )
        }
        barra.add(barraCalendario())
        return barra.apply { className = "lbi-qv-top" }
    }

    /**
     * Anno / Mese / Giorno, come in Qlik: sono campi (i derivati del campo data scelto in Modello
     * dati) e si selezionano come gli altri. Finché il calendario non è configurato i valori
     * restano grigi e non si possono cliccare.
     */
    private fun barraCalendario(): Component {
        fun gruppo(titolo: String, chips: Component) =
            Div(Span(titolo).apply { className = "lbi-qv-cal-label" }, chips).apply { className = "lbi-qv-cal-group" }

        contenitoreAnni.removeAll()
        contenitoreMesi.removeAll()
        contenitoreGiorni.removeAll()
        calendarioService.nomiMesi().forEach { contenitoreMesi.add(chip(it)) }
        (1..31).forEach { contenitoreGiorni.add(chip(it.toString())) }

        return Div(
            gruppo("ANNO", contenitoreAnni),
            gruppo("MESE", contenitoreMesi),
            gruppo("GIORNO", contenitoreGiorni)
        ).apply { className = "lbi-qv-cal" }
    }

    private fun chip(testo: String): Span = Span(testo).apply { className = "lbi-qv-chip lbi-qv-chip-escluso" }

    // ---------- colonna sinistra ----------

    private fun pannelloSelezioniCorrenti(): Component {
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

    private fun pannelloPreferiti(): Component {
        val scelta = ComboBox<String>().apply {
            placeholder = "Seleziona Preferiti"
            isEnabled = false
            setWidthFull()
        }
        val aggiungi = Button("Aggiungi", Icon(VaadinIcon.PLUS)) { }.apply {
            addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL)
            isEnabled = false
            element.setAttribute("title", "I preferiti (selezioni salvate) arrivano nel prossimo passo")
        }
        val rimuovi = Button("Rimuovi", Icon(VaadinIcon.CLOSE_SMALL)) { }.apply {
            addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR)
            isEnabled = false
        }
        val corpo = Div(scelta, Div(aggiungi, rimuovi).apply { className = "lbi-qv-fav-buttons" })
            .apply { className = "lbi-qv-panel-body" }
        return Div(Div(Span("Preferiti")).apply { className = "lbi-qv-panel-title" }, corpo)
            .apply { className = "lbi-qv-panel" }
    }

    // ---------- box delle tabelle ----------

    private fun boxTabella(
        area: Area,
        alias: String,
        occorrenzaId: UUID,
        campi: List<CampoEffettivo>,
        dimensioni: Map<Pair<UUID, String>, UUID>
    ): Component {
        val titolo = Div(Span(alias)).apply { className = "lbi-qv-panel-title" }
        val corpo = Div().apply { className = "lbi-qv-panel-body" }
        if (campi.isEmpty()) {
            corpo.add(Span("Nessun campo da filtrare").apply { className = "lbi-qv-empty" })
        } else {
            campi.sortedBy { it.nomeOrigine.lowercase() }.forEach { campo ->
                val dimId = dimensioni[occorrenzaId to campo.colonna] ?: return@forEach
                if (dimId in dimCalendario.values) return@forEach  // sta nella barra in alto
                val ui = CampoUi(area.id, dimId, campo.nomeOrigine)
                campiUi += ui
                nomiCampi[dimId] = campo.nomeOrigine
                corpo.add(ui.contenitore)
            }
        }
        return Div(titolo, corpo).apply { className = "lbi-qv-panel lbi-qv-box" }
    }

    /** Una riga di campo: nome, contatore possibili/totali e, al clic, l'elenco dei valori. */
    private inner class CampoUi(val areaId: UUID, val dimId: UUID, val nome: String) {
        private val contatore = Span("(–)").apply { className = "lbi-qv-field-count" }
        private val riga = Div(
            Span(nome).apply { className = "lbi-qv-field-name" },
            contatore,
            Span().apply { className = "lbi-qv-field-bar" }
        ).apply { className = "lbi-qv-field-row" }

        val contenitore = Div(riga)
        var lista: ListaValoriUi? = null
            private set

        init {
            riga.addClickListener { apriChiudi() }
        }

        private fun apriChiudi() {
            val esistente = lista
            if (esistente != null) {
                esistente.isVisible = !esistente.isVisible
                return
            }
            val nuova = ListaValoriUi(
                filtriService, areaId, dimId,
                { selezioni },
                { valore, ctrl -> alClic(dimId, valore, ctrl) }
            )
            lista = nuova
            contenitore.add(nuova)
        }

        fun mostra(c: ContatoreCampo?) {
            val possibili = c?.possibili ?: 0L
            val totali = c?.totali ?: 0L
            contatore.text = "($possibili/$totali)"
            riga.classNames.set("lbi-qv-field-selected", (c?.selezionati ?: 0L) > 0)
            riga.classNames.set("lbi-qv-field-excluded", totali > 0 && possibili == 0L)
        }
    }

    // ================= Selezioni =================

    private fun caricaSelezioni(area: Area) {
        val utente = CurrentUserHolder.get() ?: return
        userPivotStateRepository.find(utente.userId, area.id)?.selections?.forEach { (dimId, valori) ->
            if (valori.isNotEmpty()) selezioni[dimId] = valori
        }
    }

    private fun salvaSelezioni() {
        val area = areaCorrente ?: return
        val utente = CurrentUserHolder.get() ?: return
        val attuale = userPivotStateRepository.find(utente.userId, area.id)
        val nuove = selezioni.toMap()
        userPivotStateRepository.save(
            attuale?.copy(selections = nuove, updatedAt = java.time.Instant.now())
                ?: UserPivotState(utente.userId, area.id, null, nuove)
        )
    }

    /**
     * Un clic su un valore lo aggiunge alla selezione del campo, un altro clic sullo stesso lo
     * toglie. Più valori dello stesso campo si combinano in OR, i campi diversi in AND.
     * Un valore ESCLUSO (grigio) si può selezionare: come in Qlik le selezioni in conflitto
     * con quel valore si annullano.
     */
    private fun alClic(dimId: UUID, valore: ValoreFiltro, @Suppress("UNUSED_PARAMETER") ctrl: Boolean) {
        val attuali = selezioni[dimId].orEmpty()
        if (valore.id in attuali) {
            impostaSelezione(dimId, attuali - valore.id)
            return
        }
        if (valore.stato == StatoValore.ESCLUSO) {
            val area = areaCorrente ?: return
            val daTogliere = try {
                filtriService.conflitti(area.id, selezioni, dimId, valore.id)
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
        aggiornaTutto()
    }

    /** Come "Cancella selezioni" di Qlik: svuota le selezioni del dataset, condivise con Analisi e Grafici. */
    private fun cancellaSelezioni() {
        selezioni.clear()
        salvaSelezioni()
        aggiornaTutto()
        Notification.show("Selezioni cancellate", 2500, Notification.Position.BOTTOM_START)
    }

    // ================= Aggiornamento =================

    private fun aggiornaTutto() {
        aggiornaContatori()
        aggiornaCalendario()
        campiUi.forEach { campo ->
            try {
                campo.lista?.takeIf { it.isVisible }?.aggiorna()
            } catch (e: Exception) {
                log.warn("Aggiornamento dell'elenco di '{}' fallito", campo.nome, e)
            }
        }
        aggiornaSelezioniCorrenti()
    }

    private fun aggiornaContatori() {
        val area = areaCorrente ?: return
        try {
            val contatori = filtriService.contatori(area.id, selezioni, campiUi.map { it.dimId })
            campiUi.forEach { it.mostra(contatori[it.dimId]) }
        } catch (e: Exception) {
            log.warn("Calcolo dei contatori fallito per il dataset {}", area.id, e)
            Notification.show(
                "Impossibile calcolare i filtri: ${e.message ?: e::class.simpleName}. " +
                        "Se il dataset non è mai stato sincronizzato, premi Sincronizza.",
                8000, Notification.Position.MIDDLE
            )
        }
    }

    /** Rilegge Anno, Mese e Giorno dai dati: ogni valore ha il suo stato (verde, bianco, grigio). */
    private fun aggiornaCalendario() {
        val area = areaCorrente ?: return
        if (dimCalendario.isEmpty()) return
        try {
            dimCalendario["anno"]?.let { dimId ->
                val valori = filtriService.valori(area.id, selezioni, dimId, null, 0, MAX_CHIP)
                contenitoreAnni.removeAll()
                valori.sortedBy { it.etichetta.toLongOrNull() ?: Long.MAX_VALUE }
                    .forEach { contenitoreAnni.add(chipValore(dimId, it)) }
            }
            dimCalendario["mese"]?.let { dimId ->
                val perEtichetta = filtriService.valori(area.id, selezioni, dimId, null, 0, MAX_CHIP).associateBy { it.etichetta }
                contenitoreMesi.removeAll()
                calendarioService.nomiMesi().forEach { nome ->
                    contenitoreMesi.add(perEtichetta[nome]?.let { chipValore(dimId, it) } ?: chip(nome))
                }
            }
            dimCalendario["giorno"]?.let { dimId ->
                val perEtichetta = filtriService.valori(area.id, selezioni, dimId, null, 0, MAX_CHIP).associateBy { it.etichetta }
                contenitoreGiorni.removeAll()
                (1..31).forEach { giorno ->
                    contenitoreGiorni.add(perEtichetta[giorno.toString()]?.let { chipValore(dimId, it) } ?: chip(giorno.toString()))
                }
            }
        } catch (e: Exception) {
            log.warn("Calcolo del calendario fallito per il dataset {}", area.id, e)
        }
    }

    /** Un valore del calendario come chip cliccabile, con il colore del suo stato. */
    private fun chipValore(dimId: UUID, valore: ValoreFiltro): Span =
        Span(valore.etichetta).apply {
            className = "lbi-qv-chip lbi-qv-chip-" + valore.stato.name.lowercase()
            addClickListener { alClic(dimId, valore, false) }
        }

    private fun aggiornaSelezioniCorrenti() {
        val area = areaCorrente ?: return
        corpoSelezioni.removeAll()
        if (selezioni.isEmpty()) {
            corpoSelezioni.add(Span("Nessuna selezione").apply { className = "lbi-qv-empty" })
            return
        }
        selezioni.entries.sortedBy { (nomiCampi[it.key] ?: "").lowercase() }.forEach { (dimId, valori) ->
            val etichette = try {
                filtriService.etichette(area.id, dimId, valori.take(MAX_ETICHETTE))
            } catch (e: Exception) {
                log.warn("Etichette delle selezioni fallite per la dimensione {}", dimId, e)
                emptyList()
            }
            val testo = etichette.joinToString(", ") + if (valori.size > MAX_ETICHETTE) " … (${valori.size})" else ""
            val togli = Button(Icon(VaadinIcon.CLOSE_SMALL)) { impostaSelezione(dimId, emptySet()) }.apply {
                addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL)
                element.setAttribute("title", "Togli la selezione di questo campo")
            }
            corpoSelezioni.add(
                Div(
                    Span("●").apply { className = "lbi-qv-sel-dot" },
                    Span(nomiCampi[dimId] ?: "?").apply { className = "lbi-qv-sel-campo" },
                    Span(testo).apply { className = "lbi-qv-sel-valori" },
                    togli
                ).apply { className = "lbi-qv-sel-row" }
            )
        }
    }

    // ================= Sincronizzazione =================

    /**
     * Il Reload di Qlik: sincronizza le tabelle del dataset e ricostruisce il suo
     * indice. Gira in un thread a parte, con una finestra di avanzamento. Solo admin.
     */
    private fun sincronizza(area: Area) {
        if (!adminGuard.isAdmin()) return
        val ui = getUI().orElse(null) ?: return
        val sorgenti = areaSourceRepository.findByArea(area.id)
        if (sorgenti.isEmpty()) {
            Notification.show("Il dataset non ha una sorgente: salvalo da Modello dati", 5000, Notification.Position.MIDDLE)
            return
        }

        val avanzamento = Span("Avvio...")
        val dialog = Dialog().apply {
            headerTitle = "Sincronizzazione di ${area.nome}"
            width = "480px"
            isCloseOnEsc = false
            isCloseOnOutsideClick = false
        }
        dialog.add(VerticalLayout(ProgressBar().apply { isIndeterminate = true }, avanzamento).apply { isPadding = false })
        dialog.open()

        Thread {
            try {
                sorgenti.forEach { sorgente ->
                    etlOrchestrator.runForArea(area.id, sorgente) { frase -> ui.access { avanzamento.text = frase } }
                }
                ui.access {
                    dialog.close()
                    Notification.show("Sincronizzazione completata", 4000, Notification.Position.BOTTOM_END)
                    if (areaCorrente?.id == area.id) aggiornaTutto()
                }
            } catch (e: Exception) {
                ui.access {
                    dialog.close()
                    Notification.show(
                        "Sincronizzazione fallita: ${e.message ?: e::class.simpleName}",
                        8000, Notification.Position.MIDDLE
                    )
                }
            }
        }.apply {
            isDaemon = true
            name = "sync-dataset-${area.id}"
        }.start()
    }

    // ================= Menu =================

    private fun menu(area: Area?, aree: List<Area>, analisi: List<PivotView>): List<LbiSidebarMenu.MenuGroup> {
        val admin = adminGuard.isAdmin()
        val areaId = area?.id

        val dataset = aree.map { a ->
            LbiSidebarMenu.MenuEntry(a.nome, icon = VaadinIcon.TABLE) {
                getUI().ifPresent { it.navigate(DatasetFiltriView::class.java, a.id.toString()) }
            }
        }

        val gruppi = mutableListOf(
            LbiSidebarMenu.MenuGroup("Dataset", dataset, active = true, icon = VaadinIcon.DATABASE),
            LbiSidebarMenu.MenuGroup(
                label = "Analisi di ${area?.nome ?: ""}",
                entries = when {
                    areaId == null -> emptyList()
                    // Dataset senza analisi: la pagina Analisi ne crea una al primo ingresso.
                    analisi.isEmpty() -> listOf(
                        LbiSidebarMenu.MenuEntry("Apri analisi", icon = VaadinIcon.TABLE) {
                            getUI().ifPresent { it.navigate(AnalisiView::class.java, areaId.toString()) }
                        }
                    )
                    else -> analisi.map { vista ->
                        LbiSidebarMenu.MenuEntry(vista.nome, icon = VaadinIcon.TABLE) { apriAnalisi(areaId, vista) }
                    }
                },
                icon = VaadinIcon.CHART
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Grafici",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Gestisci grafici", enabled = areaId != null, icon = VaadinIcon.PIE_CHART) {
                        areaId?.let { id -> getUI().ifPresent { it.navigate(ChartsView::class.java, id.toString()) } }
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
                        LbiSidebarMenu.MenuEntry("Sincronizza dataset", enabled = area != null, icon = VaadinIcon.REFRESH) {
                            area?.let { sincronizza(it) }
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

    /** Apre un'analisi (la rende attiva e passa alla pagina della pivot). */
    private fun apriAnalisi(areaId: UUID, vista: PivotView) {
        CurrentUserHolder.get()?.let { pivotViewService.setActiveView(it.userId, areaId, vista.id) }
        getUI().ifPresent { it.navigate(AnalisiView::class.java, "$areaId,${vista.id}") }
    }

    private companion object {
        /** Quanti valori mostrare per campo nelle Selezioni correnti, prima dei puntini. */
        const val MAX_ETICHETTE = 3

        /** Quanti valori leggere per Anno, Mese e Giorno: bastano per anni, 12 mesi e 31 giorni. */
        const val MAX_CHIP = 100
    }
}