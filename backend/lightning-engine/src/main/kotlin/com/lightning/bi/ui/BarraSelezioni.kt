package com.lightning.bi.ui

import com.lightning.bi.service.FiltriService
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * La barra delle selezioni in cima ai fogli della BI, come in Qlik Sense: Indietro / Avanti,
 * una chip per campo selezionato (con lucchetto e ×), Blocca / Sblocca / Cancella.
 * Si aggiorna da sola a ogni cambio di [stato].
 *
 * @param nomeCampo il nome mostrato di una dimensione (etichetta del dataset)
 */
class BarraSelezioni(
    private val stato: StatoSelezioni,
    private val filtriService: FiltriService,
    private val nomeCampo: (UUID) -> String
) : Div() {

    private val log = LoggerFactory.getLogger(BarraSelezioni::class.java)

    private val indietro = pulsante(VaadinIcon.ARROW_BACKWARD, "Indietro") { stato.indietro() }
    private val avanti = pulsante(VaadinIcon.ARROW_FORWARD, "Avanti") { stato.avanti() }
    private val chips = Div().apply { className = "lt-sel-chips" }
    private val blocca = Button("Blocca", Icon(VaadinIcon.LOCK)) { stato.bloccaTutto() }
    private val sblocca = Button("Sblocca", Icon(VaadinIcon.UNLOCK)) { stato.sbloccaTutto() }
    private val cancella = Button("Cancella selezioni") { stato.cancella() }.apply { addThemeName("primary") }

    init {
        className = "lt-barra-sel"
        element.setAttribute("aria-label", "Selezioni")
        add(indietro, avanti, Span().apply { className = "lt-separatore" }, chips, blocca, sblocca, cancella)
        stato.alCambio { aggiorna() }
        aggiorna()
    }

    fun aggiorna() {
        indietro.isEnabled = stato.puoIndietro()
        avanti.isEnabled = stato.puoAvanti()
        val sel = stato.selezioni
        val bloccati = stato.bloccati
        blocca.isEnabled = sel.keys.any { it !in bloccati }
        sblocca.isEnabled = bloccati.isNotEmpty()
        cancella.isEnabled = sel.keys.any { it !in bloccati }

        chips.removeAll()
        if (sel.isEmpty()) {
            chips.add(Span("Nessuna selezione").apply { className = "lt-vuoto" })
            return
        }
        sel.entries.sortedBy { nomeCampo(it.key).lowercase() }.forEach { (dimId, valori) ->
            chips.add(chip(dimId, valori, dimId in bloccati))
        }
    }

    private fun chip(dimId: UUID, valori: Set<Long>, bloccato: Boolean): Div {
        val etichette = try {
            filtriService.etichette(stato.areaId, dimId, valori.take(MAX_ETICHETTE))
        } catch (e: Exception) {
            log.warn("Etichette delle selezioni fallite per la dimensione {}", dimId, e)
            emptyList()
        }
        val testo = etichette.joinToString(", ") + if (valori.size > MAX_ETICHETTE) " … (${valori.size})" else ""

        val lucchetto = Button(Icon(if (bloccato) VaadinIcon.LOCK else VaadinIcon.UNLOCK)) { stato.cambiaBlocco(dimId) }.apply {
            className = "lt-chip-btn"
            addThemeName("tertiary")
            element.setAttribute("aria-label", if (bloccato) "Sblocca il campo" else "Blocca il campo")
            setTooltipText(if (bloccato) "Sblocca" else "Blocca")
        }
        val togli = Button(Icon(VaadinIcon.CLOSE_SMALL)) { avvisa(stato.imposta(dimId, emptySet())) }.apply {
            className = "lt-chip-btn"
            addThemeName("tertiary")
            element.setAttribute("aria-label", "Togli la selezione")
            isEnabled = !bloccato
        }
        return Div(
            Span(nomeCampo(dimId)).apply { className = "lt-chip-campo" },
            Span(testo).apply { className = "lt-chip-valori" },
            lucchetto,
            togli
        ).apply {
            className = "lt-chip-sel"
            classNames.set("lt-bloccata", bloccato)
        }
    }

    private fun pulsante(icona: VaadinIcon, titolo: String, azione: () -> Unit) =
        Button(Icon(icona)) { azione() }.apply {
            className = "lt-icona-btn"
            element.setAttribute("aria-label", titolo)
            setTooltipText(titolo)
        }

    private fun avvisa(messaggio: String?) {
        if (messaggio != null) Notification.show(messaggio, 3000, Notification.Position.BOTTOM_START)
    }

    private companion object {
        const val MAX_ETICHETTE = 3
    }
}