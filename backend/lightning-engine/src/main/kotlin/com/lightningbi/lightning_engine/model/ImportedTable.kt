package com.lightningbi.lightning_engine.model

import java.util.UUID

/**
 * Ruolo di una tabella importata nello schema a stella di un dataset.
 * FATTI: tabella dei fatti. Il modello prevede N Fatti per dataset
 * (multi-fatto, come Qlik); StarQueryBuilder oggi ne gestisce uno solo
 * (si allarga in Fase E).
 * DIMENSIONE: tabella dimensione, collegata ai Fatti da una chiave condivisa.
 */
enum class RuoloTabella {
    FATTI,
    DIMENSIONE
}

/**
 * Una tabella importata da una sorgente esterna, mappata 1:1 su una tabella
 * fisica ClickHouse. È descritta interamente da dati (nome, colonne, chiave),
 * non da un type Kotlin dedicato: lo schema si scopre a runtime tramite il
 * connettore (ConnectionOrchestrator.listColumns), non si hardcoda in
 * compilazione.
 *
 * Una tabella importata appartiene alla CONNESSIONE da cui si legge, non a un
 * dataset: si importa una volta e si riusa in N dataset tramite il ponte
 * lbi_area_imported_table.
 *
 * Passo ponte (A.2 e Fase B): [areaId] e [sourceId] sono nullable. Il wizard
 * di oggi li scrive; la pagina "Tabelle importate" crea tabelle senza dataset,
 * quindi senza nessuno dei due. Spariscono nel blocco unico della Fase C.
 * [connectionId], [schemaOrigine] e [nomeOrigine] sono nullable solo in questo
 * passo: diventano obbligatorie nella stessa Fase C.
 *
 * @param nomeLogico nome leggibile scelto in fase di wizard (es. "Clienti")
 * @param tabellaFisica nome ClickHouse, prodotto da Naming (es. mssql_sem__clienti)
 * @param colonnaChiave nome della colonna usata per il JOIN con i Fatti,
 *   null se non ancora determinata (solo per ruolo DIMENSIONE)
 * @param connectionId connessione da cui si legge la tabella
 * @param schemaOrigine schema della view/tabella sulla sorgente (es. a_reporting)
 * @param nomeOrigine nome della view/tabella sulla sorgente (es. QLK_VISTACLIENTI)
 */
data class ImportedTable(
    val id: UUID,
    val areaId: UUID?,
    val nomeLogico: String,
    val tabellaFisica: String,
    val ruolo: RuoloTabella,
    val sourceId: UUID?,
    val colonnaChiave: String? = null,
    val connectionId: UUID? = null,
    val schemaOrigine: String? = null,
    val nomeOrigine: String? = null
)

/**
 * Una colonna di una ImportedTable, come scoperta dal connettore sulla view
 * sorgente. Puramente descrittiva: non genera proprietà tipizzate, resta un
 * dato consultabile a runtime.
 *
 * @param isChiave true se questa colonna è (parte del)la chiave di JOIN
 *   verso un'altra ImportedTable dello stesso dataset
 */
data class ImportedColumn(
    val id: UUID,
    val importedTableId: UUID,
    val nome: String,
    val tipo: String,
    val isChiave: Boolean = false,
    /**
     * Se il campo è DERIVATO da una data (calendario, come i campi derivati di
     * Qlik): nome della colonna data di origine. Una colonna derivata non esiste
     * sulla sorgente: la calcola la sincronizzazione.
     */
    val derivataDa: String? = null,
    /** Componente del calendario (anno, mese, giorno...); presente solo con [derivataDa]. */
    val componente: String? = null
) {
    init {
        require((derivataDa == null) == (componente == null)) {
            "derivataDa e componente vanno insieme (colonna '$nome')"
        }
    }

    val derivata: Boolean get() = derivataDa != null
}
