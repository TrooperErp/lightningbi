package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.view.CurrentUserHolder
import org.springframework.stereotype.Component

/**
 * Unico punto per decidere se l'utente corrente è amministratore: chi ha il
 * permesso MANAGE_USERS (esiste un solo tipo di admin).
 *
 * Nascondere una voce di menu non basta: ogni azione amministrativa chiama
 * [requireAdmin] anche lato server, così è rifiutata anche se invocata per
 * altre vie. Funziona nei thread delle richieste Vaadin (l'utente sta nella
 * sessione); non va chiamato da thread di background.
 */
@Component
class AdminGuard(
    private val permissionCheckService: PermissionCheckService
) {
    fun isAdmin(): Boolean {
        val user = CurrentUserHolder.get() ?: return false
        return permissionCheckService.hasPermission(user.roleName, "MANAGE_USERS")
    }

    /** @throws SecurityException se l'utente corrente non è amministratore */
    fun requireAdmin() {
        if (!isAdmin()) throw SecurityException("Operazione riservata all'amministratore")
    }
}