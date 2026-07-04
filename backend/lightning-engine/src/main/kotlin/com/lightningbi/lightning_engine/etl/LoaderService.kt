package com.lightningbi.lightning_engine.etl

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class LoaderService(
    private val jdbcTemplate: JdbcTemplate
) {
    private val validIdentifier = Regex("^[a-z0-9_]+$")
    private val validPartitionKey = Regex("^\\([0-9]+,\\s?[0-9]+\\)$")

    fun load(tabellaFisica: String, rows: List<Map<String, Any?>>, columns: List<String>) {
        require(validIdentifier.matches(tabellaFisica)) { "Invalid table name: $tabellaFisica" }
        columns.forEach { require(validIdentifier.matches(it)) { "Invalid column name: $it" } }
        if (rows.isEmpty()) return

        val partitions = rows.mapNotNull { it["_partition_key"]?.toString() }.toSet()
        partitions.forEach { p ->
            require(validPartitionKey.matches(p)) { "Invalid partition key: $p" }
            jdbcTemplate.execute("ALTER TABLE $tabellaFisica DROP PARTITION IF EXISTS $p")
        }

        val insertColumns = columns.filter { it != "_partition_key" }
        val placeholders = insertColumns.joinToString(",") { "?" }
        val sql = "INSERT INTO $tabellaFisica (${insertColumns.joinToString(",")}) VALUES ($placeholders)"
        val batchArgs: List<Array<Any?>> = rows.map { row -> insertColumns.map { row[it] }.toTypedArray() }
        jdbcTemplate.batchUpdate(sql, rows.map { row ->
            insertColumns.map { row[it] as Any }.toTypedArray()
        })
    }
}