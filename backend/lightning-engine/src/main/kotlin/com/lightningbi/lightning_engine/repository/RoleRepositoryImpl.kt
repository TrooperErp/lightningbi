package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.Role
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class RoleRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : RoleRepository {

    private val rowMapper = RowMapper<Role> { rs, _ ->
        Role(
            id = UUID.fromString(rs.getString("id")),
            name = rs.getString("name"),
            description = rs.getString("description"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    override fun findById(id: UUID): Role? =
        jdbcTemplate.query("SELECT * FROM pg_lbi_system_roles WHERE id = ?", rowMapper, id).firstOrNull()

    override fun findByName(name: String): Role? =
        jdbcTemplate.query("SELECT * FROM pg_lbi_system_roles WHERE name = ?", rowMapper, name).firstOrNull()

    override fun save(role: Role): Role {
        jdbcTemplate.update(
            "INSERT INTO pg_lbi_system_roles (id, name, description, created_at) VALUES (?, ?, ?, ?)",
            role.id, role.name, role.description, role.createdAt
        )
        return role
    }

    override fun update(role: Role): Role {
        jdbcTemplate.update(
            "UPDATE pg_lbi_system_roles SET name = ?, description = ? WHERE id = ?",
            role.name, role.description, role.id
        )
        return role
    }

    override fun delete(id: UUID): Boolean =
        jdbcTemplate.update("DELETE FROM pg_lbi_system_roles WHERE id = ?", id) > 0

    override fun findAll(): List<Role> =
        jdbcTemplate.query("SELECT * FROM pg_lbi_system_roles", rowMapper)
}