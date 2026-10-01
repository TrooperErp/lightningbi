package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.AreaCampo
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class AreaCampoRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : AreaCampoRepository {

    override fun findByArea(areaId: UUID): List<AreaCampo> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_area_campo WHERE area_id = ? ORDER BY imported_table_id, colonna",
            { rs, _ -> mapRow(rs) },
            areaId
        )

    override fun findByAreaAndTable(areaId: UUID, importedTableId: UUID): List<AreaCampo> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_area_campo WHERE area_id = ? AND imported_table_id = ? ORDER BY colonna",
            { rs, _ -> mapRow(rs) },
            areaId, importedTableId
        )

    override fun save(campo: AreaCampo): AreaCampo {
        jdbcTemplate.update(
            """
            INSERT INTO lbi_area_campo (area_id, imported_table_id, colonna, nome_campo, escluso)
            VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (area_id, imported_table_id, colonna) DO UPDATE SET
                nome_campo = EXCLUDED.nome_campo,
                escluso = EXCLUDED.escluso
            """.trimIndent(),
            campo.areaId, campo.importedTableId, campo.colonna, campo.nomeCampo, campo.escluso
        )
        return campo
    }

    override fun delete(areaId: UUID, importedTableId: UUID, colonna: String): Boolean =
        jdbcTemplate.update(
            "DELETE FROM lbi_area_campo WHERE area_id = ? AND imported_table_id = ? AND colonna = ?",
            areaId, importedTableId, colonna
        ) > 0

    override fun deleteByArea(areaId: UUID): Int =
        jdbcTemplate.update("DELETE FROM lbi_area_campo WHERE area_id = ?", areaId)

    private fun mapRow(rs: ResultSet): AreaCampo = AreaCampo(
        areaId = UUID.fromString(rs.getString("area_id")),
        importedTableId = UUID.fromString(rs.getString("imported_table_id")),
        colonna = rs.getString("colonna"),
        nomeCampo = rs.getString("nome_campo"),
        escluso = rs.getBoolean("escluso")
    )
}