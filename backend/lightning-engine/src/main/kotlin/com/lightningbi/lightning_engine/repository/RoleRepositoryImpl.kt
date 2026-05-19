package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.Role
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class RoleRepositoryImpl(
    private val jdbcTemplate: JdbcTemplate
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
        jdbcTemplate.query(
            "SELECT * FROM system_roles WHERE id = ?",
            rowMapper, id.toString()
        ).firstOrNull()

    override fun findByName(name: String): Role? =
        jdbcTemplate.query(
            "SELECT * FROM system_roles WHERE name = ?",
            rowMapper, name
        ).firstOrNull()

    override fun save(role: Role): Role {
        jdbcTemplate.update(
            "INSERT INTO system_roles (id, name, description, created_at) VALUES (?, ?, ?, ?)",
            role.id.toString(), role.name, role.description, role.createdAt
        )
        return role
    }

    override fun update(role: Role): Role {
        jdbcTemplate.update("""
            ALTER TABLE system_roles UPDATE
                name = ?, description = ?
            WHERE id = ?
        """,
            role.name, role.description, role.id.toString()
        )
        return role
    }

    override fun delete(id: UUID): Boolean {
        val rows = jdbcTemplate.update(
            "ALTER TABLE system_roles DELETE WHERE id = ?",
            id.toString()
        )
        return rows > 0
    }

    override fun findAll(): List<Role> =
        jdbcTemplate.query("SELECT * FROM system_roles", rowMapper)
}