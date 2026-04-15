package com.lightningbi.lightning_engine.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Permission entity. Rappresenta i permessi garantiti ad ogni ruolo.
 * corrisponde alla tabella system_permission di ClickHouse
 */
data class Permission(
    val id: UUID,
    val name: String,
    val description: String,
    val category: String,
    val createdAt: LocalDateTime= LocalDateTime.now()
)