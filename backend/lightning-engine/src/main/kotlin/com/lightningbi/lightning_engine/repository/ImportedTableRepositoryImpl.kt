package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.ImportedColumn
import com.lightningbi.lightning_engine.model.ImportedTable
import com.lightningbi.lightning_engine.model.RuoloTabella
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class ImportedTableRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : ImportedTableRepository {

    override fun findByArea(areaId: UUID): List<ImportedTable> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_imported_table WHERE area_id = ? ORDER BY nome_logico",
            { rs, _ -> mapTable(rs) },
            areaId
        )

    override fun findById(id: UUID): ImportedTable? =
        jdbcTemplate.query(
            "SELECT * FROM lbi_imported_table WHERE id = ?",
            { rs, _ -> mapTable(rs) },
            id
        ).firstOrNull()

    override fun save(table: ImportedTable): ImportedTable {
        jdbcTemplate.update(
            """INSERT INTO lbi_imported_table
               (id, area_id, nome_logico, tabella_fisica, ruolo, source_id, colonna_chiave,
                connection_id, schema_origine, nome_origine)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            table.id, table.areaId, table.nomeLogico, table.tabellaFisica,
            table.ruolo.name, table.sourceId, table.colonnaChiave,
            table.connectionId, table.schemaOrigine, table.nomeOrigine
        )
        return table
    }

    override fun updateColonnaChiave(id: UUID, colonnaChiave: String) {
        jdbcTemplate.update(
            "UPDATE lbi_imported_table SET colonna_chiave = ? WHERE id = ?",
            colonnaChiave, id
        )
    }

    override fun deleteByArea(areaId: UUID): Int =
        jdbcTemplate.update("DELETE FROM lbi_imported_table WHERE area_id = ?", areaId)

    override fun findColumnsByTable(importedTableId: UUID): List<ImportedColumn> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_imported_column WHERE imported_table_id = ? ORDER BY nome",
            { rs, _ ->
                ImportedColumn(
                    UUID.fromString(rs.getString("id")),
                    UUID.fromString(rs.getString("imported_table_id")),
                    rs.getString("nome"),
                    rs.getString("tipo"),
                    rs.getBoolean("is_chiave")
                )
            },
            importedTableId
        )

    override fun saveColumns(columns: List<ImportedColumn>) {
        if (columns.isEmpty()) return
        jdbcTemplate.batchUpdate(
            """INSERT INTO lbi_imported_column
               (id, imported_table_id, nome, tipo, is_chiave)
               VALUES (?, ?, ?, ?, ?)""",
            columns,
            columns.size
        ) { ps, c ->
            ps.setObject(1, c.id)
            ps.setObject(2, c.importedTableId)
            ps.setString(3, c.nome)
            ps.setString(4, c.tipo)
            ps.setBoolean(5, c.isChiave)
        }
    }

    override fun deleteColumnsByTable(importedTableId: UUID): Int =
        jdbcTemplate.update("DELETE FROM lbi_imported_column WHERE imported_table_id = ?", importedTableId)

    // ---- Ponte dataset <-> tabelle importate (lbi_area_imported_table) ----

    override fun linkToArea(areaId: UUID, importedTableId: UUID) {
        jdbcTemplate.update(
            """INSERT INTO lbi_area_imported_table (area_id, imported_table_id)
               VALUES (?, ?)
               ON CONFLICT (area_id, imported_table_id) DO NOTHING""",
            areaId, importedTableId
        )
    }

    override fun unlinkArea(areaId: UUID): Int =
        jdbcTemplate.update("DELETE FROM lbi_area_imported_table WHERE area_id = ?", areaId)

    override fun findLinkedToArea(areaId: UUID): List<ImportedTable> =
        jdbcTemplate.query(
            """SELECT t.*
               FROM lbi_imported_table t
               JOIN lbi_area_imported_table l ON l.imported_table_id = t.id
               WHERE l.area_id = ?
               ORDER BY t.nome_logico""",
            { rs, _ -> mapTable(rs) },
            areaId
        )

    override fun findAreaIdsUsing(importedTableId: UUID): List<UUID> =
        jdbcTemplate.query(
            "SELECT area_id FROM lbi_area_imported_table WHERE imported_table_id = ?",
            { rs, _ -> UUID.fromString(rs.getString("area_id")) },
            importedTableId
        )

    override fun findTabelleFisiche(): Set<String> =
        jdbcTemplate.queryForList("SELECT tabella_fisica FROM lbi_imported_table", String::class.java)
            .filterNotNull()
            .toSet()

    override fun findAll(): List<ImportedTable> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_imported_table ORDER BY nome_logico",
            { rs, _ -> mapTable(rs) }
        )

    override fun deleteById(id: UUID): Boolean =
        jdbcTemplate.update("DELETE FROM lbi_imported_table WHERE id = ?", id) > 0

    private fun mapTable(rs: java.sql.ResultSet): ImportedTable = ImportedTable(
        UUID.fromString(rs.getString("id")),
        rs.getString("area_id")?.let { UUID.fromString(it) },
        rs.getString("nome_logico"),
        rs.getString("tabella_fisica"),
        RuoloTabella.valueOf(rs.getString("ruolo")),
        rs.getString("source_id")?.let { UUID.fromString(it) },
        rs.getString("colonna_chiave"),
        rs.getString("connection_id")?.let { UUID.fromString(it) },
        rs.getString("schema_origine"),
        rs.getString("nome_origine")
    )
}