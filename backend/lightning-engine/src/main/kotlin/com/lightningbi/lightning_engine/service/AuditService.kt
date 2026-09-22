package com.lightningbi.lightning_engine.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

/**
 * Registra eventi di autenticazione (login, logout, cambio password,
 * creazione/disattivazione utenti) su ch_lbi_auth_events - la tabella
 * pensata esattamente per questo (vedi V2 audit tables), distinta da
 * ch_lbi_audit_log che traccia l'uso delle analisi (query, filtri, righe
 * restituite), un dominio diverso con colonne diverse per natura.
 */
@Service
class AuditService(
    private val jdbcTemplate: JdbcTemplate
) {
    fun log(
        action: String,
        userId: UUID?,
        details: String,
        ipAddress: String,
        username: String = "",
        userAgent: String = ""
    ) {
        jdbcTemplate.update("""
            INSERT INTO ch_lbi_auth_events
            (id, event_time, user_id, username, event_type, ip_address, user_agent, details_json)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """,
            UUID.randomUUID().toString(),
            LocalDateTime.now(),
            userId?.toString(),
            username,
            action,
            ipAddress,
            userAgent,
            details
        )
    }
}