package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.User
import com.lightningbi.lightning_engine.repository.RoleRepository
import com.lightningbi.lightning_engine.repository.UserRepository
import com.lightningbi.lightning_engine.repository.UserRoleRepository
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val userRoleRepository: UserRoleRepository,
    private val roleRepository: RoleRepository,
    private val sessionService: SessionService,
    private val auditService: AuditService,
    private val jwtService: JwtService,
    private val passwordEncoder: BCryptPasswordEncoder
) {

    fun login(username: String, password: String, ipAddress: String, userAgent: String): String? {
        val user = userRepository.findByUsername(username) ?: run {
            auditService.log("LOGIN_FAILED", null, "User not found: $username", ipAddress)
            return null
        }

        if (!user.active) {
            auditService.log("LOGIN_FAILED", user.id, "Account disabled", ipAddress)
            return null
        }

        if (user.lockedUntil != null && user.lockedUntil.isAfter(LocalDateTime.now())) {
            auditService.log("LOGIN_FAILED", user.id, "Account locked", ipAddress)
            return null
        }

        if (!passwordEncoder.matches(password, user.passwordHash)) {
            handleFailedAttempt(user, ipAddress)
            return null
        }

        val updatedUser = user.copy(
            failedAttempts = 0,
            lockedUntil = null,
            lastLogin = LocalDateTime.now()
        )
        userRepository.update(updatedUser)

        val roleId = userRoleRepository.findRoleIdByUserId(user.id)
            ?: throw IllegalStateException("User ${user.id} has no role assigned")
        val role = roleRepository.findById(roleId)
            ?: throw IllegalStateException("Role $roleId not found")

        val sessionId = sessionService.create(user.id, ipAddress, userAgent)
        auditService.log("LOGIN_SUCCESS", user.id, "Login successful", ipAddress)
        return jwtService.generate(user.id, sessionId, role.name)
    }

    fun logout(sessionId: String, userId: UUID, ipAddress: String) {
        sessionService.revoke(sessionId)
        auditService.log("LOGOUT", userId, "Logout", ipAddress)
    }

    fun changePassword(userId: UUID, oldPassword: String, newPassword: String, ipAddress: String): Boolean {
        val user = userRepository.findById(userId) ?: return false

        if (!passwordEncoder.matches(oldPassword, user.passwordHash)) {
            auditService.log("PASSWORD_CHANGE_FAILED", userId, "Wrong current password", ipAddress)
            return false
        }

        val updatedUser = user.copy(
            passwordHash = passwordEncoder.encode(newPassword)!!,
            updatedAt = LocalDateTime.now()
        )
        userRepository.update(updatedUser)
        auditService.log("PASSWORD_CHANGED", userId, "Password changed", ipAddress)
        return true
    }

    private fun handleFailedAttempt(user: User, ipAddress: String) {
        val attempts = user.failedAttempts + 1
        val lockedUntil = if (attempts >= 5) LocalDateTime.now().plusMinutes(15) else null
        userRepository.update(user.copy(failedAttempts = attempts, lockedUntil = lockedUntil))
        auditService.log("LOGIN_FAILED", user.id, "Wrong password, attempt $attempts", ipAddress)
    }
}