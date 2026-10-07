package com.lightningbi.lightning_engine.config

import com.lightningbi.lightning_engine.repository.RoleRepository
import com.lightningbi.lightning_engine.repository.UserRepository
import com.lightningbi.lightning_engine.service.SessionService
import com.lightningbi.lightning_engine.view.AuthenticatedUser
import com.lightningbi.lightning_engine.view.CurrentUserHolder
import com.lightningbi.lightning_engine.view.DatasetFiltriView
import com.lightningbi.lightning_engine.view.LoginView
import com.lightningbi.lightning_engine.view.SessioneCookie
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.server.ServiceInitEvent
import com.vaadin.flow.server.VaadinServiceInitListener
import org.springframework.stereotype.Component

/**
 * Protegge ogni route Vaadin: a ogni navigazione verifica che ci sia una
 * sessione di lavoro valida.
 *
 * - Utente già nella sessione Vaadin: la sessione su Postgres si ricontrolla
 *   (scadenza, revoca, utente attivo). Se non vale più, si esce.
 * - Sessione Vaadin vuota (riavvio del server, scadenza di Tomcat): si prova a
 *   ricostruirla dal cookie con l'id della sessione di lavoro.
 * - Altrimenti: login.
 *
 * Registrato come VaadinServiceInitListener: gira per OGNI UI, quindi copre
 * ogni nuova @Route senza doverla proteggere una per una.
 */
@Component
class LbiServiceInitListener(
    private val sessionService: SessionService,
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository
) : VaadinServiceInitListener {
    private val log = org.slf4j.LoggerFactory.getLogger(LbiServiceInitListener::class.java)

    override fun serviceInit(event: ServiceInitEvent) {
        event.source.addUIInitListener { uiEvent ->
            uiEvent.ui.addBeforeEnterListener { beforeEnter -> controlla(beforeEnter) }
        }
    }

    private fun controlla(evento: BeforeEnterEvent) {
        val versoLogin = evento.navigationTarget == LoginView::class.java

        val corrente = CurrentUserHolder.get()
        if (corrente != null) {
            if (sessionService.validate(corrente.sessionId) == null) {
                log.info("Sessione {} non più valida: uscita", corrente.sessionId)
                CurrentUserHolder.clear()
                SessioneCookie.cancella()
                if (!versoLogin) evento.forwardTo(LoginView::class.java)
            } else if (versoLogin) {
                evento.forwardTo(DatasetFiltriView::class.java)
            }
            return
        }

        // Sessione Vaadin vuota: rientro dal cookie.
        val idCookie = SessioneCookie.leggi()
        val ripristinato = idCookie?.let { ripristina(it) }
        if (ripristinato != null) {
            CurrentUserHolder.set(ripristinato)
            log.info("Sessione {} ripristinata dal cookie per '{}'", ripristinato.sessionId, ripristinato.username)
            if (versoLogin) evento.forwardTo(DatasetFiltriView::class.java)
            return
        }

        if (idCookie != null) SessioneCookie.cancella()
        if (!versoLogin) {
            log.debug("Forward a login da {}", evento.location.path)
            evento.forwardTo(LoginView::class.java)
        }
    }

    /** L'utente della sessione di lavoro [sessionId], se la sessione è valida. */
    private fun ripristina(sessionId: String): AuthenticatedUser? {
        val sessione = sessionService.validate(sessionId) ?: return null
        val utente = userRepository.findById(sessione.userId) ?: return null
        val ruolo = roleRepository.findById(sessione.roleId) ?: return null
        return AuthenticatedUser(
            userId = utente.id,
            username = utente.username,
            roleName = ruolo.name,
            sessionId = sessione.sessionId,
            codiceDittaAssegnata = utente.codiceDittaAssegnata
        )
    }
}