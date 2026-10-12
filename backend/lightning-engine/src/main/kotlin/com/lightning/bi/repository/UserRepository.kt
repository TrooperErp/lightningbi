package com.lightning.bi.repository

import com.lightning.bi.model.User
import java.util.UUID

interface UserRepository {
    fun findByUsername(username: String): User?
    fun findById(id: UUID): User?
    fun save(user: User): User
    fun update(user: User): User
    fun delete(id: UUID): Boolean
    fun findAll(): List<User>
}