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
     * Dimensioni da "ruotare" in colonna (asse Colonne del pivot). Vuoto =
     * comportamento storico, una riga per combinazione di groupBy con le
     * metriche affiancate. Con columnBy valorizzato, ogni combinazione
     * distinta dei valori di columnBy diventa un gruppo di colonne
     * aggiuntivo nel risultato (vedi AggregateRow.values per la
     * convenzione delle chiavi).
     *
     * Oggi supportata una sola dimensione in columnBy: più di una
     * genererebbe una combinatoria di colonne che il pivot UI attuale non
     * saprebbe ancora rendere leggibile (serve prima decidere come
     * annidare le intestazioni). Se columnBy.size > 1, AggregateService
     * lancia IllegalArgumentException con messaggio esplicativo, non
     * tronca silenziosamente alla prima.
     */
    /**
     * Dimensioni da "ruotare" in colonna (asse Colonne del pivot), stile
     * Qlik/Excel pivot table: N dimensioni ammesse, annidate per livelli.
     * Es. columnBy = [Anno, Trimestre] genera intestazioni a due livelli:
     * 2025 { Q1, Q2, Q3, Q4 }, 2026 { Q1, Q2, Q3, Q4 }. L'ordine della
     * lista è l'ordine di annidamento (primo elemento = livello più
     * esterno). Vuoto = comportamento storico, nessun pivot a colonne.
     */
    val columnBy: List<UUID> = emptyList(),
    /**
     * Se true, per ogni coppia di valori consecutivi (ordinati) generati
     * dall'ultima dimensione di columnBy, aggiunge una colonna calcolata
     * di variazione percentuale: (valore_corrente - valore_precedente) /
     * valore_precedente * 100. Generico su qualunque dimensione in
     * columnBy (Anno, Trimestre, Agenzia...), non specifico per
     * confronti anno su anno. Ignorato se columnBy è vuoto.
     */
    val showVariationPercent: Boolean = false,

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
    /** dimensioneId -> value_id (solo le dimensioni di groupBy, mai columnBy). */
    val groupKeys: Map<UUID, Long>,

    /**
     * nome metrica -> valore, SE columnBy è vuoto (comportamento storico).
     *
     * Se columnBy è valorizzato, la chiave diventa invece
     * "nomeMetrica|valoreColonna" (es. "Fatturato|2025", "Fatturato|2026"):
     * una entry per ogni combinazione metrica × valore distinto trovato in
     * columnBy per quella riga. valoreColonna è la label risolta se
     * disponibile, altrimenti l'id grezzo come stringa - la UI la separa
     * splittando su "|" per costruire le intestazioni di colonna.
     */
    val values: Map<String, BigDecimal>,

    /** dimensioneId -> etichetta, per le sole dimensioni di groupBy. Popolato solo se richiesto. */
    val labels: Map<UUID, String> = emptyMap(),

    /**
     * valore(i) distinti di columnBy presenti in questa riga, con
     * relativa label. Vuoto se columnBy non è usato. Serve alla UI per
     * sapere quali colonne dinamiche generare senza dover riparsare le
     * chiavi di "values".
     */
    val columnValues: Map<UUID, String> = emptyMap()
)

data class AggregateResult(
    val rows: List<AggregateRow>,
    val truncated: Boolean
)