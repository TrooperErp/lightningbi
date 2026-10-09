// FILE: src/main/kotlin/com/lightningbi/lightning_engine/repository/SorgenteTabellaRepository.kt
package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.SorgenteTabella
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/** Sorgenti aggiuntive delle tabelle importate (lbi_imported_table_sorgente, migrazione 039). */
interface SorgenteTabellaRepository {

    /** Le sorgenti aggiuntive di una tabella, in ordine di numero. */
    fun findByTable(importedTableId: UUID): List<SorgenteTabella>

    /** Inserisce o aggiorna (per id). Fallisce se numero o connessione sono già usati nella stessa tabella. */
    fun save(sorgente: SorgenteTabella): SorgenteTabella

    /** Toglie una sorgente. Restituisce true se esisteva. */
    fun delete(id: UUID): Boolean

    /** Il primo numero libero per una nuova sorgente della tabella (1 se non ne ha). */
    fun prossimoOrdine(importedTableId: UUID): Int
}

@Repository
class SorgenteTabellaRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : SorgenteTabellaRepository {

    override fun findByTable(importedTableId: UUID): List<SorgenteTabella> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_imported_table_sorgente WHERE imported_table_id = ? ORDER BY ordine",
            { rs, _ -> mapRow(rs) },
            importedTableId
        )

    override fun save(sorgente: SorgenteTabella): SorgenteTabella {
        jdbcTemplate.update(
            """
            INSERT INTO lbi_imported_table_sorgente
                (id, imported_table_id, ordine, connection_id, ditta_forzata, prefisso_chiavi)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                connection_id = EXCLUDED.connection_id,
                ditta_forzata = EXCLUDED.ditta_forzata,
                prefisso_chiavi = EXCLUDED.prefisso_chiavi
            """.trimIndent(),
            sorgente.id, sorgente.importedTableId, sorgente.ordine, sorgente.connectionId,
            sorgente.dittaForzata, sorgente.prefissoChiavi
        )
        return sorgente
    }

    override fun delete(id: UUID): Boolean =
        jdbcTemplate.update("DELETE FROM lbi_imported_table_sorgente WHERE id = ?", id) > 0

    override fun prossimoOrdine(importedTableId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(ordine), 0) + 1 FROM lbi_imported_table_sorgente WHERE imported_table_id = ?",
            Int::class.java,
            importedTableId
        ) ?: 1

    private fun mapRow(rs: ResultSet): SorgenteTabella = SorgenteTabella(
        id = UUID.fromString(rs.getString("id")),
        importedTableId = UUID.fromString(rs.getString("imported_table_id")),
        ordine = rs.getInt("ordine"),
        connectionId = UUID.fromString(rs.getString("connection_id")),
        dittaForzata = (rs.getObject("ditta_forzata") as Number?)?.toInt(),
        prefissoChiavi = rs.getString("prefisso_chiavi")
    )
}