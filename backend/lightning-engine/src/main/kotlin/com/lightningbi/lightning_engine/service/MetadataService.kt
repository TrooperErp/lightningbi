package com.lightningbi.lightning_engine.service

import org.springframework.stereotype.Service
import java.sql.Connection
import java.sql.DriverManager

data class TableInfo(val schema: String, val name: String)
data class ColumnInfo(val name: String, val typeName: String)
data class ForeignKeyInfo(val fkColumn: String, val pkTable: String, val pkColumn: String)

@Service
class MetadataService {

    fun connect(jdbcUrl: String, username: String, password: String, driverClass: String): Connection {
        Class.forName(driverClass)
        return DriverManager.getConnection(jdbcUrl, username, password)
    }

    fun listSchemas(conn: Connection): List<String> {
        val result = mutableListOf<String>()
        conn.metaData.schemas.use { rs ->
            while (rs.next()) {
                result.add(rs.getString("TABLE_SCHEM"))
            }
        }
        return result
    }

    fun listTables(conn: Connection, schema: String?): List<TableInfo> {
        val result = mutableListOf<TableInfo>()
        conn.metaData.getTables(null, schema, "%", arrayOf("TABLE", "VIEW")).use { rs ->
            while (rs.next()) {
                result.add(TableInfo(
                    schema = rs.getString("TABLE_SCHEM") ?: "",
                    name = rs.getString("TABLE_NAME")
                ))
            }
        }
        return result
    }

    fun listColumns(conn: Connection, schema: String?, table: String): List<ColumnInfo> {
        val result = mutableListOf<ColumnInfo>()
        conn.metaData.getColumns(null, schema, table, "%").use { rs ->
            while (rs.next()) {
                result.add(ColumnInfo(
                    name = rs.getString("COLUMN_NAME"),
                    typeName = rs.getString("TYPE_NAME")
                ))
            }
        }
        return result
    }

    fun getImportedForeignKeys(conn: Connection, schema: String?, table: String): List<ForeignKeyInfo> {
        val result = mutableListOf<ForeignKeyInfo>()
        conn.metaData.getImportedKeys(null, schema, table).use { rs ->
            while (rs.next()) {
                result.add(ForeignKeyInfo(
                    fkColumn = rs.getString("FKCOLUMN_NAME"),
                    pkTable = rs.getString("PKTABLE_NAME"),
                    pkColumn = rs.getString("PKCOLUMN_NAME")
                ))
            }
        }
        return result
    }

    fun viewExistsWithColumns(conn: Connection, schema: String?, viewName: String, expectedColumns: List<String>): Pair<Boolean, String?> {
        val actual = listColumns(conn, schema, viewName).map { it.name.lowercase() }.toSet()
        if (actual.isEmpty()) return false to "View non trovata: $viewName"
        val missing = expectedColumns.map { it.lowercase() }.filterNot { it in actual }
        return if (missing.isEmpty()) true to null
        else false to "Colonne mancanti nella view: ${missing.joinToString(", ")}"
    }
}