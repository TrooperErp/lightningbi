// FILE: src/main/kotlin/com/lightningbi/lightning_engine/view/CronologiaSelezioni.kt
package com.lightningbi.lightning_engine.view

import com.vaadin.flow.server.VaadinSession
import java.util.UUID

/**
 * Cronologia delle selezioni, come Indietro / Avanti di Qlik: per utente (sessione
 * del browser) e per dataset. Le pagine Dataset e Analisi la condividono, così come
 * condividono le selezioni.
 *
 * Chi cambia le selezioni chiama [registra] con lo stato PRIMA del cambio; una
 * selezione nuova svuota l'Avanti. [indietro] e [avanti] restituiscono lo stato da
 * applicare (senza registrarlo di nuovo).
 *
 * Va usata nel thread della pagina (sta nella sessione Vaadin).
 */
object CronologiaSelezioni {
    private const val CHIAVE = "lbi-cronologia-selezioni"
    private const val MAX_PASSI = 50

    private class Storia {
        val indietro = ArrayDeque<Map<UUID, Set<Long>>>()
        val avanti = ArrayDeque<Map<UUID, Set<Long>>>()
    }

    @Suppress("UNCHECKED_CAST")
    private fun storie(): MutableMap<UUID, Storia> {
        val sessione = VaadinSession.getCurrent() ?: return mutableMapOf()
        return sessione.getAttribute(CHIAVE) as? MutableMap<UUID, Storia>
            ?: mutableMapOf<UUID, Storia>().also { sessione.setAttribute(CHIAVE, it) }
    }

    private fun storia(areaId: UUID): Storia = storie().getOrPut(areaId) { Storia() }

    /** Salva lo stato [prima] di un cambio di selezione. Uno stato uguale all'ultimo non si ripete. */
    fun registra(areaId: UUID, prima: Map<UUID, Set<Long>>) {
        val s = storia(areaId)
        if (s.indietro.lastOrNull() != prima) {
            s.indietro.addLast(prima)
            while (s.indietro.size > MAX_PASSI) s.indietro.removeFirst()
        }
        s.avanti.clear()
    }

    /** Lo stato precedente da applicare, o null se non ce n'è. [attuale] passa nell'Avanti. */
    fun indietro(areaId: UUID, attuale: Map<UUID, Set<Long>>): Map<UUID, Set<Long>>? {
        val s = storia(areaId)
        val precedente = s.indietro.removeLastOrNull() ?: return null
        s.avanti.addLast(attuale)
        return precedente
    }

    /** Lo stato successivo da applicare, o null se non ce n'è. [attuale] torna nell'Indietro. */
    fun avanti(areaId: UUID, attuale: Map<UUID, Set<Long>>): Map<UUID, Set<Long>>? {
        val s = storia(areaId)
        val successivo = s.avanti.removeLastOrNull() ?: return null
        s.indietro.addLast(attuale)
        return successivo
    }

    fun puoIndietro(areaId: UUID): Boolean = storia(areaId).indietro.isNotEmpty()

    fun puoAvanti(areaId: UUID): Boolean = storia(areaId).avanti.isNotEmpty()
}