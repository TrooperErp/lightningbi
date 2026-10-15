package com.lightning.bi.ui

import com.lightning.bi.etl.EtlOrchestrator
import com.lightning.bi.model.Area
import com.lightning.bi.repository.AreaSourceRepository
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.progressbar.ProgressBar

/**
 * Azioni dell'admin su un dataset, come il Reload di Qlik: Modello dati, Aggiorna indice,
 * Sincronizza dati. Il lavoro gira in un thread a parte con una finestra di avanzamento;
 * alla fine [dopo] ricalcola la pagina.
 */
class AzioniDataset(
    private val area: Area,
    private val etlOrchestrator: EtlOrchestrator,
    private val areaSourceRepository: AreaSourceRepository,
    private val dopo: () -> Unit
) : MenuBar() {

    init {
        className = "lt-menu-foglio"
        val radice = addItem(Icon(VaadinIcon.COG))
        radice.element.setAttribute("aria-label", "Azioni sul dataset")
        radice.element.setAttribute("title", "Dataset: modello e dati")
        radice.subMenu.addItem("Modello dati") { UI.getCurrent().navigate("nuovo-dataset/${area.id}") }
        radice.subMenu.addItem("Aggiorna indice") { aggiornaIndice() }
        radice.subMenu.addItem("Sincronizza dati") { sincronizza() }
    }

    private fun sincronizza() {
        val sorgenti = areaSourceRepository.findByArea(area.id)
        if (sorgenti.isEmpty()) {
            Notification.show("Il dataset non ha una sorgente: salvalo da Modello dati", 5000, Notification.Position.MIDDLE)
            return
        }
        lavoro("Sincronizzazione di ${area.nome}", "sync-dataset-${area.id}", "Sincronizzazione completata") { avanza ->
            sorgenti.forEach { sorgente -> etlOrchestrator.runForArea(area.id, sorgente, avanza) }
        }
    }

    private fun aggiornaIndice() {
        lavoro("Aggiorna indice di ${area.nome}", "indice-dataset-${area.id}", "Indice aggiornato") { avanza ->
            etlOrchestrator.aggiornaIndice(area.id, avanza)
        }
    }

    /** Esegue [blocco] in un thread a parte, con una finestra che mostra l'avanzamento. */
    private fun lavoro(titolo: String, nomeThread: String, fatto: String, blocco: ((String) -> Unit) -> Unit) {
        val ui = UI.getCurrent() ?: return
        val avanzamento = Span("Avvio...")
        val dialog = Dialog().apply {
            headerTitle = titolo
            width = "480px"
            isCloseOnEsc = false
            isCloseOnOutsideClick = false
            add(Div(ProgressBar().apply { isIndeterminate = true }, avanzamento).apply { className = "lt-finestra" })
        }
        dialog.open()
        val inizio = System.currentTimeMillis()
        Thread {
            try {
                blocco { frase -> ui.access { avanzamento.text = frase } }
                val secondi = (System.currentTimeMillis() - inizio) / 1000
                ui.access {
                    dialog.close()
                    Notification.show("$fatto in $secondi s", 5000, Notification.Position.BOTTOM_END)
                    dopo()
                }
            } catch (e: Exception) {
                ui.access {
                    dialog.close()
                    Notification.show("$titolo fallita: ${e.message ?: e::class.simpleName}", 8000, Notification.Position.MIDDLE)
                }
            }
        }.apply {
            isDaemon = true
            name = nomeThread
        }.start()
    }
}