package com.lightningbi.lightning_engine.view

import com.vaadin.flow.server.VaadinServletRequest
import com.vaadin.flow.server.VaadinServletResponse
import jakarta.servlet.http.Cookie

/**
 * Cookie con l'id della sessione di lavoro (quella su Postgres). Serve a
 * rientrare dopo un riavvio del server o la scadenza della sessione Vaadin,
 * senza rifare il login finché la sessione di lavoro è valida.
 *
 * Contiene solo l'id: chi lo legge deve sempre controllarlo su Postgres
 * (scadenza, revoca, utente attivo).
 */
object SessioneCookie {
    private const val NOME = "lbi-sessione"

    fun imposta(sessionId: String, durataSecondi: Int) {
        val richiesta = VaadinServletRequest.getCurrent()?.httpServletRequest ?: return
        val risposta = VaadinServletResponse.getCurrent()?.httpServletResponse ?: return
        risposta.addCookie(crea(sessionId, durataSecondi, richiesta.isSecure, richiesta.contextPath))
    }

    /** L'id di sessione presente nel cookie della richiesta corrente, se c'è. */
    fun leggi(): String? {
        val richiesta = VaadinServletRequest.getCurrent()?.httpServletRequest ?: return null
        return richiesta.cookies?.firstOrNull { it.name == NOME }?.value?.takeIf { it.isNotBlank() }
    }

    fun cancella() {
        val richiesta = VaadinServletRequest.getCurrent()?.httpServletRequest ?: return
        val risposta = VaadinServletResponse.getCurrent()?.httpServletResponse ?: return
        risposta.addCookie(crea("", 0, richiesta.isSecure, richiesta.contextPath))
    }

    private fun crea(valore: String, durataSecondi: Int, sicuro: Boolean, contextPath: String): Cookie =
        Cookie(NOME, valore).apply {
            isHttpOnly = true
            secure = sicuro
            path = contextPath.ifEmpty { "/" }
            maxAge = durataSecondi
            setAttribute("SameSite", "Lax")
        }
}