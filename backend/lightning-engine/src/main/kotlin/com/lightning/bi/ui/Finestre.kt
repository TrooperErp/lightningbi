package com.lightning.bi.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.textfield.TextField

/** Le finestre comuni delle pagine nuove: conferma e richiesta di un nome. */
object Finestre {

    /**
     * Una finestra con [contenuto], Annulla e un pulsante di conferma. [azione] restituisce
     * false se la finestra deve restare aperta (es. dato non valido).
     */
    fun apri(titolo: String, contenuto: Component, conferma: String, pericolo: Boolean = false, azione: () -> Boolean) {
        val dialog = Dialog().apply {
            headerTitle = titolo
            width = "460px"
        }
        dialog.add(Div(contenuto).apply { className = "lt-finestra" })
        dialog.footer.add(
            Button("Annulla") { dialog.close() },
            Button(conferma) { if (azione()) dialog.close() }.apply {
                addThemeName("primary")
                if (pericolo) addThemeName("error")
            }
        )
        dialog.open()
    }

    fun chiediNome(titolo: String, iniziale: String, azione: (String) -> Unit) {
        val campo = TextField("Nome").apply {
            setWidthFull()
            value = iniziale
        }
        apri(titolo, campo, "Conferma") {
            val nome = campo.value.orEmpty().trim()
            if (nome.isEmpty()) {
                Notification.show("Il nome è obbligatorio", 3000, Notification.Position.MIDDLE)
                false
            } else {
                azione(nome)
                true
            }
        }
        campo.focus()
    }
}