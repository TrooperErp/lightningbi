package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.AreaSource
import com.lightningbi.lightning_engine.model.SourceConfig
import com.lightningbi.lightning_engine.model.SourceStatus
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class AreaSourceRepositoryImpl(
    @Qualifier("postgresJdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper
) : AreaSourceRepository {

    override fun findByArea(areaId: UUID): List<AreaSource> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_area_source WHERE area_id = ?",
            { rs, _ -> mapRow(rs) },
            areaId
        )

    override fun findById(id: UUID): AreaSource? =
        jdbcTemplate.query(
            "SELECT * FROM lbi_area_source WHERE id = ?",
            { rs, _ -> mapRow(rs) },
            id
        ).firstOrNull()

    override fun save(source: AreaSource) {
        val configJson = objectMapper.writeValueAsString(source.config)
        jdbcTemplate.update(
            """
            INSERT INTO lbi_area_source (id, area_id, tipo_sorgente, config, status, error_detail, created_at)
            VALUES (?, ?, ?, ?::jsonb, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                tipo_sorgente = EXCLUDED.tipo_sorgente,
                config = EXCLUDED.config,
                status = EXCLUDED.status,
                error_detail = EXCLUDED.error_detail
            """,
            source.id,
            source.areaId,
            source.tipoSorgente,
            configJson,
            source.status.name,
            source.errorDetail,
            Timestamp.from(source.createdAt)
        )
    }

    override fun delete(id: UUID) {
        jdbcTemplate.update("DELETE FROM lbi_area_source WHERE id = ?", id)
    }

    private fun mapRow(rs: ResultSet): AreaSource {
        val configJson = rs.getString("config")
        val config = objectMapper.readValue(configJson, SourceConfig::class.java)
        return AreaSource(
            id = UUID.fromString(rs.getString("id")),
            areaId = UUID.fromString(rs.getString("area_id")),
            tipoSorgente = rs.getString("tipo_sorgente"),
            config = config,
            status = SourceStatus.valueOf(rs.getString("status")),
            errorDetail = rs.getString("error_detail"),
            createdAt = rs.getTimestamp("created_at").toInstant()
        )
    }
}