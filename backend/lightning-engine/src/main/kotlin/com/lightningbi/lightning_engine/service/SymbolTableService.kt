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
}