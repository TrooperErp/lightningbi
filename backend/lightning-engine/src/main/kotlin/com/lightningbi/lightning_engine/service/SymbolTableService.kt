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
     * Crea la tabella dei fatti di un'area.
     *
     * Riceve i nomi LOGICI delle colonne e li normalizza internamente:
     * chi chiama non deve preoccuparsi del case. Attenzione: la view
     * generata da ViewSqlGenerator deve usare gli STESSI alias, altrimenti
     * l'ETL non trova le colonne. Usare Naming.column() anche lì.
     */
    fun createAreaTable(
        nomeArea: String,
        colonneFiltri: List<String>,
        colonneSomme: List<String>
    ): String {
        require(colonneFiltri.isNotEmpty()) { "Serve almeno una colonna filtro per la ORDER BY" }

        val tabellaFisica = Naming.areaTable(nomeArea)
        val filtri = colonneFiltri.map { Naming.column(it) }
        val somme = colonneSomme.map { Naming.column(it) }

        val duplicati = (filtri + somme).groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(duplicati.isEmpty()) {
            "Colonne che collidono dopo la normalizzazione: ${duplicati.joinToString(", ")}"
        }

        val defs = buildList {
            filtri.forEach { add("$it UInt32") }
            somme.forEach { add("$it Decimal(18,4)") }
            add("_partition_key String")
        }.joinToString(",\n                ")

        jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS $tabellaFisica (
                $defs
            ) ENGINE = MergeTree()
            ORDER BY (${filtri.joinToString(", ")})
            """.trimIndent()
        )
        return tabellaFisica
    }

    /** Elimina una tabella. Usato per ripulire artefatti orfani dopo un rollback. */
    fun dropTable(tableName: String) {
        jdbcTemplate.execute("DROP TABLE IF EXISTS $tableName")
    }
}