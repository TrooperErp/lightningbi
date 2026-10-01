package com.lightningbi.lightning_engine.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class SymbolTableService(
    private val jdbcTemplate: JdbcTemplate
) {

    /**
     * Crea la symbol table di un CAMPO, stile Qlik: una per ogni nome di
     * colonna fisica, condivisa da tutte le tabelle che hanno una colonna con
     * quel nome (è ciò che rende possibile l'associazione per nome).
     *
     * Ogni valore distinto ha un id e due forme, come i valori doppi di Qlik:
     * il testo ([value_string]) e, se il valore è un numero, il numero
     * ([value_number], null per i valori non numerici). Il numero serve
     * all'ordinamento numerico del campo.
     *
     * Accetta il nome della colonna così come arriva dalla sorgente e ricava
     * da sé l'identificatore fisico via Naming.
     *
     * Una symbol table già esistente, creata prima di value_number, viene
     * aggiornata con la colonna mancante: i suoi valori restano e il numero
     * resta null finché il valore non viene riletto.
     */
    fun createSymbolTable(colonna: String) {
        val table = Naming.symbolTable(colonna)
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS $table (
                value_id UInt32,
                value_string String,
                value_number Nullable(Decimal(38, 6))
            ) ENGINE = MergeTree()
            ORDER BY (value_string)
            """.trimIndent()
        )
        jdbcTemplate.execute("ALTER TABLE $table ADD COLUMN IF NOT EXISTS value_number Nullable(Decimal(38, 6))")
    }

    /**
     * Crea la tabella ClickHouse di una tabella importata (Fatti o
     * Dimensione). La forma di ogni colonna non dipende dal dataset: lo
     * stesso identico schema serve a tutti i dataset che la usano.
     *
     * Riceve il nome fisico della tabella (prodotto da Naming.importedTable)
     * e i nomi delle colonne come arrivano dalla sorgente, normalizzati qui
     * con Naming.column.
     *
     * @param colonneId colonne UInt32: chiavi di JOIN e tutti i campi, ogni
     *   valore codificato come id di symbol table
     * @param colonneNumeriche colonne di tipo numerico (non chiave): oltre
     *   all'id hanno una COPIA NUMERICA Nullable(Decimal(38,6)) chiamata
     *   `<colonna>__n` (Naming.numericColumn), così lo stesso campo si può
     *   usare come dimensione o come metrica. Null = valore mancante: le
     *   aggregazioni lo ignorano (non conta come zero). Devono essere tra
     *   colonneId.
     * @param colonneOrdinamento colonne della ORDER BY; devono essere tra
     *   colonneId. Vuoto = si usa la prima colonna id.
     *
     * IF NOT EXISTS: se la tabella esiste già con colonne diverse NON viene
     * modificata (serve un ALTER esplicito).
     * Nessuna _partition_key: le tabelle importate si svuotano e si
     * ricaricano per intero, oppure si aggiornano per unità (vedi
     * LoaderService).
     */
    fun createImportedTable(
        tabellaFisica: String,
        colonneId: List<String>,
        colonneNumeriche: List<String> = emptyList(),
        colonneOrdinamento: List<String> = emptyList()
    ): String {
        require(Regex("^[a-z][a-z0-9_]*$").matches(tabellaFisica)) {
            "Nome tabella non valido: '$tabellaFisica'"
        }
        require(colonneId.isNotEmpty()) { "Serve almeno una colonna id (chiave o campo) per $tabellaFisica" }

        val ids = colonneId.map { Naming.column(it) }
        val numeriche = colonneNumeriche.map { Naming.column(it) }.distinct()
        val ordinamento = colonneOrdinamento.map { Naming.column(it) }.ifEmpty { listOf(ids.first()) }

        val duplicati = ids.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicati.isEmpty()) {
            "Colonne che collidono dopo la normalizzazione in $tabellaFisica: ${duplicati.joinToString(", ")}"
        }
        val numericheFuori = numeriche.filter { it !in ids }
        require(numericheFuori.isEmpty()) {
            "Colonne numeriche non presenti tra le colonne id di $tabellaFisica: ${numericheFuori.joinToString(", ")}"
        }
        val fuori = ordinamento.filter { it !in ids }
        require(fuori.isEmpty()) {
            "Colonne di ordinamento non presenti tra le colonne id di $tabellaFisica: ${fuori.joinToString(", ")}"
        }

        val defs = buildList {
            ids.forEach { add("$it UInt32") }
            numeriche.forEach { add("${Naming.numericColumn(it)} Nullable(Decimal(38, 6))") }
        }.joinToString(",\n                ")

        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS $tabellaFisica (
                $defs
            ) ENGINE = MergeTree()
            ORDER BY (${ordinamento.joinToString(", ")})
            """.trimIndent()
        )
        return tabellaFisica
    }

    /**
     * Nomi delle colonne di una tabella ClickHouse, o insieme vuoto se la
     * tabella non esiste. Serve a capire se la tabella ha lo schema atteso
     * prima di una sincronizzazione incrementale.
     */
    fun colonneDi(tabellaFisica: String): Set<String> {
        require(Regex("^[a-z][a-z0-9_]*$").matches(tabellaFisica)) { "Nome tabella non valido: '$tabellaFisica'" }
        return jdbcTemplate.queryForList(
            "SELECT name FROM system.columns WHERE database = currentDatabase() AND table = ?",
            String::class.java,
            tabellaFisica
        ).filterNotNull().toSet()
    }

    /** Elimina una tabella. Usato per ripulire artefatti orfani dopo un rollback. */
    fun dropTable(tableName: String) {
        jdbcTemplate.execute("DROP TABLE IF EXISTS $tableName")
    }
}