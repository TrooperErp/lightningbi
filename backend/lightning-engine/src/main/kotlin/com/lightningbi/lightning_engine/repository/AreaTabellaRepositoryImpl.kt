package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.AreaTabella
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class AreaTabellaRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : AreaTabellaRepository {

    override fun findByArea(areaId: UUID): List<AreaTabella> =
        jdbcTemplate.query(
            "SELECT id, area_id, imported_table_id, alias, disconnessa FROM lbi_area_imported_table WHERE area_id = ? ORDER BY ordine, lower(alias)",
            { rs, _ -> mapRow(rs) },
            areaId
        )

    override fun findById(id: UUID): AreaTabella? =
        jdbcTemplate.query(
            "SELECT id, area_id, imported_table_id, alias, disconnessa FROM lbi_area_imported_table WHERE id = ?",
            { rs, _ -> mapRow(rs) },
            id
        ).firstOrNull()

    override fun save(tabella: AreaTabella): AreaTabella {
        jdbcTemplate.update(
            """INSERT INTO lbi_area_imported_table (id, area_id, imported_table_id, alias, disconnessa, ordine)
               VALUES (?, ?, ?, ?, ?,
                       (SELECT COALESCE(MAX(ordine), 0) + 1 FROM lbi_area_imported_table WHERE area_id = ?))""",
            tabella.id, tabella.areaId, tabella.importedTableId, tabella.alias, tabella.disconnessa, tabella.areaId
        )
        return tabella
    }

    override fun updateAlias(id: UUID, alias: String) {
        jdbcTemplate.update("UPDATE lbi_area_imported_table SET alias = ? WHERE id = ?", alias, id)
    }

    override fun updateDisconnessa(id: UUID, disconnessa: Boolean) {
        jdbcTemplate.update("UPDATE lbi_area_imported_table SET disconnessa = ? WHERE id = ?", disconnessa, id)
    }

    override fun delete(id: UUID): Boolean =
        jdbcTemplate.update("DELETE FROM lbi_area_imported_table WHERE id = ?", id) > 0

    override fun deleteByArea(areaId: UUID): Int =
        jdbcTemplate.update("DELETE FROM lbi_area_imported_table WHERE area_id = ?", areaId)

    private fun mapRow(rs: ResultSet): AreaTabella = AreaTabella(
        id = UUID.fromString(rs.getString("id")),
        areaId = UUID.fromString(rs.getString("area_id")),
        importedTableId = UUID.fromString(rs.getString("imported_table_id")),
        alias = rs.getString("alias"),
        disconnessa = rs.getBoolean("disconnessa")
    )
}