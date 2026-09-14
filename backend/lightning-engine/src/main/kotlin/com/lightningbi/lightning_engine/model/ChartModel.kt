package com.lightningbi.lightning_engine.model

import java.time.Instant
import java.util.UUID

/** Tipi di grafico supportati. */
enum class ChartType {
    BAR,
    LINE,
    /**
     * Il PIE mostra una composizione: con troppe fette diventa illeggibile
     * e con valori negativi non ha senso. Chi lo sceglie dovrebbe sempre
     * impostare anche maxItems.
     */
    PIE,
    AREA
}

/**
 * Configurazione persistita di un grafico.
 *
 * Una sola dimensione di raggruppamento per grafico: le serie multiple
 * (es. fatturato per mese E per azienda) raddoppiano la complessità di
 * modello e rendering. Si potranno aggiungere in seguito con un campo
 * seriesDimId nullable, senza rompere i grafici esistenti.
 */
data class AreaChart(
    val id: UUID,
    val areaId: UUID,
    val titolo: String,
    val tipo: ChartType,
    /** Dimensione sull'asse X (o le fette, per il PIE). */
    val groupByDimId: UUID,
    /** Metrica misurata sull'asse Y. */
    val metricaId: UUID,
    val orderBy: AggregateOrder = AggregateOrder.DIMENSION,
    /** Top-N. Null = tutte le righe fino al limite di sistema. */
    val maxItems: Int? = null,
    /** Ordine nella dashboard dell'Analisi. */
    val posizione: Int = 0,
    val createdAt: Instant = Instant.now()
)

/** Un grafico pronto da disegnare: configurazione più dati risolti. */
data class ChartData(
    val chart: AreaChart,
    /** Etichette dell'asse X, già ordinate. */
    val labels: List<String>,
    /** Valori, nello stesso ordine delle etichette. */
    val values: List<Double>,
    /** Nome della metrica, per la legenda e il tooltip. */
    val metricaNome: String,
    /** True se i dati sono stati troncati: va segnalato all'utente. */
    val truncated: Boolean
)