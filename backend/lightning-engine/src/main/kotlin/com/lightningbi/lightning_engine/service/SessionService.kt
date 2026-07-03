package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.Session
import com.lightningbi.lightning_engine.repository.SessionRepository
import com.lightningbi.lightning_engine.repository.UserRoleRepository
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

@Service
class SessionService(
    private val sessionRepository: SessionRepository,
    private val userRoleRepository: UserRoleRepository
) {

    fun create(userId: UUID, ipAddress: String, userAgent: String): String {
        val roleId = userRoleRepository.findRoleIdByUserId(userId)
            ?: throw IllegalStateException("User $userId has no role assigned")

        val session = Session(
            sessionId = UUID.randomUUID().toString(),
            userId = userId,
            roleId = roleId,
            ipAddress = ipAddress,
            userAgent = userAgent,
            expiresAt = LocalDateTime.now().plusHours(8)
        )
        sessionRepository.save(session)
        return session.sessionId
    }

    fun validate(sessionId: String): Session? {
        val session = sessionRepository.findBySessionId(sessionId) ?: return null
        if (session.revoked || session.expiresAt.isBefore(LocalDateTime.now())) return null
        return session
    }

    fun revoke(sessionId: String) {
        sessionRepository.revoke(sessionId)
    }

    fun revokeAllForUser(userId: UUID) {
        sessionRepository.findByUserId(userId)
            .filter { !it.revoked }
            .forEach { sessionRepository.revoke(it.sessionId) }
    }

    fun cleanExpired() {
        sessionRepository.deleteExpired()
    }
}