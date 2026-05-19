package com.lightningbi.lightning_engine.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Session Entity. Rappresenta i dati di ogni sessione di lavoro per ogni utente/ruolo
 * corrisponde alla tabella system_session di Click House
 */
data class Session (
    val sessionId: String,
    val userId: UUID,
    val roleId: UUID,
    val ipAddress: String,
    val userAgent: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val expiresAt: LocalDateTime,
    val lastActivity: LocalDateTime= LocalDateTime.now(),
    val revoked: Boolean= false

)