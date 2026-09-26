package com.lightningbi.lightning_engine.model

import java.util.UUID

/**
 * Ruolo di una tabella importata nello schema a stella dell'Area.
 * FATTI: tabella dei fatti (una sola per Area, coincide con Area.tabellaFisica).
 * DIMENSIONE: tabella dimensione, collegata ai Fatti da una chiave condivisa.
 */
enum class RuoloTabella {
    FATTI,
    DIMENSIONE
}

/**
 * Una tabella importata da una sorgente esterna, mappata 1:1 su una tabella
 * fisica ClickHouse. Sostituisce l'idea di "una classe Kotlin per tabella":
 * qui la tabella è descritta interamente da dati (nome, colonne, chiave),
 * non da un type Kotlin dedicato. Lo schema si scopre a runtime via
 * MetadataService, non si hardcoda in compilazione.
 *
 * @param nomeLogico nome leggibile scelto in fase di wizard (es. "Clienti")
 * @param tabellaFisica nome ClickHouse, prodotto da Naming (es. mssql_sem__clienti)
 * @param colonnaChiave nome della colonna usata per il JOIN con i Fatti,
 *   null se non ancora determinata (solo per ruolo DIMENSIONE)
 */
data class ImportedTable(
    val id: UUID,
    val areaId: UUID,
    val nomeLogico: String,
    val tabellaFisica: String,
    val ruolo: RuoloTabella,
    val sourceId: UUID,
    val colonnaChiave: String? = null
)

/**
 * Una colonna di una ImportedTable, come scoperta da
 * MetadataService.listColumns sulla view sorgente. Puramente descrittiva:
 * non genera proprietà tipizzate, resta un dato consultabile a runtime.
 *
 * @param isChiave true se questa colonna è (parte del)la chiave di JOIN
 *   verso un'altra ImportedTable della stessa Area
 */
data class ImportedColumn(
    val id: UUID,
    val importedTableId: UUID,
    val nome: String,
    val tipo: String,
    val isChiave: Boolean = false
)