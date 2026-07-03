package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.Permission
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class PermissionRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : PermissionRepository {

    private val rowMapper = RowMapper<Permission> { rs, _ ->
        Permission(
            id = UUID.fromString(rs.getString("id")),
            name = rs.getString("name"),
            description = rs.getString("description"),
            category = rs.getString("category"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime()
        )
    }

    override fun findById(id: UUID): Permission? =
        jdbcTemplate.query("SELECT * FROM pg_lbi_system_permissions WHERE id = ?", rowMapper, id).firstOrNull()

    override fun findByName(name: String): Permission? =
        jdbcTemplate.query("SELECT * FROM pg_lbi_system_permissions WHERE name = ?", rowMapper, name).firstOrNull()

    override fun findByCategory(category: String): List<Permission> =
        jdbcTemplate.query("SELECT * FROM pg_lbi_system_permissions WHERE category = ?", rowMapper, category)

    override fun save(permission: Permission): Permission {
        jdbcTemplate.update(
            "INSERT INTO pg_lbi_system_permissions (id, name, description, category, created_at) VALUES (?, ?, ?, ?, ?)",
            permission.id, permission.name, permission.description, permission.category, permission.createdAt
        )
        return permission
    }

    override fun update(permission: Permission): Permission {
        jdbcTemplate.update(
            "UPDATE pg_lbi_system_permissions SET name = ?, description = ?, category = ? WHERE id = ?",
            permission.name, permission.description, permission.category, permission.id
        )
        return permission
    }

    override fun delete(id: UUID): Boolean =
        jdbcTemplate.update("DELETE FROM pg_lbi_system_permissions WHERE id = ?", id) > 0

    override fun findAll(): List<Permission> =
        jdbcTemplate.query("SELECT * FROM pg_lbi_system_permissions", rowMapper)
}