package com.lightningbi.lightning_engine.model


import java.time.LocalDateTime
import java.util.UUID

data class Role(
    val id: UUID,
    val name: String,
    val description: String,
    val createdAt: LocalDateTime= LocalDateTime.now()
)