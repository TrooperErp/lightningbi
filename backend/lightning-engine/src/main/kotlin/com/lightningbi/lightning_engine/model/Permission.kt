package com.lightningbi.lightning_engine.model

import java.time.LocalDateTime
import java.util.UUID

data class Permission(
    val id: UUID,
    val name: String,
    val description: String,
    val category: String,
    val createdAt: LocalDateTime= LocalDateTime.now()
)