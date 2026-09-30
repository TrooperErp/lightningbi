package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.SourceConnection
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Repository
class SourceConnectionRepositoryImpl(
    @Qualifier("postgresJdbcTemplate")
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper
) : SourceConnectionRepository {

    override fun findAll(): List<SourceConnection> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_connection ORDER BY nome",
            { rs, _ -> mapRow(rs) }
        )

    override fun findById(id: UUID): SourceConnection? =
        jdbcTemplate.query(
            "SELECT * FROM lbi_connection WHERE id = ?",
            { rs, _ -> mapRow(rs) },
            id
        ).firstOrNull()

    override fun findByNome(nome: String): SourceConnection? =
        jdbcTemplate.query(
            "SELECT * FROM lbi_connection WHERE nome = ?",
            { rs, _ -> mapRow(rs) },
            nome
        ).firstOrNull()

    override fun save(connection: SourceConnection) {
        jdbcTemplate.update(
            """
            INSERT INTO lbi_connection (id, nome, tipo, parametri, segreti, created_at)
            VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?)
            ON CONFLICT (id) DO UPDATE SET
                nome = EXCLUDED.nome,
                tipo = EXCLUDED.tipo,
                parametri = EXCLUDED.parametri,
                segreti = EXCLUDED.segreti
            """.trimIndent(),
            connection.id,
            connection.nome,
            connection.tipo,
            objectMapper.writeValueAsString(connection.parametri),
            objectMapper.writeValueAsString(connection.segreti),
            Timestamp.from(connection.createdAt)
        )
    }

    override fun delete(id: UUID) {
        jdbcTemplate.update("DELETE FROM lbi_connection WHERE id = ?", id)
    }

    private fun mapRow(rs: ResultSet): SourceConnection = SourceConnection(
        id = UUID.fromString(rs.getString("id")),
        nome = rs.getString("nome"),
        tipo = rs.getString("tipo"),
        parametri = readMap(rs.getString("parametri")),
        segreti = readMap(rs.getString("segreti")),
        createdAt = rs.getTimestamp("created_at").toInstant()
    )

    private fun readMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        val raw = objectMapper.readValue(json, Map::class.java)
        return raw.entries
            .mapNotNull { (k, v) -> if (k == null || v == null) null else k.toString() to v.toString() }
            .toMap()
    }
}