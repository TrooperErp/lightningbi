package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.EtlSyncState
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class EtlSyncStateRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : EtlSyncStateRepository {

    override fun find(areaId: UUID, sourceId: UUID): EtlSyncState? =
        jdbcTemplate.query(
            "SELECT * FROM lbi_etl_sync_state WHERE area_id = ? AND source_id = ?",
            { rs, _ -> EtlSyncState(
                UUID.fromString(rs.getString("area_id")),
                UUID.fromString(rs.getString("source_id")),
                rs.getTimestamp("last_sync")?.toLocalDateTime()
            ) },
            areaId, sourceId
        ).firstOrNull()

    override fun upsert(state: EtlSyncState) {
        jdbcTemplate.update(
            """INSERT INTO lbi_etl_sync_state (area_id, source_id, last_sync)
               VALUES (?, ?, ?)
               ON CONFLICT (area_id, source_id) DO UPDATE SET last_sync = ?""",
            state.areaId, state.sourceId, state.lastSync, state.lastSync
        )
    }
}