package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.UserPivotState
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

/**
 * Legge/scrive lo stato pivot come jsonb su pg_lbi_user_pivot_state.
 * Il JSON contiene liste di UUID come stringhe e le selections con
 * chiave UUID (dimensione) -> lista di Long (id valore, non Set: JSON
 * non ha un tipo Set nativo, la conversione a Set avviene in lettura).
 */
@Repository
class UserPivotStateRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper
) : UserPivotStateRepository {

    private data class PivotStateJson(
        val pivotRows: List<String>,
        val pivotColumns: List<String>,
        val pivotValues: List<String>,
        val selections: Map<String, List<Long>>
    )

    override fun find(userId: UUID, areaId: UUID): UserPivotState? {
        val rows = jdbcTemplate.query(
            "SELECT pivot_state, updated_at FROM pg_lbi_user_pivot_state WHERE user_id = ? AND area_id = ?",
            { rs, _ ->
                val json = rs.getString("pivot_state")
                val updatedAt = rs.getTimestamp("updated_at").toInstant()
                json to updatedAt
            },
            userId, areaId
        )
        val (json, updatedAt) = rows.firstOrNull() ?: return null

        val parsed = objectMapper.readValue(json, PivotStateJson::class.java)
        return UserPivotState(
            userId = userId,
            areaId = areaId,
            pivotRows = parsed.pivotRows.map { UUID.fromString(it) },
            pivotColumns = parsed.pivotColumns.map { UUID.fromString(it) },
            pivotValues = parsed.pivotValues.map { UUID.fromString(it) },
            selections = parsed.selections.mapKeys { UUID.fromString(it.key) }.mapValues { it.value.toSet() },
            updatedAt = updatedAt
        )
    }

    override fun save(state: UserPivotState) {
        val json = objectMapper.writeValueAsString(
            PivotStateJson(
                pivotRows = state.pivotRows.map { it.toString() },
                pivotColumns = state.pivotColumns.map { it.toString() },
                pivotValues = state.pivotValues.map { it.toString() },
                selections = state.selections.mapKeys { it.key.toString() }.mapValues { it.value.toList() }
            )
        )
        jdbcTemplate.update(
            """
                INSERT INTO pg_lbi_user_pivot_state (user_id, area_id, pivot_state, updated_at)
                VALUES (?, ?, ?::jsonb, ?)
                ON CONFLICT (user_id, area_id)
                DO UPDATE SET pivot_state = EXCLUDED.pivot_state, updated_at = EXCLUDED.updated_at
                """,
            state.userId, state.areaId, json, java.sql.Timestamp.from(Instant.now())
        )
    }

    override fun delete(userId: UUID, areaId: UUID) {
        jdbcTemplate.update(
            "DELETE FROM pg_lbi_user_pivot_state WHERE user_id = ? AND area_id = ?",
            userId, areaId
        )
    }
}