package com.lightningbi.lightning_engine.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * User entity - rappresenta un utente del sistema
 * Corrisponde alla tabella system_users in ClickHouse
 */
data class User(
    val id: UUID,
    val username: String,
    val email: String,
    val passwordHash: String,

    // Security fields
    val lastLogin: LocalDateTime? = null,
    val failedAttempts: Int = 0,
    val lockedUntil: LocalDateTime? = null,

    // MFA
    val mfaEnabled: Boolean = false,
    val mfaSecret: String? = null,
    val recoveryCodesHash: String? = null,

    // Status
    val active: Boolean = true,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val updatedAt: LocalDateTime = LocalDateTime.now()
)