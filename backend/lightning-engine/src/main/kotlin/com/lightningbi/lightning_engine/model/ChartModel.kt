package com.lightningbi.lightning_engine.model

import java.time.Instant
import java.util.UUID

/**
 * Tipi di grafico supportati.
 *
 * Non tutti sono sempre proponibili: la UI di creazione filtra questa
 * lista in base al contesto corrente (quante dimensioni sono in Righe
 * nel pivot, quante metriche il grafico ha scelto). PIE ad esempio ha
 * senso solo con esattamente una dimensione di raggruppamento e una
 * metrica - con più dimensioni o più metriche va tolto dalle opzioni,
 * non semplicemente sconsigliato.
 */
/**
 * Tipi di grafico supportati.
 *
 * Non tutti sono sempre proponibili: tipiAmmessi() in ChartService filtra
 * questa lista in base al contesto corrente (quante dimensioni sono in
 * Righe nel pivot, quante metriche il grafico ha scelto).
 *
 * SCATTER e RADAR hanno un contratto diverso dagli altri: usano le
 * metriche stesse come assi (SCATTER) o come raggi (RADAR), non la
 * dimensione di raggruppamento come asse X. MAP richiede che la
 * dimensione scelta contenga nomi geografici riconoscibili - questo il
 * sistema non può verificarlo in anticipo, quindi resta sempre proposta
 * ma può fallire a runtime se i valori non sono nomi di stati/regioni.
 */
enum class ChartType {
    BAR,
    BAR_HORIZONTAL,
    LINE,
    AREA,
    PIE,
    DONUT,
    SCATTER,
    RADAR,
    MAP
}

/**
 * Configurazione persistita di un grafico.
 *
 * Il grafico NON ha una propria dimensione di raggruppamento: la eredita
 * sempre dalle Righe del pivot corrente della pagina. Un'analisi è un
 * modo di guardare i dati - se serve guardarli raggruppati in modo
 * diverso, quella è un'altra analisi (anche sulla stessa sorgente, il
 * wizard lo permette senza costi), non un grafico scollegato dentro la
 * stessa pagina. Legare il grafico al pivot garantisce che tabella e
 * grafici raccontino sempre la stessa storia nella stessa vista.
 *
 * Le metriche sono più di una: barre affiancate per confrontare valori
 * imparentati (es. costo e ricavo), vivono in AreaChartMetrica con un
 * ordine esplicito, non qui come lista - coerente con AreaDimensione e
 * AreaMetrica che sono anch'esse tabelle separate dal record area.
 */
data class AreaChart(
    val id: UUID,
    val areaId: UUID,
    val titolo: String,
    val tipo: ChartType,
    val orderBy: AggregateOrder = AggregateOrder.DIMENSION,
    val maxItems: Int? = null,
    val posizione: Int = 0,
    val createdAt: Instant = Instant.now(),
    /**
     * Se true, il grafico genera una serie per ogni valore distinto
     * dell'ultima dimensione di columnBy nel pivot corrente (es. Anno:
     * una serie 2025, una serie 2026), oltre alle Righe come categorie
     * sull'asse X. Se false (default), comportamento storico: una serie
     * per metrica, nessun asse Colonne.
     */
    val followsColumns: Boolean = false,
    /**
     * Attivabile solo se followsColumns=true: colora di rosso ogni punto
     * della serie più recente il cui valore è inferiore al punto
     * corrispondente della serie precedente (stesso principio della
     * variazione % già presente nel pivot). La serie precedente resta
     * arancio, quella più recente blu salvo i punti in calo.
     */
    val highlightDecline: Boolean = false
)

/**
 * Una metrica associata a un grafico, con l'ordine in cui appare come
 * serie (prima barra, seconda barra...).
 */
data class AreaChartMetrica(
    val chartId: UUID,
    val metricaId: UUID,
    val posizione: Int
)

/**
 * Un grafico pronto da disegnare: configurazione più dati risolti sulle
 * Righe correnti del pivot.
 *
 * Le etichette dell'asse X vengono dalle Righe del pivot al momento della
 * richiesta, non sono più fisse nella configurazione del grafico: se
 * l'utente cambia il pivot, lo stesso AreaChart produce un ChartData
 * diverso alla chiamata successiva.
 */
data class ChartData(
    val chart: AreaChart,
    /** Etichette dell'asse X, già ordinate. Composite se le Righe hanno più di una dimensione. */
    val labels: List<String>,
    /**
     * Una serie per metrica, nello stesso ordine di AreaChartMetrica.posizione.
     * Ogni serie ha tanti valori quante etichette in labels, stesso indice.
     */
    val series: List<ChartSeries>,
    /** True se i dati sono stati troncati: va segnalato all'utente. */
    val truncated: Boolean
)

/**
 * Una singola serie di valori nel grafico, con il nome della metrica per legenda/tooltip.
 *
 * pointColors è opzionale: null significa "nessun colore per punto
 * personalizzato, usa il colore di default della serie (palette)".
 * Quando presente, ha la stessa lunghezza di values, stesso indice -
 * usato quando AreaChart.followsColumns genera serie per valore-colonna
 * (es. una serie "2025" arancio, una serie "2026" blu salvo i punti in
 * calo colorati di rosso se highlightDecline è attivo).
 */
data class ChartSeries(
    val metricaNome: String,
    val values: List<Double>,
    val pointColors: List<String?>? = null
)