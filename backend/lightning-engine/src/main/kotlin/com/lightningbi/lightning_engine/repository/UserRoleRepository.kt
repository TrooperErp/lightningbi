package com.lightningbi.lightning_engine.repository

import java.util.UUID

interface UserRoleRepository {
    fun findRoleIdByUserId(userId: UUID): UUID?
    fun assign(userId: UUID, roleId: UUID)
}