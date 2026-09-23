package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.PivotView
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.util.UUID

@Repository
class PivotViewRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper
) : PivotViewRepository {

    /** Serializza una lista di UUID come array JSON di stringhe, per le colonne jsonb pivot_*_json. */
    private fun toJson(ids: List<UUID>): String = objectMapper.writeValueAsString(ids.map { it.toString() })

    private fun fromJson(json: String?): List<UUID> {
        if (json.isNullOrBlank()) return emptyList()
        val raw: List<String> = objectMapper.readValue(json, objectMapper.typeFactory.constructCollectionType(List::class.java, String::class.java))
        return raw.map { UUID.fromString(it) }
    }

    private val mapper = RowMapper { rs: ResultSet, _: Int ->
        PivotView(
            id = UUID.fromString(rs.getString("id")),
            areaId = UUID.fromString(rs.getString("area_id")),
            nome = rs.getString("nome"),
            pivotRows = fromJson(rs.getString("pivot_rows_json")),
            pivotColumns = fromJson(rs.getString("pivot_columns_json")),
            pivotValues = fromJson(rs.getString("pivot_values_json")),
            posizione = rs.getInt("posizione"),
            createdAt = rs.getTimestamp("created_at").toInstant()
        )
    }

    override fun findByArea(areaId: UUID): List<PivotView> =
        jdbcTemplate.query(
            "SELECT * FROM pg_lbi_pivot_view WHERE area_id = ? ORDER BY posizione, created_at",
            mapper, areaId
        )

    override fun findById(id: UUID): PivotView? =
        jdbcTemplate.query("SELECT * FROM pg_lbi_pivot_view WHERE id = ?", mapper, id).firstOrNull()

    override fun save(view: PivotView): PivotView {
        jdbcTemplate.update(
            """
        INSERT INTO pg_lbi_pivot_view
            (id, area_id, nome, pivot_rows_json, pivot_columns_json, pivot_values_json, posizione, created_at)
        VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?, ?)
        """.trimIndent(),
            view.id, view.areaId, view.nome,
            toJson(view.pivotRows), toJson(view.pivotColumns), toJson(view.pivotValues),
            view.posizione, java.sql.Timestamp.from(view.createdAt)
        )
        return view
    }

    override fun update(view: PivotView): PivotView {
        jdbcTemplate.update(
            """
        UPDATE pg_lbi_pivot_view
           SET nome = ?, pivot_rows_json = ?::jsonb, pivot_columns_json = ?::jsonb, pivot_values_json = ?::jsonb, posizione = ?
         WHERE id = ?
        """.trimIndent(),
            view.nome, toJson(view.pivotRows), toJson(view.pivotColumns), toJson(view.pivotValues),
            view.posizione, view.id
        )
        return view
    }

    override fun delete(id: UUID): Boolean =
        jdbcTemplate.update("DELETE FROM pg_lbi_pivot_view WHERE id = ?", id) > 0

    override fun nextPosizione(areaId: UUID): Int {
        val max = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(posizione), -1) FROM pg_lbi_pivot_view WHERE area_id = ?",
            Int::class.java, areaId
        ) ?: -1
        return max + 1
    }
}