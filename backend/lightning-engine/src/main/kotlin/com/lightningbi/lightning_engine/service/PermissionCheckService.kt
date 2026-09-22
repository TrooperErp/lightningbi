package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.repository.PermissionRepository
import com.lightningbi.lightning_engine.repository.RolePermissionRepository
import com.lightningbi.lightning_engine.repository.RoleRepository
import org.springframework.stereotype.Service

/**
 * Punto unico per chiedere "questo ruolo ha questo permesso?", invece di
 * far ricomporre a ogni chiamante (view, dialog) le tre letture
 * (RoleRepository + PermissionRepository + RolePermissionRepository)
 * necessarie per rispondere. Le view che devono solo mostrare/nascondere
 * una voce di menu o bloccare un accesso dipendono da questo solo
 * servizio, non dai tre repository di ruoli/permessi.
 */
@Service
class PermissionCheckService(
    private val roleRepository: RoleRepository,
    private val permissionRepository: PermissionRepository,
    private val rolePermissionRepository: RolePermissionRepository
) {
    fun hasPermission(roleName: String, permissionCode: String): Boolean {
        val role = roleRepository.findByName(roleName) ?: return false
        val permission = permissionRepository.findByName(permissionCode) ?: return false
        val permissionIds = rolePermissionRepository.findPermissionIdsByRoleId(role.id)
        return permission.id in permissionIds
    }
}