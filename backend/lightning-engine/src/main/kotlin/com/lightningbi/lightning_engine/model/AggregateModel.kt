package com.lightningbi.lightning_engine.model

import java.math.BigDecimal
import java.util.UUID

/** Come ordinare le righe aggregate. */
enum class AggregateOrder {
    /** Per etichetta della dimensione di raggruppamento (A→Z). */
    DIMENSION,
    /** Per valore della metrica, decrescente. Richiede orderMetricId. */
    METRIC_DESC,
    /** Per valore della metrica, crescente. Richiede orderMetricId. */
    METRIC_ASC
}

data class AggregateRequest(
    val areaId: UUID,
    val selections: Map<UUID, Set<Long>> = emptyMap(),
    val groupBy: List<UUID> = emptyList(),

    /**
     * Metriche da calcolare. Vuoto = tutte quelle dell'area.
     * Un grafico ne chiede una: calcolarle tutte è spreco su aree ricche.
     */
    val metricIds: List<UUID> = emptyList(),

    /**
     * Ordinamento. Null = nessuno (comportamento storico, adatto alla grid).
     * Per un grafico va sempre valorizzato: una serie non ordinata è illeggibile.
     */
    val order: AggregateOrder? = null,

    /** Metrica su cui ordinare, se order è METRIC_*. */
    val orderMetricId: UUID? = null,

    /**
     * Numero massimo di righe. Null = limite di sistema.
     * Con order METRIC_* produce un vero top-N calcolato dal database;
     * con DIMENSION o senza ordine è un semplice troncamento.
     */
    val limit: Int? = null,

    /** Se true, ogni riga porta anche le etichette leggibili delle chiavi. */
    val resolveLabels: Boolean = false
)

data class AggregateRow(
    /** dimensioneId -> value_id */
    val groupKeys: Map<UUID, Long>,
    /** nome metrica -> valore */
    val values: Map<String, BigDecimal>,
    /** dimensioneId -> etichetta. Popolato solo se richiesto. */
    val labels: Map<UUID, String> = emptyMap()
)

data class AggregateResult(
    val rows: List<AggregateRow>,
    val truncated: Boolean
)