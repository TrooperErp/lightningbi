package com.lightningbi.lightning_engine.repository

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class DataVersionRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : DataVersionRepository {

    override fun getVersion(areaId: UUID): Long =
        jdbcTemplate.query(
            "SELECT data_version FROM lbi_area_data_version WHERE area_id = ?",
            { rs, _ -> rs.getLong("data_version") },
            areaId
        ).firstOrNull() ?: 1L

    override fun bumpVersion(areaId: UUID) {
        jdbcTemplate.update(
            """INSERT INTO lbi_area_data_version (area_id, data_version)
               VALUES (?, 1)
               ON CONFLICT (area_id) DO UPDATE SET data_version = lbi_area_data_version.data_version + 1""",
            areaId
        )
    }
}