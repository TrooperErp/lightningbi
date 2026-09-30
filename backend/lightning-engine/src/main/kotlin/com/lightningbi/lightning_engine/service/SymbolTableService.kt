package com.lightningbi.lightning_engine.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class SymbolTableService(
    private val jdbcTemplate: JdbcTemplate
) {

    /**
     * Crea la symbol table per una dimensione.
     *
     * Accetta il nome LOGICO (es. "Ordini", "CODICE_CLIENTE") e ricava da sé
     * l'identificatore fisico via Naming. In precedenza il nome veniva solo
     * validato, non normalizzato: qualsiasi nome con maiuscole - cioè la
     * quasi totalità dei nomi colonna che arrivano da SQL Server - faceva
     * fallire la creazione.
     */
    fun createSymbolTable(dimensioneNome: String) {
        val table = Naming.symbolTable(dimensioneNome)
        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS $table (
                value_id UInt32,
                value_string String
            ) ENGINE = MergeTree()
            ORDER BY (value_string)
            """.trimIndent()
        )
    }

    /** Vero se la symbol table della dimensione esiste già. */
    fun symbolTableExists(dimensioneNome: String): Boolean {
        val table = Naming.symbolTable(dimensioneNome)
        val n = jdbcTemplate.queryForObject(
            "SELECT count() FROM system.tables WHERE name = ?",
            Long::class.java,
            table
        )
        return (n ?: 0L) > 0L
    }

    /**
     * Crea la tabella ClickHouse di una tabella importata (schema a stella
     * nativo: Fatti o Dimensione).
     *
     * Riceve nomi già fisici della tabella (prodotti da
     * Naming.importedTable) e nomi LOGICI delle colonne, normalizzati qui
     * con Naming.column.
     *
     * @param colonneId colonne UInt32: chiavi di JOIN e attributi
     *   dimensione, tutti codificati come id di symbol table
     * @param colonneDecimali colonne Decimal(18,4): metriche (solo Fatti)
     * @param colonneOrdinamento colonne della ORDER BY; devono essere tra
     *   colonneId. Vuoto = si usa la prima colonna id.
     *
     * IF NOT EXISTS: se la tabella esiste già con colonne diverse NON viene
     * modificata (serve un ALTER esplicito).
     * Nessuna _partition_key: le tabelle importate si svuotano e si
     * ricaricano per intero (vedi LoaderService).
     */
    fun createImportedTable(
        tabellaFisica: String,
        colonneId: List<String>,
        colonneDecimali: List<String> = emptyList(),
        colonneOrdinamento: List<String> = emptyList()
    ): String {
        require(Regex("^[a-z][a-z0-9_]*$").matches(tabellaFisica)) {
            "Nome tabella non valido: '$tabellaFisica'"
        }
        require(colonneId.isNotEmpty()) { "Serve almeno una colonna id (chiave o dimensione) per $tabellaFisica" }

        val ids = colonneId.map { Naming.column(it) }
        val decimali = colonneDecimali.map { Naming.column(it) }
        val ordinamento = colonneOrdinamento.map { Naming.column(it) }.ifEmpty { listOf(ids.first()) }

        val duplicati = (ids + decimali).groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicati.isEmpty()) {
            "Colonne che collidono dopo la normalizzazione in $tabellaFisica: ${duplicati.joinToString(", ")}"
        }
        val fuori = ordinamento.filter { it !in ids }
        require(fuori.isEmpty()) {
            "Colonne di ordinamento non presenti tra le colonne id di $tabellaFisica: ${fuori.joinToString(", ")}"
        }

        val defs = buildList {
            ids.forEach { add("$it UInt32") }
            decimali.forEach { add("$it Decimal(18,4)") }
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

    /** Elimina una tabella. Usato per ripulire artefatti orfani dopo un rollback. */
    fun dropTable(tableName: String) {
        jdbcTemplate.execute("DROP TABLE IF EXISTS $tableName")
    }
}