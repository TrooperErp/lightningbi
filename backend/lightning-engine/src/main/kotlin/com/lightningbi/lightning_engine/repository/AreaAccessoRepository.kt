package com.lightningbi.lightning_engine.repository

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/** Chi può aprire quale dataset: righe per ruolo oppure per utente. */
@Repository
class AreaAccessoRepository(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) {
    /** I dataset visibili a un utente: quelli del suo ruolo più quelli suoi. */
    fun areeVisibili(utenteId: UUID, ruoloId: UUID?): Set<UUID> {
        val perUtente = jdbcTemplate.query(
            "SELECT area_id FROM lbi_area_accesso WHERE utente_id = ?",
            { rs, _ -> UUID.fromString(rs.getString("area_id")) },
            utenteId
        )
        val perRuolo = if (ruoloId == null) emptyList() else jdbcTemplate.query(
            "SELECT area_id FROM lbi_area_accesso WHERE ruolo_id = ?",
            { rs, _ -> UUID.fromString(rs.getString("area_id")) },
            ruoloId
        )
        return (perUtente + perRuolo).toSet()
    }

    fun ruoliDi(areaId: UUID): Set<UUID> =
        jdbcTemplate.query(
            "SELECT ruolo_id FROM lbi_area_accesso WHERE area_id = ? AND ruolo_id IS NOT NULL",
            { rs, _ -> UUID.fromString(rs.getString("ruolo_id")) },
            areaId
        ).toSet()

    fun utentiDi(areaId: UUID): Set<UUID> =
        jdbcTemplate.query(
            "SELECT utente_id FROM lbi_area_accesso WHERE area_id = ? AND utente_id IS NOT NULL",
            { rs, _ -> UUID.fromString(rs.getString("utente_id")) },
            areaId
        ).toSet()

    /** Sostituisce gli accessi del dataset. Da chiamare dentro una transazione. */
    fun sostituisci(areaId: UUID, ruoli: Set<UUID>, utenti: Set<UUID>) {
        jdbcTemplate.update("DELETE FROM lbi_area_accesso WHERE area_id = ?", areaId)
        ruoli.forEach {
            jdbcTemplate.update(
                "INSERT INTO lbi_area_accesso (id, area_id, ruolo_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), areaId, it
            )
        }
        utenti.forEach {
            jdbcTemplate.update(
                "INSERT INTO lbi_area_accesso (id, area_id, utente_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), areaId, it
            )
        }
    }
}