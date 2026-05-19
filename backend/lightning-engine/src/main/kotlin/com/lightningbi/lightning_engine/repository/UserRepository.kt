package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.User
import java.util.UUID

interface UserRepository {
    fun findByUsername(username: String): User?
    fun findById(id: UUID): User?
    fun save(user: User): User
    fun update(user: User): User
    fun delete(id: UUID): Boolean
    fun findAll(): List<User>
}