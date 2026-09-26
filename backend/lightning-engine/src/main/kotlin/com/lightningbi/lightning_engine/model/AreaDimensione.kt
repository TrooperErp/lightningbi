package com.lightningbi.lightning_engine.model

import java.util.UUID

data class Area(
    val id: UUID,
    val nome: String,
    val tabellaFisica: String
)

data class Dimensione(
    val id: UUID,
    val nome: String,
    val tipo: String,
    val conformata: Boolean,
    val tabellaDimFisica: String?,
    val colonnaChiave: String?
)

data class AreaDimensione(
    val areaId: UUID,
    val dimensioneId: UUID,
    val colonnaFisica: String,
    val obbligatoria: Boolean,
    val cardinalitaStimata: Long?,
    /**
     * true se il valore della colonna è già un numero significativo di
     * per sé (es. mese_numero, trimestre_numero), non una categoria da
     * tradurre tramite symbol table. TransformService scrive il valore
     * grezzo diretto invece di passare da SymbolLookupService.
     * getOrCreateIds, che per una colonna già numerica produrrebbe uno
     * shift silenzioso (id sequenziale in ordine di prima apparizione,
     * scollegato dal valore reale).
     *
     * Default false: ogni dimensione resta categorica come oggi finché
     * non marcata esplicitamente al momento del collegamento.
     */
    val valoreGrezzo: Boolean = false,
    /**
     * Tabella importata (ImportedTable) in cui vive fisicamente
     * colonnaFisica, nel modello multi-tabella (TBS).
     *
     * Null per le Aree legacy a view singola pre-denormalizzata (dove
     * colonnaFisica vive direttamente sulla tabella fatti dell'Area, senza
     * un JOIN separato): in quel caso FilterCardsUi non può raggruppare
     * per entità di provenienza e AggregateService non deve fare nessun
     * JOIN per questa dimensione.
     *
     * Valorizzato per le Aree costruite con lo schema a stella nativo: la
     * colonna vive sulla ImportedTable di ruolo DIMENSIONE referenziata,
     * e AggregateService fa JOIN con i Fatti sulla chiave condivisa
     * (ImportedTable.colonnaChiave) quando questa dimensione è richiesta.
     */
    val importedTableId: UUID? = null
)

/** Come aggregare la colonna. COUNT non richiede che colonnaFisica sia valorizzata. */
enum class TipoAggregazione {
    SUM, AVG, COUNT, COUNT_DISTINCT, MIN, MAX
}

/**
 * Natura della metrica.
 *
 * ESPRESSIONE_CALCOLATA non è ancora implementata da nessun service: il
 * campo esiste per non dover migrare la tabella una seconda volta quando
 * servirà (es. percentuale di scarto = SUM(scartati)/SUM(prodotti), prezzo
 * medio ponderato = SUM(importo)/SUM(quantita) - casi dove nessuna singola
 * aggregazione su una colonna basta, perché il calcolo è un rapporto tra
 * due aggregazioni distinte).
 */
enum class TipoMetrica {
    AGGREGAZIONE_COLONNA,
    ESPRESSIONE_CALCOLATA
}

data class AreaMetrica(
    val id: UUID,
    val areaId: UUID,
    val nome: String,
    /**
     * Null solo per COUNT(*): un conteggio di righe non ha bisogno di una
     * colonna specifica. Per ogni altra aggregazione è obbligatoria - il
     * controllo è a carico di chi costruisce l'oggetto (RegistryService),
     * non del tipo, perché la regola dipende dal valore di tipoAggregazione.
     */
    val colonnaFisica: String?,
    val tipoAggregazione: TipoAggregazione,
    val tipoMetrica: TipoMetrica = TipoMetrica.AGGREGAZIONE_COLONNA,
    /** Usata solo da ESPRESSIONE_CALCOLATA. Nessun service la legge oggi. */
    val espressione: String? = null
)