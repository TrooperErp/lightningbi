package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.Session
import com.lightningbi.lightning_engine.repository.SessionRepository
import com.lightningbi.lightning_engine.repository.UserRepository
import com.lightningbi.lightning_engine.repository.UserRoleRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

/**
 * Sessioni di lavoro. La verità è su Postgres: una sessione vale finché non
 * è scaduta, non è revocata e l'utente è attivo. Durata FISSA dal login
 * (non si rinnova con l'uso).
 */
@Service
class SessionService(
    private val sessionRepository: SessionRepository,
    private val userRoleRepository: UserRoleRepository,
    private val userRepository: UserRepository,
    @Value("\${lightningbi.security.session-hours:4}") private val ore: Long
) {

    /** Durata della sessione in secondi: serve anche al cookie, che scade insieme alla sessione. */
    val durataSecondi: Int = (ore * 3600).toInt()

    fun create(userId: UUID, ipAddress: String, userAgent: String): String {
        val roleId = userRoleRepository.findRoleIdByUserId(userId)
            ?: throw IllegalStateException("User $userId has no role assigned")

        val session = Session(
            sessionId = UUID.randomUUID().toString(),
            userId = userId,
            roleId = roleId,
            ipAddress = ipAddress,
            userAgent = userAgent,
            expiresAt = LocalDateTime.now().plusHours(ore)
        )
        sessionRepository.save(session)
        return session.sessionId
    }

    /** La sessione se è ancora valida, altrimenti null (scaduta, revocata, utente disattivato o cancellato). */
    fun validate(sessionId: String): Session? {
        val session = sessionRepository.findBySessionId(sessionId) ?: return null
        if (session.revoked || session.expiresAt.isBefore(LocalDateTime.now())) return null
        val utente = userRepository.findById(session.userId) ?: return null
        if (!utente.active) return null
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

    /** Come [revokeAllForUser], ma salva la sessione indicata (quella da cui l'utente sta operando). */
    fun revokeOthersForUser(userId: UUID, tenere: String) {
        sessionRepository.findByUserId(userId)
            .filter { !it.revoked && it.sessionId != tenere }
            .forEach { sessionRepository.revoke(it.sessionId) }
    }

    fun cleanExpired() {
        sessionRepository.deleteExpired()
    }
}