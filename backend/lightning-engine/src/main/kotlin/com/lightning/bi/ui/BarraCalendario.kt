package com.lightning.bi.ui

import com.lightning.bi.service.CalendarioService
import com.lightning.bi.service.FiltriService
import com.lightning.bi.service.PoolQuery
import com.lightning.bi.service.SezioneAccessoService
import com.lightning.bi.service.StatoValore
import com.lightning.bi.service.ValoreFiltro
import com.lightning.piattaforma.model.Azienda
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import com.vaadin.flow.component.html.NativeButton

/**
 * Anno / Mese / Giorno (i derivati del campo data scelto in Modello dati) e, per l'admin, Ditta.
 * Sono campi come gli altri: un clic seleziona, i colori sono quelli degli stati.
 *
 * Le query partono in parallelo con [avvia], che restituisce la funzione da chiamare
 * (nel thread della pagina) per mostrare i risultati.
 *
 * @param calendario componente ("anno", "mese", "giorno") -> dimensione; vuoto se non configurato
 * @param ditta la dimensione dell'azienda, solo per l'admin (null = non si mostra)
 */
class BarraCalendario(
    private val areaId: UUID,
    private val calendario: Map<String, UUID>,
    private val ditta: UUID?,
    private val stato: StatoSelezioni,
    private val filtriService: FiltriService,
    private val poolQuery: PoolQuery,
    private val calendarioService: CalendarioService
) : Div() {

    private val log = LoggerFactory.getLogger(BarraCalendario::class.java)

    private val anni = Div().apply { className = "lt-cal-valori" }
    private val mesi = Div().apply { className = "lt-cal-valori" }
    private val giorni = Div().apply { className = "lt-cal-valori lt-cal-giorni" }
    private val ditte = Div().apply { className = "lt-cal-valori" }

    init {
        className = "lt-barra-cal"
        element.setAttribute("aria-label", "Calendario")
        add(gruppo("ANNO", anni), gruppo("MESE", mesi), gruppo("GIORNO", giorni))
        if (ditta != null) add(gruppo("DITTA · solo admin", ditte))
        // Finché non arrivano i dati (o se il calendario non è configurato): tutto grigio.
        calendarioService.nomiMesi().forEach { mesi.add(vuoto(it, 32)) }
        (1..31).forEach { giorni.add(vuoto(it.toString(), 26)) }
    }

    /** Fa partire le query; la funzione restituita le aspetta e mostra i valori. */
    fun avvia(selezioni: Map<UUID, Set<Long>>, vincolo: SezioneAccessoService.Vincolo?): () -> Unit {
        fun valori(dimId: UUID?): CompletableFuture<List<ValoreFiltro>>? = dimId?.let { d ->
            poolQuery.avvia { filtriService.valori(areaId, selezioni, d, null, 0, MAX_VALORI, vincolo) }
        }
        val fAnni = valori(calendario["anno"])
        val fMesi = valori(calendario["mese"])
        val fGiorni = valori(calendario["giorno"])
        val fDitte = valori(ditta)

        return {
            mostra("anno", fAnni) { dimId, valori ->
                anni.removeAll()
                valori.sortedBy { it.etichetta.toLongOrNull() ?: Long.MAX_VALUE }.forEach { anni.add(chip(dimId, it, 40)) }
            }
            mostra("mese", fMesi) { dimId, valori ->
                val perNome = valori.associateBy { it.etichetta }
                mesi.removeAll()
                calendarioService.nomiMesi().forEach { nome -> mesi.add(perNome[nome]?.let { chip(dimId, it, 32) } ?: vuoto(nome, 32)) }
            }
            mostra("giorno", fGiorni) { dimId, valori ->
                val perNumero = valori.associateBy { it.etichetta }
                giorni.removeAll()
                (1..31).forEach { g -> giorni.add(perNumero[g.toString()]?.let { chip(dimId, it, 26) } ?: vuoto(g.toString(), 22)) }
            }
            if (ditta != null && fDitte != null) {
                try {
                    ditte.removeAll()
                    poolQuery.attendi(fDitte).sortedBy { it.etichetta.toIntOrNull() ?: Int.MAX_VALUE }.forEach { v ->
                        val nome = v.etichetta.toIntOrNull()?.let { Azienda.fromCodice(it)?.label } ?: v.etichetta
                        ditte.add(chip(ditta, v, 34, nome))
                    }
                } catch (e: Exception) {
                    log.warn("Calcolo delle ditte fallito per il dataset {}", areaId, e)
                }
            }
        }
    }

    private fun mostra(
        componente: String,
        futuro: CompletableFuture<List<ValoreFiltro>>?,
        disegna: (UUID, List<ValoreFiltro>) -> Unit
    ) {
        val dimId = calendario[componente] ?: return
        if (futuro == null) return
        try {
            disegna(dimId, poolQuery.attendi(futuro))
        } catch (e: Exception) {
            log.warn("Calcolo di '{}' fallito per il dataset {}", componente, areaId, e)
        }
    }

    private fun gruppo(titolo: String, valori: Div) =
        Div(Span(titolo).apply { className = "lt-cal-titolo" }, valori).apply { className = "lt-cal-gruppo" }

    private fun chip(dimId: UUID, valore: ValoreFiltro, larghezza: Int, testo: String = valore.etichetta): NativeButton =
        NativeButton(testo) {
            stato.clic(dimId, valore)?.let { Notification.show(it, 3000, Notification.Position.BOTTOM_START) }
        }.apply {
            className = "lt-chip-valore " + classeStato(valore.stato)
            style.set("min-width", "${larghezza}px")
            element.setAttribute("aria-pressed", (valore.stato == StatoValore.SELEZIONATO).toString())
        }

    /** Un valore senza dati (es. il 31 di un mese corto): grigio, non si clicca. */
    private fun vuoto(testo: String, larghezza: Int): Span =
        Span(testo).apply {
            className = "lt-chip-valore lt-esc"
            style.set("min-width", "${larghezza}px")
        }

    private companion object {
        /** Bastano per gli anni, 12 mesi, 31 giorni e le ditte. */
        const val MAX_VALORI = 100

        fun classeStato(s: StatoValore) = when (s) {
            StatoValore.SELEZIONATO -> "lt-sel"
            StatoValore.POSSIBILE -> "lt-pos"
            StatoValore.ESCLUSO -> "lt-esc"
        }
    }
}