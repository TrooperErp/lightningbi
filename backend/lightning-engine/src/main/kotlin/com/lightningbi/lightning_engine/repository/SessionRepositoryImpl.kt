package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.Session
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.UUID

@Repository
class SessionRepositoryImpl(
    private val jdbcTemplate: JdbcTemplate
) : SessionRepository {

    private val rowMapper = RowMapper<Session> { rs, _ ->
        Session(
            sessionId = rs.getString("session_id"),
            userId = UUID.fromString(rs.getString("user_id")),
            roleId = UUID.fromString(rs.getString("role_id")),
            ipAddress = rs.getString("ip_address"),
            userAgent = rs.getString("user_agent"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            expiresAt = rs.getTimestamp("expires_at").toLocalDateTime(),
            lastActivity = rs.getTimestamp("last_activity").toLocalDateTime(),
            revoked = rs.getBoolean("revoked")
        )
    }

    override fun findBySessionId(sessionId: String): Session? =
        jdbcTemplate.query(
            "SELECT * FROM system_sessions WHERE session_id = ?",
            rowMapper, sessionId
        ).firstOrNull()

    override fun findByUserId(userId: UUID): List<Session> =
        jdbcTemplate.query(
            "SELECT * FROM system_sessions WHERE user_id = ?",
            rowMapper, userId.toString()
        )

    override fun save(session: Session): Session {
        jdbcTemplate.update(
            """INSERT INTO system_sessions 
               (session_id, user_id, role_id, ip_address, user_agent, created_at, expires_at, last_activity, revoked)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            session.sessionId, session.userId.toString(), session.roleId.toString(),
            session.ipAddress, session.userAgent, session.createdAt,
            session.expiresAt, session.lastActivity, session.revoked
        )
        return session
    }

    override fun revoke(sessionId: String): Boolean {
        val rows = jdbcTemplate.update(
            "ALTER TABLE system_sessions UPDATE revoked = true WHERE session_id = ?",
            sessionId
        )
        return rows > 0
    }

    override fun deleteExpired(): Int =
        jdbcTemplate.update(
            "ALTER TABLE system_sessions DELETE WHERE expires_at < ?",
            LocalDateTime.now()
        )

    override fun findAll(): List<Session> =
        jdbcTemplate.query("SELECT * FROM system_sessions", rowMapper)
}