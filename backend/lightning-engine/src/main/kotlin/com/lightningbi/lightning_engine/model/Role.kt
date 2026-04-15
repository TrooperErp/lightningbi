package com.lightningbi.lightning_engine.model


import java.time.LocalDateTime
import java.util.UUID

/**
 * Role entity. Rappresenta il ruolo assegnato ad ogni utente del sistema
 * corrisponde alla tabella system_roles di clickHouse
 */
data class Role(
    val id: UUID,
    val name: String,
    val description: String,
    val createdAt: LocalDateTime= LocalDateTime.now()
)