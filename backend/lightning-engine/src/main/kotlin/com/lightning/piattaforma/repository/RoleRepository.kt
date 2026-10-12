package com.lightning.piattaforma.repository

import com.lightning.piattaforma.model.Role
import java.util.UUID

interface RoleRepository {
    fun findById(id: UUID): Role?
    fun findByName(name: String): Role?
    fun save(role: Role): Role
    fun update(role: Role): Role
    fun delete(id: UUID): Boolean
    fun findAll(): List<Role>
}