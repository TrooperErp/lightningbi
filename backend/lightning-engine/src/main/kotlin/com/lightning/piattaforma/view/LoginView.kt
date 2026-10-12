package com.lightning.piattaforma.view

import com.lightning.bi.view.DatasetFiltriView
import com.lightning.piattaforma.repository.UserRepository
import com.lightning.piattaforma.service.AuthService
import com.lightning.piattaforma.service.JwtService
import com.lightning.piattaforma.service.SessionService
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.PasswordField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.VaadinServletRequest

/**
 * Pagina di login: nessuna shell/sidebar (non è ancora un utente
 * autenticato, non deve vedere il resto dell'applicazione). Riusa
 * AuthService così com'è (già completo: lock dopo 5 tentativi, audit
 * log, generazione sessione+JWT) - qui il JWT ritornato viene solo
 * decodificato per estrarne sessionId e ruolo, non viene mai allegato
 * a richieste HTTP: Vaadin usa la sessione nativa (cookie), non un
 * pattern Bearer per ogni frame WebSocket.
 */
@Route("login")
class LoginView(
    private val authService: AuthService,
    private val jwtService: JwtService,
    private val userRepository: UserRepository,
    private val sessionService: SessionService
) : VerticalLayout() {

    init {
        setSizeFull()
        justifyContentMode = FlexComponent.JustifyContentMode.CENTER
        defaultHorizontalComponentAlignment = FlexComponent.Alignment.CENTER
        className = "lbi-login-page"
        val logo = Image("images/logo.png", "LightningBI").apply {
            width = "128px"
            height = "128px"
            style.set("margin-bottom", "12px")
        }


        val usernameField = TextField("Utente").apply {
            width = "280px"
        }
        val passwordField = PasswordField("Password").apply {
            width = "280px"
        }

        val loginButton = Button("Accedi") {
            attemptLogin(usernameField.value, passwordField.value)
        }.apply {
            addThemeVariants(ButtonVariant.LUMO_PRIMARY)
            width = "280px"
        }

        // Invio da tastiera (Enter) su entrambi i campi, non solo sull'ultimo.
        loginButton.addClickShortcut(Key.ENTER)

        val form = VerticalLayout(logo, usernameField, passwordField, loginButton).apply {
            isPadding = true
            isSpacing = true
            className = "lbi-login-form"
            width = "360px"
            alignItems = FlexComponent.Alignment.CENTER
        }

        add(form)
    }

    private fun attemptLogin(username: String, password: String) {
        if (username.isBlank() || password.isBlank()) {
            Notification.show("Inserisci utente e password", 4000, Notification.Position.MIDDLE)
            return
        }

        val request = VaadinServletRequest.getCurrent().httpServletRequest
        val token = authService.login(
            username,
            password,
            request.remoteAddr,
            request.getHeader("User-Agent") ?: ""
        )

        if (token == null) {
            Notification.show("Credenziali non valide", 4000, Notification.Position.MIDDLE)
            return
        }

        val claims = jwtService.validate(token)
        if (claims == null) {
            // Non dovrebbe mai accadere (il token è appena stato generato
            // da AuthService con la stessa chiave), ma se capita è un
            // problema di configurazione, non di credenziali: messaggio
            // diverso per non confondere l'utente con "password sbagliata".
            Notification.show("Errore interno di autenticazione", 5000, Notification.Position.MIDDLE)
            return
        }

        val sessionId = claims["sessionId"] as? String
        val roleName = claims["role"] as? String
        val user = userRepository.findByUsername(username)

        if (sessionId == null || roleName == null || user == null) {
            Notification.show("Errore interno di autenticazione", 5000, Notification.Position.MIDDLE)
            return
        }

        CurrentUserHolder.set(
            AuthenticatedUser(
                userId = user.id,
                username = user.username,
                roleName = roleName,
                sessionId = sessionId,
                codiceDittaAssegnata = user.codiceDittaAssegnata
            )
        )
        SessioneCookie.imposta(sessionId, sessionService.durataSecondi)
        ui.ifPresent { it.navigate(DatasetFiltriView::class.java) }
    }
}