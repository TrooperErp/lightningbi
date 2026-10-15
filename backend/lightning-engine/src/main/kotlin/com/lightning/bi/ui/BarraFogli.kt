package com.lightning.bi.ui

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import java.util.UUID

/**
 * Le schede dei fogli in cima a una pagina della BI, con "+" per un nuovo foglio pivot.
 * A destra c'è posto per le azioni del foglio (es. Esporta): [azioni].
 */
class BarraFogli(
    private val areaId: UUID,
    fogli: List<Foglio>,
    foglioAttivo: String?,
    private val fogliBi: FogliBi
) : Div() {

    private val azioni = Div().apply { className = "lt-fogli-azioni" }

    init {
        className = "lt-barra-fogli"
        element.setAttribute("role", "tablist")
        element.setAttribute("aria-label", "Fogli")
        fogli.forEach { f ->
            add(Button(f.nome) { UI.getCurrent().navigate(f.rotta) }.apply {
                className = "lt-scheda-foglio"
                element.setAttribute("role", "tab")
                element.setAttribute("aria-selected", (f.id == foglioAttivo).toString())
                classNames.set("lt-attiva", f.id == foglioAttivo)
            })
        }
        add(Button(Icon(VaadinIcon.PLUS)) { nuovo() }.apply {
            className = "lt-scheda-foglio lt-scheda-nuova"
            element.setAttribute("aria-label", "Nuovo foglio")
            setTooltipText("Nuovo foglio pivot")
        })
        add(Div().apply { className = "lt-spazio" }, azioni)
    }

    fun aggiungiAzione(componente: Component) = azioni.add(componente)

    private fun nuovo() {
        fogliBi.nuovoFoglio(areaId)?.let { UI.getCurrent().navigate(it.rotta) }
    }
}