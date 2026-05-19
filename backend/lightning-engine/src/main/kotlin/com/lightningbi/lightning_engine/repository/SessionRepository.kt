package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.Session
import java.util.UUID

interface SessionRepository {
    fun findBySessionId(sessionId: String): Session?
    fun findByUserId(userId: UUID): List<Session>
    fun save(session: Session): Session
    fun revoke(sessionId: String): Boolean
    fun deleteExpired(): Int
    fun findAll(): List<Session>
}