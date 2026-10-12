// FILE: src/main/kotlin/com/lightningbi/lightning_engine/view/DatasetFiltriView.kt
package com.lightning.view

import com.lightning.etl.EtlOrchestrator
import com.lightning.model.Area
import com.lightning.model.Azienda
import com.lightning.model.PivotView
import com.lightning.model.UserPivotState
import com.lightning.repository.AreaSourceRepository
import com.lightning.repository.RegistryRepository
import com.lightning.repository.UserPivotStateRepository
import com.lightning.service.AccessoDatasetService
import com.lightning.service.AdminGuard
import com.lightning.service.AuthService
import com.lightning.service.CalendarioService
import com.lightning.service.CampoEffettivo
import com.lightning.service.ContatoreCampo
import com.lightning.service.DatasetBozza
import com.lightning.service.DatasetService
import com.lightning.service.FiltriService
import com.lightning.service.PivotViewService
import com.lightning.service.PoolQuery
import com.lightning.service.RiferimentoCampo
import com.lightning.service.SezioneAccessoService
import com.lightning.service.StatoValore
import com.lightning.service.ValoreFiltro
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
import java.time.Instant
import java.util.UUID

/**
 * Pagina "Dataset": il foglio "Impostazione filtri" di Qlik. Solo selezioni:
 * tabelle e grafici stanno nelle pagine Analisi e Grafici, che condividono le
 * stesse selezioni (UserPivotState, per utente e dataset).
 *
 * - Un box per ogni tabella del dataset, con i suoi campi e il contatore
 *   (possibili/totali). Un clic su un campo ne apre l'elenco dei valori.
 * - Valori: verde selezionato, bianco possibile, grigio escluso. Un clic aggiunge
 *   o toglie il valore; un valore grigio toglie le selezioni in conflitto.
 * - Selezioni correnti (lucchetto e X per campo), Cancella selezioni, Indietro /
 *   Avanti e Blocca / Sblocca, come in Qlik. Cronologia e blocchi sono condivisi con Analisi.
 * - Barra Anno / Mese / Giorno (derivati del campo data del calendario) e, per
 *   l'admin, Ditta. I Preferiti sono ancora da fare.
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
    private val authService: AuthService,
    private val sezioneAccessoService: SezioneAccessoService,
    private val poolQuery: PoolQuery
) : VerticalLayout(), HasUrlParameter<String>, AfterNavigationObserver {

    private val log = LoggerFactory.getLogger(DatasetFiltriView::class.java)

    private var parametro: String? = null

    // Stato della pagina aperta.
    private var areaCorrente: Area? = null
    private val selezioni = mutableMapOf<UUID, Set<Long>>()

    /** Campi con la selezione bloccata (Blocca di Qlik): i clic non la cambiano, Cancella non la toglie. */
    private val bloccati = mutableSetOf<UUID>()
    private val campiUi = mutableListOf<CampoUi>()
    private val nomiCampi = mutableMapOf<UUID, String>()
    private val corpoSelezioni = Div()

    private val indietroButton = Button("Indietro", Icon(VaadinIcon.ARROW_BACKWARD)).apply {
        addClassName("lbi-qv-clear")
        element.setAttribute("title", "Torna alla selezione precedente")
        addClickListener { indietro() }
    }
    private val avantiButton = Button("Avanti", Icon(VaadinIcon.ARROW_FORWARD)).apply {
        addClassName("lbi-qv-clear")
        element.setAttribute("title", "Rifai la selezione annullata")
        addClickListener { avanti() }
    }
    private val bloccaButton = Button("Blocca", Icon(VaadinIcon.LOCK)).apply {
        addClassName("lbi-qv-clear")
        element.setAttribute("title", "Blocca tutte le selezioni correnti")
        addClickListener { bloccaTutto() }
    }
    private val sbloccaButton = Button("Sblocca", Icon(VaadinIcon.UNLOCK)).apply {
        addClassName("lbi-qv-clear")
        element.setAttribute("title", "Sblocca tutte le selezioni")
        addClickListener { sbloccaTutto() }
    }

    // Barra del calendario: componente ("anno", "mese", "giorno") -> dimensione del campo derivato.
    private val dimCalendario = mutableMapOf<String, UUID>()
    private val contenitoreAnni = Div().apply { className = "lbi-qv-chips lbi-qv-chips-anno" }
    private val contenitoreMesi = Div().apply { className = "lbi-qv-chips lbi-qv-chips-mese" }
    private val contenitoreGiorni = Div().apply { className = "lbi-qv-chips lbi-qv-chips-giorno" }

    // Ditta (solo admin): la dimensione del campo azienda, se il dataset ce l'ha.
    private var dimDitta: UUID? = null
    private val contenitoreDitte = Div().apply { className = "lbi-qv-chips lbi-qv-chips-anno" }

    // Campo azienda di un non-admin: forzato dal section access, quindi non si mostra.
    private var dimNascosta: UUID? = null

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
        dimDitta = if (area != null && adminGuard.isAdmin()) sezioneAccessoService.dimensioneAzienda(area.id) else null
        dimNascosta = if (area != null && !adminGuard.isAdmin()) sezioneAccessoService.dimensioneAzienda(area.id) else null
        selezioni.clear()
        bloccati.clear()
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
        aggiornaPulsantiCronologia()
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
                    nomiCampi[dimId] = campo.etichetta
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

        val barra = Div(titolo, cancella, indietroButton, avantiButton, bloccaButton, sbloccaButton)
        // Come il Reload di Qlik: solo chi costruisce il modello (admin) ricarica i dati.
        if (adminGuard.isAdmin()) {
            barra.add(
                Button("Aggiorna indice", Icon(VaadinIcon.REFRESH)) { aggiornaIndice(area) }
                    .apply { addClassName("lbi-qv-clear") },
                Button("Sincronizza dati", Icon(VaadinIcon.DOWNLOAD)) { sincronizza(area) }
                    .apply { addClassName("lbi-qv-clear") }
            )
        }
        barra.add(barraCalendario())
        return barra.apply { className = "lbi-qv-top" }
    }

    /**
     * Anno / Mese / Giorno, come in Qlik: sono campi (i derivati del campo data scelto in Modello
     * dati) e si selezionano come gli altri. Finché il calendario non è configurato i valori
     * restano grigi e non si possono cliccare. Per l'admin anche Ditta.
     */
    private fun barraCalendario(): Component {
        fun gruppo(titolo: String, chips: Component) =
            Div(Span(titolo).apply { className = "lbi-qv-cal-label" }, chips).apply { className = "lbi-qv-cal-group" }

        contenitoreAnni.removeAll()
        contenitoreMesi.removeAll()
        contenitoreGiorni.removeAll()
        calendarioService.nomiMesi().forEach { contenitoreMesi.add(chip(it)) }
        (1..31).forEach { contenitoreGiorni.add(chip(it.toString())) }

        contenitoreDitte.removeAll()
        val barra = Div(
            gruppo("ANNO", contenitoreAnni),
            gruppo("MESE", contenitoreMesi),
            gruppo("GIORNO", contenitoreGiorni)
        ).apply { className = "lbi-qv-cal" }
        // La ditta si sceglie solo da admin: per gli altri è forzata dal section access.
        if (dimDitta != null) barra.add(gruppo("DITTA", contenitoreDitte))
        return barra
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
            campi.sortedBy { it.etichetta.lowercase() }.forEach { campo ->
                val dimId = dimensioni[occorrenzaId to campo.colonna] ?: return@forEach
                if (dimId in dimCalendario.values) return@forEach  // sta nella barra in alto
                if (dimId == dimNascosta) return@forEach  // azienda forzata: il non-admin non la vede
                val ui = CampoUi(area.id, dimId, campo.etichetta)
                campiUi += ui
                nomiCampi[dimId] = campo.etichetta
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

    /**
     * Le selezioni salvate dell'utente e i loro blocchi. Quelle su campi che il dataset non ha più
     * (campo tolto o rinominato) si scartano e lo stato pulito si salva: niente più "?".
     */
    private fun caricaSelezioni(area: Area) {
        val utente = CurrentUserHolder.get() ?: return
        val stato = userPivotStateRepository.find(utente.userId, area.id) ?: return
        val valide = registryRepository.findDimensioniByArea(area.id).map { it.dimensioneId }.toSet()
        stato.selections.forEach { (dimId, valori) ->
            if (valori.isNotEmpty() && dimId in valide) selezioni[dimId] = valori
        }
        // Un blocco vale solo su un campo che ha una selezione.
        bloccati.addAll(stato.bloccati.filter { it in selezioni })
        if (stato.selections.filterValues { it.isNotEmpty() }.keys.any { it !in valide }) salvaSelezioni()
    }

    private fun salvaSelezioni() {
        val area = areaCorrente ?: return
        val utente = CurrentUserHolder.get() ?: return
        val attuale = userPivotStateRepository.find(utente.userId, area.id)
        val nuove = selezioni.toMap()
        val blocchi = bloccati.filter { it in selezioni }.toSet()
        userPivotStateRepository.save(
            attuale?.copy(selections = nuove, bloccati = blocchi, updatedAt = Instant.now())
                ?: UserPivotState(utente.userId, area.id, null, nuove, blocchi)
        )
    }

    /**
     * Un clic su un valore lo aggiunge alla selezione del campo, un altro clic sullo stesso lo
     * toglie. Più valori dello stesso campo si combinano in OR, i campi diversi in AND.
     * Un valore ESCLUSO (grigio) si può selezionare: come in Qlik le selezioni in conflitto
     * con quel valore si annullano, ma non quelle bloccate.
     */
    private fun alClic(dimId: UUID, valore: ValoreFiltro, @Suppress("UNUSED_PARAMETER") ctrl: Boolean) {
        if (dimId in bloccati) {
            Notification.show("La selezione di questo campo è bloccata: sbloccala per cambiarla", 3000, Notification.Position.BOTTOM_START)
            return
        }
        val nuove = selezioni.toMutableMap()
        val attuali = nuove[dimId].orEmpty()
        if (valore.id in attuali) {
            val resto = attuali - valore.id
            if (resto.isEmpty()) nuove.remove(dimId) else nuove[dimId] = resto
            applicaSelezioni(nuove)
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
            // Come in Qlik: un valore in conflitto con una selezione bloccata non si può selezionare.
            if (daTogliere.any { it in bloccati }) {
                Notification.show("Valore in conflitto con una selezione bloccata", 3000, Notification.Position.BOTTOM_START)
                return
            }
            daTogliere.forEach { nuove.remove(it) }
        }
        nuove[dimId] = attuali + valore.id
        applicaSelezioni(nuove)
    }

    private fun impostaSelezione(dimId: UUID, valori: Set<Long>) {
        val nuove = selezioni.toMutableMap()
        if (valori.isEmpty()) nuove.remove(dimId) else nuove[dimId] = valori
        applicaSelezioni(nuove)
    }

    /**
     * Unico punto in cui cambiano le selezioni: le bloccate restano com'erano, cronologia
     * (tranne Indietro/Avanti), salvataggio, aggiornamento.
     */
    private fun applicaSelezioni(nuove: Map<UUID, Set<Long>>, registra: Boolean = true) {
        val area = areaCorrente ?: return
        val conBlocchi = nuove.toMutableMap().apply { bloccati.forEach { d -> selezioni[d]?.let { put(d, it) } } }
        val pulite = conBlocchi.filterValues { it.isNotEmpty() }
        if (pulite == selezioni.toMap()) {
            aggiornaPulsantiCronologia()
            return
        }
        if (registra) CronologiaSelezioni.registra(area.id, selezioni.toMap())
        selezioni.clear()
        selezioni.putAll(pulite)
        salvaSelezioni()
        aggiornaTutto()
        aggiornaPulsantiCronologia()
    }

    /** Come "Cancella selezioni" di Qlik: svuota le selezioni (tranne le bloccate), condivise con Analisi e Grafici. */
    private fun cancellaSelezioni() {
        if (selezioni.isEmpty()) return
        applicaSelezioni(emptyMap())
        Notification.show("Selezioni cancellate", 2500, Notification.Position.BOTTOM_START)
    }

    private fun indietro() {
        val area = areaCorrente ?: return
        CronologiaSelezioni.indietro(area.id, selezioni.toMap())?.let { applicaSelezioni(it, registra = false) }
        aggiornaPulsantiCronologia()
    }

    private fun avanti() {
        val area = areaCorrente ?: return
        CronologiaSelezioni.avanti(area.id, selezioni.toMap())?.let { applicaSelezioni(it, registra = false) }
        aggiornaPulsantiCronologia()
    }

    /** Blocca tutte le selezioni correnti, come Blocca di Qlik. */
    private fun bloccaTutto() {
        if (selezioni.isEmpty()) return
        bloccati.addAll(selezioni.keys)
        dopoCambioBlocchi()
    }

    /** Toglie tutti i blocchi. */
    private fun sbloccaTutto() {
        if (bloccati.isEmpty()) return
        bloccati.clear()
        dopoCambioBlocchi()
    }

    /** Blocca o sblocca un solo campo (lucchetto in Selezioni correnti). */
    private fun cambiaBlocco(dimId: UUID) {
        if (!bloccati.remove(dimId) && dimId in selezioni) bloccati.add(dimId)
        dopoCambioBlocchi()
    }

    /** I blocchi non cambiano i dati: si salvano e si aggiornano solo Selezioni correnti e pulsanti. */
    private fun dopoCambioBlocchi() {
        salvaSelezioni()
        aggiornaSelezioniCorrenti()
        aggiornaPulsantiCronologia()
    }

    private fun aggiornaPulsantiCronologia() {
        val area = areaCorrente
        indietroButton.isEnabled = area != null && CronologiaSelezioni.puoIndietro(area.id)
        avantiButton.isEnabled = area != null && CronologiaSelezioni.puoAvanti(area.id)
        bloccaButton.isEnabled = selezioni.keys.any { it !in bloccati }
        sbloccaButton.isEnabled = bloccati.isNotEmpty()
    }

    // ================= Aggiornamento =================

    /**
     * Dopo ogni cambio: contatori dei campi e barra (Anno, Mese, Giorno, Ditta) partono tutti
     * insieme, poi si mostrano. Il vincolo della ditta si calcola qui, nel thread della pagina:
     * i thread del pool non hanno la sessione dell'utente.
     */
    private fun aggiornaTutto() {
        val area = areaCorrente ?: return
        val sel = selezioni.toMap()
        val vincolo = try {
            sezioneAccessoService.vincolo(area.id)
        } catch (e: SecurityException) {
            Notification.show(e.message ?: "Accesso non consentito", 6000, Notification.Position.MIDDLE)
            return
        }
        val dimContatori = campiUi.map { it.dimId }

        val fContatori = poolQuery.avvia { filtriService.contatori(area.id, sel, dimContatori, vincolo) }
        fun valori(dimId: UUID?) = dimId?.let { d ->
            poolQuery.avvia { filtriService.valori(area.id, sel, d, null, 0, MAX_CHIP, vincolo) }
        }
        val fAnni = valori(dimCalendario["anno"])
        val fMesi = valori(dimCalendario["mese"])
        val fGiorni = valori(dimCalendario["giorno"])
        val fDitte = valori(dimDitta)

        try {
            val contatori = poolQuery.attendi(fContatori)
            campiUi.forEach { it.mostra(contatori[it.dimId]) }
        } catch (e: Exception) {
            log.warn("Calcolo dei contatori fallito per il dataset {}", area.id, e)
            Notification.show(
                "Impossibile calcolare i filtri: ${e.message ?: e::class.simpleName}. " +
                        "Se il dataset non è mai stato sincronizzato, premi Sincronizza.",
                8000, Notification.Position.MIDDLE
            )
        }

        try {
            dimCalendario["anno"]?.let { dimId ->
                contenitoreAnni.removeAll()
                poolQuery.attendi(fAnni!!).sortedBy { it.etichetta.toLongOrNull() ?: Long.MAX_VALUE }
                    .forEach { contenitoreAnni.add(chipValore(dimId, it)) }
            }
            dimCalendario["mese"]?.let { dimId ->
                val perEtichetta = poolQuery.attendi(fMesi!!).associateBy { it.etichetta }
                contenitoreMesi.removeAll()
                calendarioService.nomiMesi().forEach { nome ->
                    contenitoreMesi.add(perEtichetta[nome]?.let { chipValore(dimId, it) } ?: chip(nome))
                }
            }
            dimCalendario["giorno"]?.let { dimId ->
                val perEtichetta = poolQuery.attendi(fGiorni!!).associateBy { it.etichetta }
                contenitoreGiorni.removeAll()
                (1..31).forEach { giorno ->
                    contenitoreGiorni.add(perEtichetta[giorno.toString()]?.let { chipValore(dimId, it) } ?: chip(giorno.toString()))
                }
            }
        } catch (e: Exception) {
            log.warn("Calcolo del calendario fallito per il dataset {}", area.id, e)
        }

        // Ditte (solo admin): nome dell'azienda e colore del loro stato.
        dimDitta?.let { dimId ->
            try {
                contenitoreDitte.removeAll()
                poolQuery.attendi(fDitte!!).sortedBy { it.etichetta.toIntOrNull() ?: Int.MAX_VALUE }.forEach { v ->
                    val nome = v.etichetta.toIntOrNull()?.let { Azienda.fromCodice(it)?.label }
                    contenitoreDitte.add(chipValore(dimId, v).apply { text = nome ?: v.etichetta })
                }
            } catch (e: Exception) {
                log.warn("Calcolo delle ditte fallito per il dataset {}", area.id, e)
            }
        }

        campiUi.forEach { campo ->
            try {
                campo.lista?.takeIf { it.isVisible }?.aggiorna()
            } catch (e: Exception) {
                log.warn("Aggiornamento dell'elenco di '{}' fallito", campo.nome, e)
            }
        }
        aggiornaSelezioniCorrenti()
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
            val bloccato = dimId in bloccati
            val lucchetto = Button(Icon(if (bloccato) VaadinIcon.LOCK else VaadinIcon.UNLOCK)) { cambiaBlocco(dimId) }.apply {
                addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL)
                element.setAttribute("title", if (bloccato) "Sblocca questo campo" else "Blocca questo campo")
            }
            val togli = Button(Icon(VaadinIcon.CLOSE_SMALL)) { impostaSelezione(dimId, emptySet()) }.apply {
                addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL)
                element.setAttribute("title", "Togli la selezione di questo campo")
                // Una selezione bloccata non si toglie: prima si sblocca.
                isEnabled = !bloccato
            }
            corpoSelezioni.add(
                Div(
                    Span("●").apply { className = "lbi-qv-sel-dot" },
                    Span(nomiCampi[dimId] ?: "?").apply { className = "lbi-qv-sel-campo" },
                    Span(testo).apply { className = "lbi-qv-sel-valori" },
                    lucchetto,
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

    /**
     * Ricostruisce solo l'indice del dataset, senza ricaricare i dati: dopo una modifica al
     * modello o a un campo. Gira in un thread a parte, con una finestra di avanzamento. Solo admin.
     */
    private fun aggiornaIndice(area: Area) {
        if (!adminGuard.isAdmin()) return
        val ui = getUI().orElse(null) ?: return

        val avanzamento = Span("Avvio...")
        val dialog = Dialog().apply {
            headerTitle = "Aggiorna indice di ${area.nome}"
            width = "480px"
            isCloseOnEsc = false
            isCloseOnOutsideClick = false
        }
        dialog.add(VerticalLayout(ProgressBar().apply { isIndeterminate = true }, avanzamento).apply { isPadding = false })
        dialog.open()

        val inizio = System.currentTimeMillis()
        Thread {
            try {
                etlOrchestrator.aggiornaIndice(area.id) { frase -> ui.access { avanzamento.text = frase } }
                val secondi = (System.currentTimeMillis() - inizio) / 1000
                ui.access {
                    dialog.close()
                    Notification.show("Indice aggiornato in $secondi s", 5000, Notification.Position.BOTTOM_END)
                    if (areaCorrente?.id == area.id) aggiornaTutto()
                }
            } catch (e: Exception) {
                ui.access {
                    dialog.close()
                    Notification.show(
                        "Aggiornamento dell'indice fallito: ${e.message ?: e::class.simpleName}",
                        8000, Notification.Position.MIDDLE
                    )
                }
            }
        }.apply {
            isDaemon = true
            name = "indice-dataset-${area.id}"
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
                        LbiSidebarMenu.MenuEntry("Aggiorna indice", enabled = area != null, icon = VaadinIcon.REFRESH) {
                            area?.let { aggiornaIndice(it) }
                        },
                        LbiSidebarMenu.MenuEntry("Sincronizza dati", enabled = area != null, icon = VaadinIcon.DOWNLOAD) {
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

        /** Quanti valori leggere per Anno, Mese, Giorno e Ditta: bastano per anni, 12 mesi e 31 giorni. */
        const val MAX_CHIP = 100
    }
}