package com.lightningbi.lightning_engine.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class SymbolTableService(
    private val jdbcTemplate: JdbcTemplate
) {
    private val validName = Regex("^[a-z0-9_]+$")

    fun createSymbolTable(dimensioneNome: String) {
        require(validName.matches(dimensioneNome)) { "Invalid dimension name: $dimensioneNome" }
        val table = "ch_lbi_symbol_$dimensioneNome"
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS $table (
                value_id UInt32,
                value_string String
            ) ENGINE = MergeTree()
            ORDER BY (value_string)
        """)
    }

    fun createAreaTable(
        tabellaFisica: String,
        colonneFiltri: List<String>,
        colonneSomme: List<String>
    ) {
        require(validName.matches(tabellaFisica)) { "Invalid table name: $tabellaFisica" }
        colonneFiltri.forEach { require(validName.matches(it)) { "Invalid column name: $it" } }
        colonneSomme.forEach { require(validName.matches(it)) { "Invalid column name: $it" } }

        val filtriDef = colonneFiltri.joinToString(",\n                ") { "$it UInt32" }
        val sommeDef = colonneSomme.joinToString(",\n                ") { "$it Decimal(18,4)" }
        val orderBy = colonneFiltri.joinToString(", ")

        jdbcTemplate.execute("""
        CREATE TABLE IF NOT EXISTS $tabellaFisica (
                $filtriDef,
                $sommeDef,
                _partition_key String
            ) ENGINE = MergeTree()
            ORDER BY ($orderBy)
    """)
    }
}