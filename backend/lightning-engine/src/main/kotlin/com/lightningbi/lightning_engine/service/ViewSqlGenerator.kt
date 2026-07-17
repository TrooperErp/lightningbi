package com.lightningbi.lightning_engine.service

import org.springframework.stereotype.Service

@Service
class ViewSqlGenerator {

    fun generate(
        driverClass: String,
        viewName: String,
        schema: String?,
        mainTable: String,
        directMappings: List<Pair<String, String>>,     // colonna sorgente -> alias output
        lookups: List<LookupSql>,
        updatedAtColumn: String
    ): String {
        val qualifiedMain = if (schema != null) "$schema.$mainTable" else mainTable

        val selectCols = mutableListOf<String>()
        directMappings.forEach { (col, alias) -> selectCols.add("$mainTable.$col AS $alias") }
        lookups.forEach { lk ->
            selectCols.add("COALESCE(${lk.lookupAlias}.${lk.valueColumn}, '(non definito)') AS ${lk.outputAlias}")
        }
        selectCols.add("$mainTable.$updatedAtColumn AS lbi_updated_at")

        val joins = lookups.joinToString("\n") { lk ->
            "LEFT JOIN ${lk.lookupTable} ${lk.lookupAlias} ON $mainTable.${lk.joinColumnMain} = ${lk.lookupAlias}.${lk.joinColumnLookup}"
        }

        val createKeyword = when (driverClass) {
            "com.microsoft.sqlserver.jdbc.SQLServerDriver" -> "CREATE VIEW"
            "com.ibm.db2.jcc.DB2Driver" -> "CREATE VIEW"
            else -> "CREATE OR REPLACE VIEW"
        }

        return buildString {
            append("$createKeyword $viewName AS\n")
            append("SELECT\n    ")
            append(selectCols.joinToString(",\n    "))
            append("\nFROM $qualifiedMain\n")
            if (joins.isNotBlank()) append("$joins\n")
        }.trimEnd()
    }

    data class LookupSql(
        val lookupTable: String,
        val lookupAlias: String,
        val joinColumnMain: String,
        val joinColumnLookup: String,
        val valueColumn: String,
        val outputAlias: String
    )
}