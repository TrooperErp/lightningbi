package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.service.AuthService
import com.lightningbi.lightning_engine.service.RegolePassword
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.PasswordField
import com.vaadin.flow.server.VaadinServletRequest

/**
 * Cambio della propria password. Serve la password attuale; la nuova segue
 * la regola di [RegolePassword]. Dopo il cambio le altre sessioni dell'utente
 * (altri browser, altri computer) si chiudono, questa resta aperta.
 */
class CambiaPasswordDialog(private val authService: AuthService) : Dialog() {

    private val attuale = PasswordField("Password attuale").apply { setWidthFull() }
    private val nuova = PasswordField("Nuova password").apply {
        setWidthFull()
        helperText = "Almeno ${RegolePassword.LUNGHEZZA_MINIMA} caratteri"
    }
    private val conferma = PasswordField("Ripeti la nuova password").apply { setWidthFull() }

    init {
        headerTitle = "Cambia password"
        width = "400px"
        add(VerticalLayout(attuale, nuova, conferma).apply { isPadding = false })

        val annulla = Button("Annulla") { close() }
        val salva = Button("Cambia") { salva() }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        footer.add(annulla, salva)
    }

    private fun salva() {
        val utente = CurrentUserHolder.get() ?: return
        if (attuale.value.isNullOrBlank() || nuova.value.isNullOrBlank()) {
            Notification.show("Compila la password attuale e la nuova")
            return
        }
        if (nuova.value != conferma.value) {
            Notification.show("Le due password nuove non coincidono")
            return
        }
        val richiesta = VaadinServletRequest.getCurrent().httpServletRequest
        try {
            val ok = authService.changePassword(
                utente.userId, attuale.value, nuova.value, richiesta.remoteAddr, utente.sessionId
            )
            if (!ok) {
                Notification.show("La password attuale non è corretta", 5000, Notification.Position.MIDDLE)
                return
            }
            close()
            Notification.show("Password cambiata", 3000, Notification.Position.BOTTOM_END)
        } catch (e: IllegalArgumentException) {
            Notification.show(e.message ?: "Password non valida", 6000, Notification.Position.MIDDLE)
        }
    }
}