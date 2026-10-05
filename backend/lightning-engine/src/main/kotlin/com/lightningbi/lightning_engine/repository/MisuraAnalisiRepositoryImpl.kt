package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.MisuraAnalisi
import com.lightningbi.lightning_engine.model.TipoAggregazione
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

@Repository
class MisuraAnalisiRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : MisuraAnalisiRepository {

    override fun findByView(pivotViewId: UUID): List<MisuraAnalisi> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_pivot_view_misura WHERE pivot_view_id = ? ORDER BY posizione, nome",
            { rs, _ -> mapRow(rs) },
            pivotViewId
        )

    override fun findByIds(ids: Collection<UUID>): List<MisuraAnalisi> {
        if (ids.isEmpty()) return emptyList()
        val segnaposto = ids.joinToString(",") { "?" }
        return jdbcTemplate.query(
            "SELECT * FROM lbi_pivot_view_misura WHERE id IN ($segnaposto)",
            { rs, _ -> mapRow(rs) },
            *ids.toTypedArray()
        )
    }

    override fun save(misura: MisuraAnalisi): MisuraAnalisi {
        jdbcTemplate.update(
            """INSERT INTO lbi_pivot_view_misura
               (id, pivot_view_id, nome, tipo_aggregazione, area_tabella_id, colonna_fisica, posizione)
               VALUES (?, ?, ?, ?, ?, ?,
                       (SELECT COALESCE(MAX(posizione), 0) + 1 FROM lbi_pivot_view_misura WHERE pivot_view_id = ?))""",
            misura.id, misura.pivotViewId, misura.nome, misura.tipo.name,
            misura.areaTabellaId, misura.colonna, misura.pivotViewId
        )
        return misura
    }

    override fun update(misura: MisuraAnalisi) {
        jdbcTemplate.update(
            """UPDATE lbi_pivot_view_misura
               SET nome = ?, tipo_aggregazione = ?, area_tabella_id = ?, colonna_fisica = ?
               WHERE id = ?""",
            misura.nome, misura.tipo.name, misura.areaTabellaId, misura.colonna, misura.id
        )
    }

    override fun delete(id: UUID): Boolean =
        jdbcTemplate.update("DELETE FROM lbi_pivot_view_misura WHERE id = ?", id) > 0

    private fun mapRow(rs: ResultSet): MisuraAnalisi = MisuraAnalisi(
        id = UUID.fromString(rs.getString("id")),
        pivotViewId = UUID.fromString(rs.getString("pivot_view_id")),
        nome = rs.getString("nome"),
        tipo = TipoAggregazione.valueOf(rs.getString("tipo_aggregazione")),
        areaTabellaId = rs.getString("area_tabella_id")?.let { UUID.fromString(it) },
        colonna = rs.getString("colonna_fisica"),
        posizione = rs.getInt("posizione")
    )
}