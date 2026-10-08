package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.User
import com.lightningbi.lightning_engine.repository.RoleRepository
import com.lightningbi.lightning_engine.repository.UserRepository
import com.lightningbi.lightning_engine.repository.UserRoleRepository
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/** Regola unica per le password (creazione, reset da admin, cambio dell'utente). */
object RegolePassword {
    const val LUNGHEZZA_MINIMA = 8

    /** Il motivo del rifiuto, oppure null se la password va bene. */
    fun controlla(password: String, username: String): String? = when {
        password.length < LUNGHEZZA_MINIMA -> "La password deve avere almeno $LUNGHEZZA_MINIMA caratteri"
        password.equals(username, ignoreCase = true) -> "La password non può coincidere con il nome utente"
        else -> null
    }
}

/**
 * Gestione degli utenti (solo admin: chi chiama deve aver già verificato il permesso).
 *
 * Errori di input: IllegalArgumentException; operazioni non consentite
 * (se stessi, ultimo admin): IllegalStateException. Messaggi in italiano,
 * pronti per l'interfaccia.
 */
@Service
class UserService(
    private val userRepository: UserRepository,
    private val userRoleRepository: UserRoleRepository,
    private val roleRepository: RoleRepository,
    private val passwordEncoder: BCryptPasswordEncoder,
    private val auditService: AuditService,
    private val sessionService: SessionService,
    private val permissionCheckService: PermissionCheckService
) {

    @Transactional("postgresTransactionManager")
    fun createUser(
        username: String, email: String, tempPassword: String,
        roleId: UUID, adminId: UUID, ipAddress: String,
        azienda: Int? = null
    ): User {
        val nome = username.trim()
        require(nome.isNotEmpty()) { "Il nome utente è obbligatorio" }
        require(userRepository.findByUsername(nome) == null) { "Il nome utente esiste già" }
        RegolePassword.controlla(tempPassword, nome)?.let { throw IllegalArgumentException(it) }
        controllaAzienda(roleId, azienda)

        val user = User(
            id = UUID.randomUUID(),
            username = nome,
            email = email.trim(),
            passwordHash = passwordEncoder.encode(tempPassword)!!,
            codiceDittaAssegnata = azienda
        )
        userRepository.save(user)
        userRoleRepository.assign(user.id, roleId)
        auditService.log("USER_CREATED", adminId, "Created user $nome with role $roleId", ipAddress, username = nome)
        return user
    }

    @Transactional("postgresTransactionManager")
    fun deactivateUser(userId: UUID, adminId: UUID, ipAddress: String): Boolean {
        val user = userRepository.findById(userId) ?: return false
        check(userId != adminId) { "Non puoi disattivare il tuo stesso utente" }
        controllaNonUltimoAdmin(user)
        userRepository.update(user.copy(active = false, updatedAt = LocalDateTime.now()))
        sessionService.revokeAllForUser(userId)
        auditService.log("USER_DEACTIVATED", adminId, "Deactivated user ${user.username}", ipAddress, username = user.username)
        return true
    }

    @Transactional("postgresTransactionManager")
    fun activateUser(userId: UUID, adminId: UUID, ipAddress: String): Boolean {
        val user = userRepository.findById(userId) ?: return false
        userRepository.update(
            user.copy(active = true, failedAttempts = 0, lockedUntil = null, updatedAt = LocalDateTime.now())
        )
        auditService.log("USER_ACTIVATED", adminId, "Activated user ${user.username}", ipAddress, username = user.username)
        return true
    }

    /** Cambia ruolo e, se serve, azienda in un colpo solo (il nuovo ruolo può richiederla). *//** Cambia ruolo e, se serve, azienda in un colpo solo (il nuovo ruolo può richiedere l'azienda). */
    @Transactional("postgresTransactionManager")
    fun changeRole(userId: UUID, roleId: UUID, azienda: Int?, adminId: UUID, ipAddress: String) {
        val user = userRepository.findById(userId) ?: throw IllegalArgumentException("Utente non trovato")
        val attuale = userRoleRepository.findRoleIdByUserId(userId)
        if (attuale == roleId && azienda == user.codiceDittaAssegnata) return

        if (attuale != roleId) check(userId != adminId) { "Non puoi cambiare il ruolo del tuo stesso utente" }
        controllaAzienda(roleId, azienda)
        if (attuale != roleId && !èAdmin(roleId)) controllaNonUltimoAdmin(user)

        userRoleRepository.replace(userId, roleId)
        userRepository.update(user.copy(codiceDittaAssegnata = azienda, updatedAt = LocalDateTime.now()))
        sessionService.revokeAllForUser(userId)
        auditService.log(
            "USER_ROLE_CHANGED", adminId,
            "User ${user.username}: role $attuale -> $roleId, azienda ${user.codiceDittaAssegnata} -> $azienda",
            ipAddress, username = user.username
        )
    }

    /** Reset della password da parte dell'admin: chiude le sessioni e sblocca l'account. */
    @Transactional("postgresTransactionManager")
    fun resetPassword(userId: UUID, nuovaPassword: String, adminId: UUID, ipAddress: String) {
        val user = userRepository.findById(userId) ?: throw IllegalArgumentException("Utente non trovato")
        RegolePassword.controlla(nuovaPassword, user.username)?.let { throw IllegalArgumentException(it) }
        userRepository.update(
            user.copy(
                passwordHash = passwordEncoder.encode(nuovaPassword)!!,
                failedAttempts = 0,
                lockedUntil = null,
                updatedAt = LocalDateTime.now()
            )
        )
        sessionService.revokeAllForUser(userId)
        auditService.log("PASSWORD_RESET", adminId, "Password reset for ${user.username}", ipAddress, username = user.username)
    }

    fun updateEmail(userId: UUID, email: String, adminId: UUID, ipAddress: String) {
        val user = userRepository.findById(userId) ?: throw IllegalArgumentException("Utente non trovato")
        val nuova = email.trim()
        require(nuova.isNotEmpty()) { "L'email non può essere vuota" }
        if (nuova == user.email) return
        userRepository.update(user.copy(email = nuova, updatedAt = LocalDateTime.now()))
        auditService.log("USER_EMAIL_CHANGED", adminId, "Email changed for ${user.username}", ipAddress, username = user.username)
    }

    fun listUsers(): List<User> = userRepository.findAll()

    /** Il ruolo dell'utente: serve alla schermata utenti per mostrarlo e per sapere se è admin. */
    fun roleIdOf(userId: UUID): UUID? = userRoleRepository.findRoleIdByUserId(userId)

    /** Vero se il ruolo ha il permesso di amministrare gli utenti (vede tutte le aziende). */
    fun èAdmin(roleId: UUID): Boolean {
        val ruolo = roleRepository.findById(roleId) ?: return false
        return permissionCheckService.hasPermission(ruolo.name, "MANAGE_USERS")
    }

    /** Un utente non admin deve avere un'azienda: è il suo section access. */
    private fun controllaAzienda(roleId: UUID, azienda: Int?) {
        require(roleRepository.findById(roleId) != null) { "Ruolo non trovato" }
        if (!èAdmin(roleId)) {
            require(azienda != null) { "Un utente non amministratore deve avere un'azienda assegnata" }
        }
    }

    /** Vieta di togliere (disattivare o declassare) l'ultimo admin attivo. */
    private fun controllaNonUltimoAdmin(user: User) {
        if (!user.active) return
        val ruolo = userRoleRepository.findRoleIdByUserId(user.id) ?: return
        if (!èAdmin(ruolo)) return
        val altriAdminAttivi = userRepository.findAll().count { u ->
            u.id != user.id && u.active && userRoleRepository.findRoleIdByUserId(u.id)?.let { èAdmin(it) } == true
        }
        check(altriAdminAttivi > 0) { "È l'ultimo amministratore attivo: non può essere disattivato né declassato" }
    }
}