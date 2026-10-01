package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.ModalitaSync
import com.lightningbi.lightning_engine.model.TableSync
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.UUID

@Repository
class TableSyncRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper
) : TableSyncRepository {

    override fun findByTable(importedTableId: UUID): TableSync? =
        jdbcTemplate.query(
            "SELECT * FROM lbi_table_sync WHERE imported_table_id = ?",
            { rs, _ -> mapRow(rs) },
            importedTableId
        ).firstOrNull()

    override fun save(sync: TableSync): TableSync {
        jdbcTemplate.update(
            """
            INSERT INTO lbi_table_sync
                (imported_table_id, modalita, colonne_unita, query_cambiati, query_sempre,
                 confronta_cancellazioni, margine_secondi, ultima_sync_inizio)
            VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            ON CONFLICT (imported_table_id) DO UPDATE SET
                modalita = EXCLUDED.modalita,
                colonne_unita = EXCLUDED.colonne_unita,
                query_cambiati = EXCLUDED.query_cambiati,
                query_sempre = EXCLUDED.query_sempre,
                confronta_cancellazioni = EXCLUDED.confronta_cancellazioni,
                margine_secondi = EXCLUDED.margine_secondi
            """.trimIndent(),
            sync.importedTableId,
            sync.modalita.name,
            objectMapper.writeValueAsString(sync.colonneUnita),
            sync.queryCambiati,
            sync.querySempre,
            sync.confrontaCancellazioni,
            sync.margineSecondi,
            sync.ultimaSyncInizio?.let { Timestamp.valueOf(it) }
        )
        return sync
    }

    override fun updateUltimaSync(importedTableId: UUID, ultimaSyncInizio: LocalDateTime) {
        jdbcTemplate.update(
            "UPDATE lbi_table_sync SET ultima_sync_inizio = ? WHERE imported_table_id = ?",
            Timestamp.valueOf(ultimaSyncInizio), importedTableId
        )
    }

    override fun delete(importedTableId: UUID): Boolean =
        jdbcTemplate.update("DELETE FROM lbi_table_sync WHERE imported_table_id = ?", importedTableId) > 0

    private fun mapRow(rs: ResultSet): TableSync = TableSync(
        importedTableId = UUID.fromString(rs.getString("imported_table_id")),
        modalita = ModalitaSync.valueOf(rs.getString("modalita")),
        colonneUnita = readList(rs.getString("colonne_unita")),
        queryCambiati = rs.getString("query_cambiati"),
        querySempre = rs.getString("query_sempre"),
        confrontaCancellazioni = rs.getBoolean("confronta_cancellazioni"),
        margineSecondi = rs.getInt("margine_secondi"),
        ultimaSyncInizio = rs.getTimestamp("ultima_sync_inizio")?.toLocalDateTime()
    )

    private fun readList(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return objectMapper.readValue(json, List::class.java).mapNotNull { it?.toString() }
    }
}