package com.lightningbi.lightning_engine.repository

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class UserRoleRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : UserRoleRepository {

    override fun findRoleIdByUserId(userId: UUID): UUID? =
        jdbcTemplate.query(
            "SELECT role_id FROM pg_lbi_system_user_roles WHERE user_id = ? LIMIT 1",
            { rs, _ -> UUID.fromString(rs.getString("role_id")) },
            userId
        ).firstOrNull()

    override fun assign(userId: UUID, roleId: UUID) {
        jdbcTemplate.update(
            "INSERT INTO pg_lbi_system_user_roles (user_id, role_id) VALUES (?, ?)",
            userId, roleId
        )
    }
}