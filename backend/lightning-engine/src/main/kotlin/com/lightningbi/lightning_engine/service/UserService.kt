package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.User
import com.lightningbi.lightning_engine.repository.UserRepository
import com.lightningbi.lightning_engine.repository.UserRoleRepository
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

@Service
class UserService(
    private val userRepository: UserRepository,
    private val userRoleRepository: UserRoleRepository,
    private val passwordEncoder: BCryptPasswordEncoder,
    private val auditService: AuditService
) {

    fun createUser(
        username: String, email: String, tempPassword: String,
        roleId: UUID, adminId: UUID, ipAddress: String
    ): User {
        require(userRepository.findByUsername(username) == null) { "Username already exists" }

        val user = User(
            id = UUID.randomUUID(),
            username = username,
            email = email,
            passwordHash = passwordEncoder.encode(tempPassword)!!
        )
        userRepository.save(user)
        userRoleRepository.assign(user.id, roleId)
        auditService.log("USER_CREATED", adminId, "Created user $username with role $roleId", ipAddress)
        return user
    }

    fun deactivateUser(userId: UUID, adminId: UUID, ipAddress: String): Boolean {
        val user = userRepository.findById(userId) ?: return false
        userRepository.update(user.copy(active = false, updatedAt = LocalDateTime.now()))
        auditService.log("USER_DEACTIVATED", adminId, "Deactivated user ${user.username}", ipAddress)
        return true
    }

    fun listUsers(): List<User> = userRepository.findAll()
}