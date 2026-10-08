package com.lightningbi.lightning_engine.repository

import java.util.UUID

interface UserRoleRepository {
    fun findRoleIdByUserId(userId: UUID): UUID?
    fun assign(userId: UUID, roleId: UUID)

    /** Sostituisce il ruolo dell'utente (un utente ha un solo ruolo). */
    fun replace(userId: UUID, roleId: UUID)
}