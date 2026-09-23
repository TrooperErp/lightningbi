package com.lightningbi.lightning_engine.repository

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lightningbi.lightning_engine.model.UserPivotState
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Legge/scrive lo stato utente su pg_lbi_user_pivot_state.
 * active_view_id è una colonna propria (nullable: nessuna vista attiva
 * ancora scelta); pivot_state (jsonb) contiene ora solo le selections.
 *
 * Il JSON viene letto in modo tollerante: le righe salvate col vecchio
 * formato contengono anche pivotRows/pivotColumns/pivotValues. Questi
 * campi vengono ignorati da find() e letti solo da findLegacyStructure(),
 * per il recupero una-tantum in "Vista 1". Ogni save() riscrive il JSON
 * nel formato nuovo (solo selections), eliminando il residuo legacy.
 */
@Repository
class UserPivotStateRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper
) : UserPivotStateRepository {

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class StateJson(
        val selections: Map<String, List<Long>> = emptyMap(),
        val pivotRows: List<String>? = null,
        val pivotColumns: List<String>? = null,
        val pivotValues: List<String>? = null
    )

    private data class Row(val json: String, val activeViewId: UUID?, val updatedAt: Instant)

    private fun readRow(userId: UUID, areaId: UUID): Row? =
        jdbcTemplate.query(
            "SELECT pivot_state, active_view_id, updated_at FROM pg_lbi_user_pivot_state WHERE user_id = ? AND area_id = ?",
            { rs, _ ->
                Row(
                    json = rs.getString("pivot_state"),
                    activeViewId = rs.getString("active_view_id")?.let { UUID.fromString(it) },
                    updatedAt = rs.getTimestamp("updated_at").toInstant()
                )
            },
            userId, areaId
        ).firstOrNull()

    override fun find(userId: UUID, areaId: UUID): UserPivotState? {
        val row = readRow(userId, areaId) ?: return null
        val parsed = objectMapper.readValue(row.json, StateJson::class.java)
        return UserPivotState(
            userId = userId,
            areaId = areaId,
            activeViewId = row.activeViewId,
            selections = parsed.selections.mapKeys { UUID.fromString(it.key) }.mapValues { it.value.toSet() },
            updatedAt = row.updatedAt
        )
    }

    override fun findLegacyStructure(userId: UUID, areaId: UUID): Triple<List<UUID>, List<UUID>, List<UUID>>? {
        val row = readRow(userId, areaId) ?: return null
        val parsed = objectMapper.readValue(row.json, StateJson::class.java)
        if (parsed.pivotRows == null && parsed.pivotColumns == null && parsed.pivotValues == null) return null
        return Triple(
            parsed.pivotRows.orEmpty().map { UUID.fromString(it) },
            parsed.pivotColumns.orEmpty().map { UUID.fromString(it) },
            parsed.pivotValues.orEmpty().map { UUID.fromString(it) }
        )
    }

    override fun save(state: UserPivotState) {
        val json = objectMapper.writeValueAsString(
            mapOf("selections" to state.selections.mapKeys { it.key.toString() }.mapValues { it.value.toList() })
        )
        jdbcTemplate.update(
            """
                INSERT INTO pg_lbi_user_pivot_state (user_id, area_id, pivot_state, active_view_id, updated_at)
                VALUES (?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (user_id, area_id)
                DO UPDATE SET pivot_state = EXCLUDED.pivot_state, active_view_id = EXCLUDED.active_view_id, updated_at = EXCLUDED.updated_at
                """,
            state.userId, state.areaId, json, state.activeViewId, java.sql.Timestamp.from(Instant.now())
        )
    }

    override fun delete(userId: UUID, areaId: UUID) {
        jdbcTemplate.update(
            "DELETE FROM pg_lbi_user_pivot_state WHERE user_id = ? AND area_id = ?",
            userId, areaId
        )
    }
}