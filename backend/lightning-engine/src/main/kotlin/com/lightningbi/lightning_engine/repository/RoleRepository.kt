package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.Role
import com.lightningbi.lightning_engine.model.User
import java.util.UUID

interface RoleRepository {
    fun findById(id: UUID): Role?
    fun findByName(name: String): Role?
    fun save(role: Role): Role
    fun update(role: Role): Role
    fun delete(id: UUID): Boolean
    fun findAll(): List<Role>
}