package com.lightningbi.lightning_engine.view

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.orderedlayout.VerticalLayout

/**
 * Sidebar di navigazione stile TrooperERP/Dynamics 365: gruppi con
 * flyout a sottomenu (hover). Disaccoppiata dalla view che la usa:
 * riceve solo etichette e callback, non conosce le view specifiche
 * né i service applicativi. Se questo componente si rompe, non rompe
 * la logica delle pagine che lo usano.
 *
 * MenuEntry supporta uno stato enabled/disabled: serve alle voci del
 * gruppo "Gestisci" (Verifica sorgente, Sincronizza, ecc.), il cui stato
 * dipende da cosa succede nell'analisi aperta - una sorgente non ancora
 * verificata non può sincronizzare, per esempio. Una entry disabilitata
 * resta visibile ma non cliccabile, con stile visivo attenuato: l'utente
 * vede sempre cosa esiste, capisce perché non può usarlo ora.
 *
 * MenuEntry e MenuGroup possono avere un'icona (VaadinIcon), disegnata a
 * sinistra dell'etichetta. Senza icona la voce resta come prima.
 *
 * MenuGroup supporta uno stato active: indica in quale PAGINA ci si
 * trova (es. "Grafici" attivo quando la route corrente è /charts/...),
 * non quale sottovoce è stata cliccata l'ultima volta - è un concetto di
 * posizione, non di cronologia.
 */
class LbiSidebarMenu : VerticalLayout() {

    data class MenuEntry(
        val label: String,
        val enabled: Boolean = true,
        val icon: VaadinIcon? = null,
        val onClick: () -> Unit
    )
    data class MenuGroup(
        val label: String,
        val entries: List<MenuEntry>,
        val active: Boolean = false,
        val icon: VaadinIcon? = null
    )

    init {
        className = "lbi-sidebar"
        isPadding = false
        isSpacing = false
        width = "260px"
        height = "100%"
    }

    /** Sostituisce interamente il contenuto della sidebar con i gruppi forniti. */
    fun setGroups(groups: List<MenuGroup>) {
        removeAll()
        add(Span("Menu").apply { className = "lbi-sidebar-title" })
        groups.forEach { group -> add(buildFlyoutGroup(group)) }
    }

    private fun buildFlyoutGroup(group: MenuGroup): Div {
        val item = Div().apply {
            className = if (group.active) "flyout-item flyout-item-active" else "flyout-item"
            add(buildLabel(group.label, group.icon))
        }

        val submenu = Div().apply { className = "flyout-submenu" }
        group.entries.forEach { entry ->
            val subEntry = Div(buildLabel(entry.label, entry.icon)).apply {
                className = if (entry.enabled) "flyout-subentry" else "flyout-subentry flyout-subentry-disabled"
                if (entry.enabled) {
                    addClickListener { entry.onClick() }
                }
            }
            submenu.add(subEntry)
        }

        item.add(submenu)
        return item
    }

    /**
     * Etichetta con l'icona (se presente) a sinistra. inline-flex e non
     * flex: il contenitore si comporta come lo Span di prima, quindi non
     * cambia il modo in cui il foglio di stile dispone la voce.
     */
    private fun buildLabel(label: String, icon: VaadinIcon?): Component =
        Span().apply {
            style.set("display", "inline-flex")
            style.set("align-items", "center")
            style.set("gap", "10px")
            if (icon != null) {
                add(Icon(icon).apply {
                    style.set("width", "16px")
                    style.set("height", "16px")
                    style.set("flex-shrink", "0")
                })
            }
            add(Span(label))
        }
}