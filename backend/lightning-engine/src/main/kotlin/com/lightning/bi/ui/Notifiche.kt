package com.lightning.piattaforma.ui

import com.vaadin.flow.component.notification.Notification
import org.slf4j.Logger
import java.util.concurrent.ThreadLocalRandom

/**
 * Errori imprevisti senza dettagli tecnici: all'utente un testo breve con un codice,
 * nel log l'eccezione completa (SQL compreso) con lo stesso codice.
 */
object Notifiche {

    /** Scrive [e] nel log e restituisce il testo da mostrare, es. "Impossibile salvare. Codice E-7F3A". */
    fun testo(log: Logger, e: Throwable, azione: String): String {
        val codice = "E-%04X".format(ThreadLocalRandom.current().nextInt(0x10000))
        log.warn("[{}] Impossibile {}", codice, azione, e)
        return "Impossibile $azione. Codice $codice"
    }

    /** Come [testo], mostrato in una notifica d'errore. */
    fun errore(log: Logger, e: Throwable, azione: String) {
        Notification.show(testo(log, e, azione), 6000, Notification.Position.MIDDLE)
            .addThemeName("error")
    }
}