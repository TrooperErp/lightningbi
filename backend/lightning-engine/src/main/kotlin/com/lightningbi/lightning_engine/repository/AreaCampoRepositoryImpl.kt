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
            """SELECT c.*
               FROM lbi_area_campo c
               JOIN lbi_area_imported_table t ON t.id = c.area_tabella_id
               WHERE t.area_id = ?
               ORDER BY lower(t.alias), c.colonna""",
            { rs, _ -> mapRow(rs) },
            areaId
        )

    override fun findByAreaTabella(areaTabellaId: UUID): List<AreaCampo> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_area_campo WHERE area_tabella_id = ? ORDER BY colonna",
            { rs, _ -> mapRow(rs) },
            areaTabellaId
        )

    override fun save(campo: AreaCampo): AreaCampo {
        jdbcTemplate.update(
            """
            INSERT INTO lbi_area_campo (area_tabella_id, colonna, nome_campo, escluso)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (area_tabella_id, colonna) DO UPDATE SET
                nome_campo = EXCLUDED.nome_campo,
                escluso = EXCLUDED.escluso
            """.trimIndent(),
            campo.areaTabellaId, campo.colonna, campo.nomeCampo, campo.escluso
        )
        return campo
    }

    override fun delete(areaTabellaId: UUID, colonna: String): Boolean =
        jdbcTemplate.update(
            "DELETE FROM lbi_area_campo WHERE area_tabella_id = ? AND colonna = ?",
            areaTabellaId, colonna
        ) > 0

    override fun deleteByArea(areaId: UUID): Int =
        jdbcTemplate.update(
            """DELETE FROM lbi_area_campo
               WHERE area_tabella_id IN (SELECT id FROM lbi_area_imported_table WHERE area_id = ?)""",
            areaId
        )

    private fun mapRow(rs: ResultSet): AreaCampo = AreaCampo(
        areaTabellaId = UUID.fromString(rs.getString("area_tabella_id")),
        colonna = rs.getString("colonna"),
        nomeCampo = rs.getString("nome_campo"),
        escluso = rs.getBoolean("escluso")
    )
}