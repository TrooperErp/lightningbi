package com.lightning.piattaforma.repository

import com.lightning.piattaforma.model.User
import java.util.UUID

interface UserRepository {
    fun findByUsername(username: String): User?
    fun findById(id: UUID): User?
    fun save(user: User): User
    fun update(user: User): User
    fun delete(id: UUID): Boolean
    fun findAll(): List<User>
}