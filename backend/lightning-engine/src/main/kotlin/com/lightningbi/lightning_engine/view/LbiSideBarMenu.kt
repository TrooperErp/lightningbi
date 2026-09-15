package com.lightningbi.lightning_engine.view

import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.VerticalLayout

/**
 * Sidebar di navigazione stile TrooperERP/Dynamics 365: gruppi con
 * flyout a sottomenu (hover). Disaccoppiata dalla view che la usa:
 * riceve solo etichette e callback, non conosce AssociativeExplorerView
 * né i service applicativi. Se questo componente si rompe, non rompe
 * la logica dell'analisi associativa.
 *
 * MenuEntry supporta uno stato enabled/disabled: serve alle voci del
 * gruppo "Gestisci" (Verifica sorgente, Sincronizza, ecc.), il cui stato
 * dipende da cosa succede nell'analisi aperta - una sorgente non ancora
 * verificata non può sincronizzare, per esempio. Una entry disabilitata
 * resta visibile ma non cliccabile, con stile visivo attenuato: l'utente
 * vede sempre cosa esiste, capisce perché non può usarlo ora.
 */
class LbiSidebarMenu : VerticalLayout() {

    data class MenuEntry(
        val label: String,
        val enabled: Boolean = true,
        val onClick: () -> Unit
    )
    data class MenuGroup(val label: String, val entries: List<MenuEntry>)

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
            className = "flyout-item"
            add(Span(group.label))
        }

        val submenu = Div().apply { className = "flyout-submenu" }
        group.entries.forEach { entry ->
            val subEntry = Div(Span(entry.label)).apply {
                className = if (entry.enabled) "flyout-subentry" else "flyout-subentry flyout-subentry-disabled"
                if (entry.enabled) {
                    addClickListener { entry.onClick() }
                }
                // Le entry disabilitate non hanno click listener: anche
                // se qualcosa forzasse un click via JS, non farebbero nulla.
            }
            submenu.add(subEntry)
        }

        item.add(submenu)
        return item
    }
}