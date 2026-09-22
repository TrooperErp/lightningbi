package com.lightningbi.lightning_engine.config

import com.lightningbi.lightning_engine.view.CurrentUserHolder
import com.lightningbi.lightning_engine.view.LoginView
import com.vaadin.flow.server.ServiceInitEvent
import com.vaadin.flow.server.VaadinServiceInitListener
import org.springframework.stereotype.Component

/**
 * Protegge ogni route Vaadin dell'applicazione: se non c'è un utente
 * autenticato nella sessione corrente (CurrentUserHolder.get() == null),
 * qualunque navigazione viene deviata su /login, tranne verso /login
 * stessa (altrimenti loop infinito).
 *
 * Registrato come VaadinServiceInitListener (non un semplice
 * BeforeEnterListener su una singola view): questo gira per OGNI UI
 * dell'applicazione, quindi copre automaticamente ogni nuova @Route
 * aggiunta in futuro senza doverla proteggere una per una.
 */
@Component
class LbiServiceInitListener : VaadinServiceInitListener {
    override fun serviceInit(event: ServiceInitEvent) {
        event.source.addUIInitListener { uiEvent ->
            uiEvent.ui.addBeforeEnterListener { beforeEnterEvent ->
                val isLoginTarget = beforeEnterEvent.navigationTarget == LoginView::class.java
                if (CurrentUserHolder.get() == null && !isLoginTarget) {
                    beforeEnterEvent.forwardTo(LoginView::class.java)
                }
            }
        }
    }
}