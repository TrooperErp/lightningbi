package com.lightningbi.lightning_engine.model

import java.time.LocalDateTime
import java.util.UUID

data class Session (
    val sessionId: String,
    val userId: UUID,
    val roleId: UUID,
    val ipAddress: String,
    val userAgent: String,
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val expiresAt: LocalDateTime,
    val lastActivitiy: LocalDateTime= LocalDateTime.now(),
    val revoked: Boolean= false

)