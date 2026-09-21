package com.lightningbi.lightning_engine.view

import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Image
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout

/**
 * Shell condivisa da tutte le pagine dell'app: banda nera in alto (logo,
 * titolo, tema) e sidebar blu a sinistra, identiche ovunque.
 *
 * updateMenuGroups() permette a chi la usa di rinfrescare la sidebar
 * quando cambia lo stato che decide quali voci sono enabled/active (es.
 * dopo aver verificato una sorgente), senza dover ricostruire l'intera
 * shell: solo la sidebar viene ridisegnata.
 */
class LbiAppShell(
    initialMenuGroups: List<LbiSidebarMenu.MenuGroup>,
    centerContent: Component
) : VerticalLayout() {

    private var isDark = false
    private val sidebar = LbiSidebarMenu()

    init {
        className = "lbi-app"
        width = "100%"
        isPadding = false
        isSpacing = false

        val logoImage = Image("images/logo.png", "LightningBI").apply { className = "lbi-logo-img" }
        val logoSpan = Span("LightningBI").apply { className = "lbi-logo" }
        val logoContainer = HorizontalLayout(logoImage, logoSpan).apply {
            className = "lbi-logo-container"
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            isSpacing = true
        }

        val themeToggle = Button("Dark").apply {
            className = "lbi-theme-toggle"
            addClickListener {
                isDark = !isDark
                element.executeJs(
                    "document.documentElement.setAttribute('theme', \$0)",
                    if (isDark) "dark" else ""
                )
                text = if (isDark) "Light" else "Dark"
            }
        }

        val topMenuBar = HorizontalLayout(logoContainer, themeToggle).apply {
            className = "lbi-topmenu"
            justifyContentMode = FlexComponent.JustifyContentMode.BETWEEN
            defaultVerticalComponentAlignment = FlexComponent.Alignment.CENTER
            setWidthFull()
        }

        sidebar.setGroups(initialMenuGroups)

        val body = HorizontalLayout(sidebar, centerContent).apply {
            className = "lbi-body"
            width = "100%"
            isPadding = false
            isSpacing = true
            setFlexGrow(0.0, sidebar)
            setFlexGrow(1.0, centerContent)
        }

        add(topMenuBar, body)
        setFlexGrow(0.0, topMenuBar)
        setFlexGrow(1.0, body)
    }

    /** Ridisegna solo la sidebar con i gruppi aggiornati, lasciando il resto della shell intatto. */
    fun updateMenuGroups(groups: List<LbiSidebarMenu.MenuGroup>) {
        sidebar.setGroups(groups)
    }
}