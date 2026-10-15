package com.lightning.bi.ui

import com.lightning.bi.service.ContatoreCampo
import com.lightning.bi.service.DimensioneAnalisi
import com.lightning.bi.service.FiltriService
import com.vaadin.flow.component.contextmenu.ContextMenu
import com.vaadin.flow.component.contextmenu.MenuItem
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.value.ValueChangeMode
import java.util.Locale
import java.util.UUID

/** Dove sta un campo nella pivot. */
enum class Zona(val etichetta: String) { RIGHE("Righe"), COLONNE("Colonne") }

/**
 * Il pannello "Campi" dell'Analisi (a sinistra, nella shell): i campi del dataset per tabella,
 * con stato, contatore e la zona in cui stanno. Clic sul nome = elenco dei valori;
 * ⋮ = metti nelle Righe, nelle Colonne o togli dalla pivot.
 *
 * @param zonaDi in quale zona sta un campo (null = non è nella pivot)
 * @param metti sposta un campo in una zona (null = toglilo dalla pivot)
 */
class PannelloCampi(
    private val areaId: UUID,
    private val filtriService: FiltriService,
    private val stato: StatoSelezioni,
    dimensioni: List<DimensioneAnalisi>,
    private val zonaDi: (UUID) -> Zona?,
    private val metti: (UUID, Zona?) -> Unit
) : Div() {

    private val righe = mutableListOf<RigaCampo>()
    private val gruppi = mutableListOf<Gruppo>()

    private val ricerca = TextField().apply {
        className = "lt-campi-cerca"
        placeholder = "Cerca campo"
        isClearButtonVisible = true
        valueChangeMode = ValueChangeMode.LAZY
        valueChangeTimeout = 200
        element.setAttribute("aria-label", "Cerca campo")
    }

    init {
        className = "lt-elenco lt-campi"
        add(Span("CAMPI").apply { className = "lt-elenco-titolo" }, ricerca)
        if (dimensioni.isEmpty()) add(Span("Nessun campo").apply { className = "lt-vuoto lt-elenco-nota" })
        dimensioni.groupBy { it.tabella }.forEach { (tabella, campi) ->
            val suoi = campi.sortedBy { it.nome.lowercase() }.map { RigaCampo(it) }
            righe += suoi
            val gruppo = Gruppo(tabella, suoi)
            gruppi += gruppo
            add(gruppo.box)
        }
        ricerca.addValueChangeListener { filtra(it.value.orEmpty()) }
    }

    fun mostraContatori(contatori: Map<UUID, ContatoreCampo>) = righe.forEach { it.mostra(contatori[it.dimId]) }

    fun aggiornaZone() = righe.forEach { it.aggiornaZona() }

    /** Rilegge gli elenchi dei valori aperti (dopo un cambio di selezioni). */
    fun aggiornaListe() = righe.forEach { r -> r.lista?.takeIf { it.isVisible }?.aggiorna() }

    private fun filtra(testo: String) {
        val t = testo.trim().lowercase()
        gruppi.forEach { it.filtra(t) }
    }

    /**
     * Il box di una tabella, come in Qlik: testata con il nome (clic = chiudi / apri) e i suoi campi.
     * Durante una ricerca i box con campi trovati si aprono da soli; a ricerca vuota tornano come erano.
     */
    private inner class Gruppo(tabella: String, private val suoi: List<RigaCampo>) {
        private var chiuso = false
        private val corpo = Div().apply { className = "lt-campi-corpo" }
        val box = Div().apply { className = "lt-campi-gruppo" }

        init {
            suoi.forEach { corpo.add(it) }
            val testa = NativeButton().apply {
                className = "lt-campi-testa"
                add(
                    Icon(VaadinIcon.CHEVRON_DOWN_SMALL).apply { className = "lt-campi-freccia" },
                    Span(tabella).apply { className = "lt-campi-tabella" },
                    Span(suoi.size.toString()).apply { className = "lt-campi-numero" }
                )
                element.setAttribute("aria-expanded", "true")
                addClickListener {
                    chiuso = !chiuso
                    apri(!chiuso)
                    element.setAttribute("aria-expanded", (!chiuso).toString())
                }
            }
            box.add(testa, corpo)
        }

        private fun apri(aperto: Boolean) {
            corpo.isVisible = aperto
            box.classNames.set("lt-chiuso", !aperto)
        }

        fun filtra(t: String) {
            suoi.forEach { it.isVisible = t.isEmpty() || it.nome.lowercase().contains(t) }
            box.isVisible = suoi.any { it.isVisible }
            apri(if (t.isEmpty()) !chiuso else true)
        }
    }

    private inner class RigaCampo(d: DimensioneAnalisi) : Div() {
        val dimId: UUID = d.dimensioneId
        val nome: String = d.nome

        private val zona = Span().apply { className = "lt-campi-zona" }
        private val conta = Span("–").apply { className = "lt-campo-conta lt-num" }
        private val principale = NativeButton().apply {
            className = "lt-campi-nome"
            add(Span(nome).apply { className = "lt-campo-nome" }, zona, conta)
            element.setAttribute("title", "Clic per vedere e selezionare i valori")
            addClickListener { apriChiudi() }
        }
        private val azioni = NativeButton().apply {
            className = "lt-campi-azioni"
            add(Icon(VaadinIcon.ELLIPSIS_DOTS_V))
            element.setAttribute("aria-label", "Azioni sul campo $nome")
        }
        private val voceRighe: MenuItem
        private val voceColonne: MenuItem
        private val voceTogli: MenuItem
        private val riga = Div(principale, azioni).apply { className = "lt-campi-riga" }

        var lista: ListaValori? = null
            private set

        init {
            add(riga)
            val menu = ContextMenu(azioni).apply { isOpenOnClick = true }
            voceRighe = menu.addItem("Metti nelle Righe") { metti(dimId, Zona.RIGHE) }
            voceColonne = menu.addItem("Metti nelle Colonne") { metti(dimId, Zona.COLONNE) }
            voceTogli = menu.addItem("Togli dalla pivot") { metti(dimId, null) }
            aggiornaZona()
        }

        fun aggiornaZona() {
            val z = zonaDi(dimId)
            zona.text = z?.etichetta ?: ""
            zona.isVisible = z != null
            riga.classNames.set("lt-in-uso", z != null)
            voceRighe.isEnabled = z != Zona.RIGHE
            voceColonne.isEnabled = z != Zona.COLONNE
            voceTogli.isEnabled = z != null
        }

        fun mostra(c: ContatoreCampo?) {
            val possibili = c?.possibili ?: 0L
            val totali = c?.totali ?: 0L
            conta.text = "${numero(possibili)}/${numero(totali)}"
            riga.classNames.set("lt-campo-sel", (c?.selezionati ?: 0L) > 0)
            riga.classNames.set("lt-campo-esc", totali > 0 && possibili == 0L)
        }

        private fun apriChiudi() {
            lista?.let {
                it.isVisible = !it.isVisible
                if (it.isVisible) it.aggiorna()
                return
            }
            val nuova = ListaValori(filtriService, areaId, dimId, { stato.selezioni }, stato) { valore ->
                stato.clic(dimId, valore)?.let { Notification.show(it, 3000, Notification.Position.BOTTOM_START) }
            }
            lista = nuova
            add(nuova)
        }
    }

    private companion object {
        fun numero(n: Long): String = "%,d".format(Locale.ITALY, n)
    }
}