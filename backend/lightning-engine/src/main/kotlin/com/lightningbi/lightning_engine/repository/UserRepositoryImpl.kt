package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.User
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UserRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : UserRepository {

    private val rowMapper = RowMapper<User> { rs, _ ->
        User(
            id = UUID.fromString(rs.getString("id")),
            username = rs.getString("username"),
            email = rs.getString("email"),
            passwordHash = rs.getString("password_hash"),
            lastLogin = rs.getTimestamp("last_login")?.toLocalDateTime(),
            failedAttempts = rs.getInt("failed_attempts"),
            lockedUntil = rs.getTimestamp("locked_until")?.toLocalDateTime(),
            mfaEnabled = rs.getBoolean("mfa_enabled"),
            mfaSecret = rs.getString("mfa_secret"),
            recoveryCodesHash = rs.getString("recovery_codes_hash"),
            active = rs.getBoolean("active"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
        )
    }

    override fun findByUsername(username: String): User? =
        jdbcTemplate.query("SELECT * FROM pg_lbi_system_users WHERE username = ?", rowMapper, username).firstOrNull()

    override fun findById(id: UUID): User? =
        jdbcTemplate.query("SELECT * FROM pg_lbi_system_users WHERE id = ?", rowMapper, id).firstOrNull()

    override fun save(user: User): User {
        jdbcTemplate.update(
            """INSERT INTO pg_lbi_system_users 
               (id, username, email, password_hash, mfa_enabled, active, created_at, updated_at)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
            user.id, user.username, user.email, user.passwordHash,
            user.mfaEnabled, user.active, user.createdAt, user.updatedAt
        )
        return user
    }

    override fun update(user: User): User {
        jdbcTemplate.update(
            """UPDATE pg_lbi_system_users SET
               email = ?, password_hash = ?, last_login = ?,
               failed_attempts = ?, locked_until = ?, mfa_enabled = ?,
               mfa_secret = ?, active = ?, updated_at = ?
               WHERE id = ?""",
            user.email, user.passwordHash, user.lastLogin,
            user.failedAttempts, user.lockedUntil, user.mfaEnabled,
            user.mfaSecret, user.active, user.updatedAt, user.id
        )
        return user
    }

    override fun delete(id: UUID): Boolean =
        jdbcTemplate.update("DELETE FROM pg_lbi_system_users WHERE id = ?", id) > 0

    override fun findAll(): List<User> =
        jdbcTemplate.query("SELECT * FROM pg_lbi_system_users", rowMapper)
}