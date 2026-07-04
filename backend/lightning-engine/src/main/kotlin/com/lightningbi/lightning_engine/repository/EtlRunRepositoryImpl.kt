package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.EtlRun
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class EtlRunRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : EtlRunRepository {

    override fun save(run: EtlRun): EtlRun {
        jdbcTemplate.update(
            """INSERT INTO lbi_etl_run
               (id, area_id, source_id, started_at, finished_at, stato, righe_processate, righe_scartate, errore)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            run.id, run.areaId, run.sourceId, run.startedAt, run.finishedAt,
            run.stato, run.righeProcessate, run.righeScartate, run.errore
        )
        return run
    }

    override fun update(run: EtlRun) {
        jdbcTemplate.update(
            """UPDATE lbi_etl_run SET
               finished_at = ?, stato = ?, righe_processate = ?, righe_scartate = ?, errore = ?
               WHERE id = ?""",
            run.finishedAt, run.stato, run.righeProcessate, run.righeScartate, run.errore, run.id
        )
    }
}