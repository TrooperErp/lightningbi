package com.lightning.repository

import com.lightning.model.Permission
import java.util.UUID

interface PermissionRepository {
    fun findById(id: UUID): Permission?
    fun findByName(name: String): Permission?
    fun findByCategory(category: String): List<Permission>
    fun save(permission: Permission): Permission
    fun update(permission: Permission): Permission
    fun delete(id: UUID): Boolean
    fun findAll(): List<Permission>
}