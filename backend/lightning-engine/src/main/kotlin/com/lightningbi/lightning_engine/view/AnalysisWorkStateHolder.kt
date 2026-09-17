package com.lightningbi.lightning_engine.view

import com.vaadin.flow.server.VaadinSession

/**
 * Punto unico di lettura/scrittura dello stato di lavoro nella sessione
 * Vaadin. Una sola area alla volta: se l'utente lavora su più analisi in
 * schede diverse dello stesso browser, condividono la stessa sessione e
 * quindi lo stesso slot - è un limite accettato, non il caso d'uso
 * principale.
 */
object AnalysisWorkStateHolder {
    private const val SESSION_KEY = "lbi-analysis-work-state"

    fun save(state: AnalysisWorkState) {
        VaadinSession.getCurrent()?.setAttribute(SESSION_KEY, state)
    }

    fun read(): AnalysisWorkState? =
        VaadinSession.getCurrent()?.getAttribute(SESSION_KEY) as? AnalysisWorkState

    fun clear() {
        VaadinSession.getCurrent()?.setAttribute(SESSION_KEY, null)
    }
}