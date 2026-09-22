package com.lightningbi.lightning_engine.view

import com.vaadin.flow.server.VaadinSession
import java.util.UUID

/**
 * Utente autenticato nella sessione Vaadin corrente. Non è un token da
 * allegare a richieste HTTP (quello è il JWT, pensato per API REST):
 * Vaadin naviga su WebSocket/long-polling dopo il caricamento iniziale,
 * dove non esiste un punto naturale per allegare un header Bearer ad
 * ogni frame. La sessione Vaadin stessa (backata da cookie HTTP
 * standard) è il meccanismo giusto qui.
 *
 * sessionId è comunque quello tracciato da SessionService su Postgres:
 * al logout va revocato lì, non solo rimosso dalla sessione Vaadin,
 * altrimenti il record di sessione resterebbe valido a tempo indeterminato
 * lato server anche se l'utente ha già premuto "Esci".
 */
data class AuthenticatedUser(
    val userId: UUID,
    val username: String,
    val roleName: String,
    val sessionId: String
)

object CurrentUserHolder {
    private const val KEY = "lbi-authenticated-user"

    fun set(user: AuthenticatedUser) {
        VaadinSession.getCurrent()?.setAttribute(KEY, user)
    }

    fun get(): AuthenticatedUser? =
        VaadinSession.getCurrent()?.getAttribute(KEY) as? AuthenticatedUser

    fun clear() {
        VaadinSession.getCurrent()?.setAttribute(KEY, null)
    }
}