package com.lightningbi.lightning_engine.model

import java.util.UUID

/**
 * Una OCCORRENZA di una tabella importata dentro un dataset.
 *
 * Come in Qlik, la stessa tabella si può caricare più volte nello stesso
 * modello (es. "Agenti" e "Agenti da documenti"): ogni caricamento è una
 * tabella a sé, con i propri campi. Campi, dimensioni e metriche del dataset
 * puntano quindi all'occorrenza e non alla tabella importata, che resta una
 * sola e si sincronizza una volta.
 *
 * Persistita nella tabella ponte lbi_area_imported_table (migrazione 030).
 *
 * @param id identificatore dell'occorrenza
 * @param areaId dataset a cui appartiene
 * @param importedTableId tabella importata che l'occorrenza legge
 * @param alias nome dell'occorrenza nel dataset, unico per dataset (non
 *   distingue maiuscole): la prima occorrenza di una tabella lascia i nomi dei
 *   campi com'è, dalla seconda i campi si qualificano con l'alias
 */
data class AreaTabella(
    val id: UUID,
    val areaId: UUID,
    val importedTableId: UUID,
    val alias: String,
    /** Disconnessa logicamente (come Qlik): le selezioni non entrano né escono da questa tabella. */
    val disconnessa: Boolean = false
) {
    init {
        require(alias.isNotBlank()) { "L'alias della tabella non può essere vuoto" }
        require(alias == alias.trim()) { "L'alias '$alias' ha spazi ai bordi" }
        require(alias.length <= MAX_ALIAS) {
            "L'alias '$alias' supera $MAX_ALIAS caratteri"
        }
    }

    companion object {
        /** Lunghezza della colonna lbi_area_imported_table.alias. */
        const val MAX_ALIAS = 100
    }
}