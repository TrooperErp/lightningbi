package com.lightningbi.lightning_engine.repository

import java.util.UUID

interface RolePermissionRepository {
    fun findPermissionIdsByRoleId(roleId: UUID): List<UUID>
    fun replacePermissions(roleId: UUID, permissionIds: List<UUID>)
}