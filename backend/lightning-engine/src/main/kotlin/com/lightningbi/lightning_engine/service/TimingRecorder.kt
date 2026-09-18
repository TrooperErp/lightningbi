package com.lightningbi.lightning_engine.service

import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Raccoglie e logga le misure di performance del calcolo associativo, in
 * formato chiave=valore (non prosa) per restare filtrabile e confrontabile
 * meccanicamente dopo ogni modifica al motore.
 *
 * Livello DEBUG: non intasa i log in produzione normale, ma resta pronto
 * da riaccendere (impostando il logger a DEBUG) la prossima volta che
 * qualcosa rallenta, senza dover reintrodurre println usa-e-getta.
 *
 * Una riga per dimensione (recordDimensionQuery) più una di riepilogo per
 * l'intero calcolo (recordTotalCompute): la prima isola quali dimensioni
 * costano, la seconda dà il quadro d'insieme per confrontare selezioni
 * diverse nel tempo.
 */
object TimingRecorder {
    private val log = LoggerFactory.getLogger("com.lightningbi.lightning_engine.timing")

    /**
     * Una query DISTINCT per una singola dimensione, dentro computeStates.
     *
     * dominioSize: cardinalità totale del dominio della dimensione (da
     * areaDomainCached, prima di ogni filtro).
     * filtroSize: quanti id compaiono nella clausola IN(...) applicata
     * (somma dei valori selezionati sulle altre dimensioni).
     * tempoAttesaSemaforoMs: quanto è rimasta in coda prima di ottenere
     * il permesso dal semaforo di concorrenza.
     * tempoQueryMs: quanto ha impiegato la query vera e propria, una
     * volta ottenuto il permesso.
     * cacheHit: se il dominio è stato letto da Redis invece che da
     * ClickHouse (rilevante solo per areaDomainCached, non per la query
     * DISTINCT filtrata, che oggi non è cachata).
     */
    fun recordDimensionQuery(
        areaId: UUID,
        dimensioneNome: String,
        dominioSize: Int,
        filtroSize: Int,
        tempoAttesaSemaforoMs: Long,
        tempoQueryMs: Long,
        cacheHit: Boolean
    ) {
        log.debug(
            "assoc_dim area={} dim={} dominio_size={} filtro_size={} attesa_semaforo_ms={} query_ms={} cache_hit={}",
            areaId, dimensioneNome, dominioSize, filtroSize, tempoAttesaSemaforoMs, tempoQueryMs, cacheHit
        )
    }

    /**
     * L'intero computeStates per un'area, una selezione, un click.
     *
     * numeroDimensioni: quante dimensioni ha l'area (= quante query
     * DISTINCT sono state lanciate, salvo cache hit sull'intero risultato).
     * cardinalitaSelezioneTotale: somma degli id selezionati su tutte le
     * dimensioni filtrate, la variabile indipendente da correlare con
     * tempoTotaleMs per confermare o smentire l'ipotesi "la latenza
     * cresce con la cardinalità della selezione".
     * cacheHit: se l'intero risultato di computeStates era già in cache
     * (getStates ha trovato la chiave), nel qual caso tempoTotaleMs
     * misura solo la lettura Redis + deserializzazione, non il calcolo.
     */
    fun recordTotalCompute(
        areaId: UUID,
        numeroDimensioni: Int,
        cardinalitaSelezioneTotale: Int,
        tempoTotaleMs: Long,
        cacheHit: Boolean
    ) {
        log.debug(
            "assoc_total area={} num_dim={} cardinalita_selezione={} totale_ms={} cache_hit={}",
            areaId, numeroDimensioni, cardinalitaSelezioneTotale, tempoTotaleMs, cacheHit
        )
    }
}