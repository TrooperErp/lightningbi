package com.lightning.bi.ui

import com.lightning.bi.model.Area
import com.lightning.bi.model.RuoloTabella
import com.lightning.bi.repository.RegistryRepository
import com.lightning.bi.repository.UserPivotStateRepository
import com.lightning.bi.service.AccessoDatasetService
import com.lightning.bi.service.CalendarioService
import com.lightning.bi.service.CampoEffettivo
import com.lightning.bi.service.ContatoreCampo
import com.lightning.bi.service.DatasetService
import com.lightning.bi.service.FiltriService
import com.lightning.bi.service.PoolQuery
import com.lightning.bi.service.RiferimentoCampo
import com.lightning.bi.service.SezioneAccessoService
import com.lightning.piattaforma.service.AdminGuard
import com.lightning.piattaforma.service.Cronometro
import com.lightning.piattaforma.ui.LightningLayout
import com.lightning.piattaforma.ui.Notifiche
import com.lightning.piattaforma.ui.PaginaLightning
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.router.AfterNavigationEvent
import com.vaadin.flow.router.AfterNavigationObserver
import com.vaadin.flow.router.BeforeEvent
import com.vaadin.flow.router.HasUrlParameter
import com.vaadin.flow.router.OptionalParameter
import com.vaadin.flow.router.Route
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.UUID
import com.lightning.bi.motore.ConfrontoMotori
import com.lightning.bi.motore.Motore

/**
 * Il foglio "Filtri" di un dataset (come "Impostazione filtri" di Qlik), dentro la shell:
 * schede dei fogli, barra delle selezioni, calendario, un box per tabella con i suoi campi
 * e il contatore possibili/totali. Un clic su un campo apre l'elenco dei suoi valori.
 *
 * Rotta "bi/filtri/{dataset}".
 */
@Route(value = "bi/filtri", layout = LightningLayout::class)
class FiltriView(
    private val accessoDatasetService: AccessoDatasetService,
    private val datasetService: DatasetService,
    private val registryRepository: RegistryRepository,
    private val userPivotStateRepository: UserPivotStateRepository,
    private val filtriService: FiltriService,
    private val poolQuery: PoolQuery,
    private val calendarioService: CalendarioService,
    private val sezioneAccessoService: SezioneAccessoService,
    private val adminGuard: AdminGuard,
    private val fogliBi: FogliBi,
    private val etlOrchestrator: com.lightning.bi.etl.EtlOrchestrator,
    private val areaSourceRepository: com.lightning.bi.repository.AreaSourceRepository,
    private val confrontoMotori: ConfrontoMotori,
    private val motore: Motore
) : Div(), PaginaLightning, HasUrlParameter<String>, AfterNavigationObserver {

    override val moduloId = "bi"

    private val log = LoggerFactory.getLogger(FiltriView::class.java)

    private var aree: List<Area> = emptyList()
    private var area: Area? = null
    private var fogli: List<Foglio> = emptyList()
    private var stato: StatoSelezioni? = null
    private var calendario: BarraCalendario? = null
    private val campi = mutableListOf<CampoUi>()
    private val nomiCampi = mutableMapOf<UUID, String>()

    init {
        className = "lt-pagina-bi"
    }

    override fun setParameter(event: BeforeEvent, @OptionalParameter parameter: String?) {
        aree = accessoDatasetService.areeVisibili().sortedBy { it.nome.lowercase() }
        val richiesta = parameter?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        costruisci(aree.firstOrNull { it.id == richiesta } ?: aree.firstOrNull())
    }

    /** Anche quando si passa da un dataset all'altro (stessa pagina): pannello, percorso, dati. */
    override fun afterNavigation(event: AfterNavigationEvent) {
        layout()?.apply {
            impostaPercorso(percorso())
            impostaPannello(pannello())
        }
        Cronometro.clic("apri filtri") { aggiorna() }
    }

    override fun percorso() = listOfNotNull(area?.nome, "Filtri")

    override fun pannello(): Component = PannelloBi(aree, area?.id, fogli, FogliBi.ID_FILTRI)

    private fun layout() = findAncestor(LightningLayout::class.java)

    // ================= Costruzione =================

    private fun costruisci(scelta: Area?) {
        removeAll()
        campi.clear()
        nomiCampi.clear()
        stato = null
        calendario = null
        area = scelta

        if (scelta == null) {
            fogli = emptyList()
            add(Div(Span("Non hai ancora accesso a nessun dataset.").apply { className = "lt-vuoto" }).apply { className = "lt-pagina" })
            return
        }

        fogli = fogliBi.di(scelta.id)
        val st = StatoSelezioni(scelta.id, motore, userPivotStateRepository, registryRepository).also { it.carica() }
        stato = st

        val admin = adminGuard.isAdmin()
        val dimAzienda = sezioneAccessoService.dimensioneAzienda(scelta.id)
        val bozza = datasetService.carica(scelta.id)

        // (occorrenza, colonna) -> dimensione
        val dimensioni = registryRepository.findDimensioniByArea(scelta.id)
            .filter { it.areaTabellaId != null }
            .associate { (it.areaTabellaId!! to it.colonnaFisica) to it.dimensioneId }

        val dimCalendario = mutableMapOf<String, UUID>()
        bozza?.campiCalendario()?.forEach { (componente, campo) ->
            dimensioni[campo.occorrenzaId to campo.colonna]?.let { dimId ->
                dimCalendario[componente] = dimId
                nomiCampi[dimId] = campo.etichetta
            }
        }

        val box = Div().apply { className = "lt-box-griglia" }
        if (bozza == null || bozza.occorrenze.isEmpty()) {
            box.add(Span("Il dataset non ha ancora tabelle: aggiungile da Modello dati.").apply { className = "lt-vuoto" })
        } else {
            val visibili = bozza.campi().filter { !it.escluso && !it.tecnico }
            bozza.occorrenze.forEach { occ ->
                val suoi = visibili
                    .filter { it.occorrenzaId == occ.id && RiferimentoCampo(occ.id, it.colonna) in bozza.dimensioni }
                    .mapNotNull { c -> dimensioni[occ.id to c.colonna]?.let { c to it } }
                    .filter { (_, dimId) -> dimId !in dimCalendario.values && (admin || dimId != dimAzienda) }
                val ruolo = when {
                    occ.disconnessa -> "Scollegata"
                    occ.ruolo == RuoloTabella.FATTI -> "Fatti"
                    else -> "Dimensione"
                }
                box.add(boxTabella(scelta.id, occ.alias, ruolo, suoi, st))
            }
        }

        val barraSelezioni = BarraSelezioni(st, filtriService) { nomiCampi[it] ?: "?" }
        val cal = BarraCalendario(
            scelta.id, dimCalendario, if (admin) dimAzienda else null,
            st, motore, poolQuery, calendarioService
        )
        calendario = cal

        val barraFogli = BarraFogli(scelta.id, fogli, FogliBi.ID_FILTRI, fogliBi)
        if (admin) barraFogli.aggiungiAzione(AzioniDataset(scelta, etlOrchestrator, areaSourceRepository) { aggiorna() })
        add(barraFogli, barraSelezioni, cal, box)
        barraSelezioni.aggiorna()  // ora i nomi dei campi ci sono tutti
        st.alCambio { cambio -> if (cambio == StatoSelezioni.Cambio.DATI) aggiorna() }
    }

    private fun boxTabella(
        areaId: UUID,
        alias: String,
        ruolo: String,
        campiTabella: List<Pair<CampoEffettivo, UUID>>,
        st: StatoSelezioni
    ): Component {
        val corpo = Div()
        if (campiTabella.isEmpty()) {
            corpo.add(Span("Nessun campo da filtrare").apply { className = "lt-vuoto lt-box-nota" })
        }
        campiTabella.sortedBy { it.first.etichetta.lowercase() }.forEach { (campo, dimId) ->
            nomiCampi[dimId] = campo.etichetta
            val ui = CampoUi(areaId, dimId, campo.etichetta, st)
            campi += ui
            corpo.add(ui)
        }
        val testa = Div(
            Span(alias).apply { className = "lt-box-nome" },
            Span(ruolo).apply { className = "lt-badge lt-badge-neutro" }
        ).apply { className = "lt-box-testa" }
        return Div(testa, corpo).apply { className = "lt-box" }
    }

    // ================= Aggiornamento =================

    /** Contatori e calendario partono insieme; il vincolo della ditta si calcola qui (thread della pagina). */
    private fun aggiorna() {
        val a = area ?: return
        val st = stato ?: return
        val sel = st.selezioni
        val vincolo = try {
            Cronometro.pezzo("accesso") { sezioneAccessoService.vincolo(a.id) }
        } catch (e: SecurityException) {
            Notification.show(e.message ?: "Accesso non consentito", 6000, Notification.Position.MIDDLE)
            return
        }
        val inizio = System.nanoTime()

        val fContatori = poolQuery.avvia { Cronometro.pezzo("contatori") { motore.contatori(a.id, sel, campi.map { it.dimId }, vincolo) } }
        confrontoMotori.contatori(a.id, sel, campi.map { it.dimId }, vincolo, nomiCampi.toMap())
        val mostraCalendario = Cronometro.pezzo("calendario avvio") { calendario?.avvia(sel, vincolo) }

        try {
            val contatori = Cronometro.pezzo("attesa contatori") { poolQuery.attendi(fContatori) }
            Cronometro.pezzo("contatori a video") { campi.forEach { it.mostra(contatori[it.dimId]) } }
        } catch (e: Exception) {
            Notification.show(
                Notifiche.testo(log, e, "calcolare i filtri") + ". Se il dataset non è mai stato sincronizzato, sincronizzalo.",
                8000, Notification.Position.MIDDLE
            ).addThemeName("error")
        }
        Cronometro.pezzo("calendario a video") { mostraCalendario?.invoke() }
        campi.forEach { c ->
            try {
                c.lista?.takeIf { it.isVisible }?.aggiorna()
            } catch (e: Exception) {
                log.warn("Aggiornamento dell'elenco di '{}' fallito", c.nome, e)
            }
        }

        val ms = (System.nanoTime() - inizio) / 1_000_000
        layout()?.impostaStato(a.nome, "Filtri $ms ms", "${sel.size} campi selezionati")
    }

    // ================= Campo =================

    /** Una riga di campo: nome, contatore e barra; al clic l'elenco dei valori. */
    private inner class CampoUi(
        val areaId: UUID,
        val dimId: UUID,
        val nome: String,
        private val st: StatoSelezioni
    ) : Div() {
        private val conta = Span("–").apply { className = "lt-campo-conta lt-num" }
        private val riempimento = Span()
        private val riga = NativeButton().apply {
            className = "lt-campo"
            add(
                Span(nome).apply { className = "lt-campo-nome" },
                conta,
                Span(riempimento).apply { className = "lt-campo-barra"; element.setAttribute("aria-hidden", "true") }
            )
            addClickListener { apriChiudi() }
        }
        var lista: ListaValori? = null
            private set

        init {
            add(riga)
        }

        private fun apriChiudi() {
            lista?.let {
                it.isVisible = !it.isVisible
                if (it.isVisible) it.aggiorna()
                return
            }
            val nuova = ListaValori(motore, areaId, dimId, { st.selezioni }, st) { valore ->
                st.clic(dimId, valore)?.let { Notification.show(it, 3000, Notification.Position.BOTTOM_START) }
            }
            lista = nuova
            add(nuova)
        }

        fun mostra(c: ContatoreCampo?) {
            val possibili = c?.possibili ?: 0L
            val totali = c?.totali ?: 0L
            conta.text = "${numero(possibili)}/${numero(totali)}"
            val perc = if (totali > 0) (possibili * 100 / totali).coerceIn(0, 100) else 0
            riempimento.style.set("width", "$perc%")
            riga.classNames.set("lt-campo-sel", (c?.selezionati ?: 0L) > 0)
            riga.classNames.set("lt-campo-esc", totali > 0 && possibili == 0L)
        }
    }

    private companion object {
        fun numero(n: Long): String = "%,d".format(Locale.ITALY, n)
    }
}