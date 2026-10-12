package com.lightning.piattaforma.ui

import com.lightning.piattaforma.model.Azienda
import com.lightning.piattaforma.service.AdminGuard
import com.lightning.piattaforma.service.AuthService
import com.lightning.piattaforma.view.AdminView
import com.lightning.piattaforma.view.CambiaPasswordDialog
import com.lightning.piattaforma.view.CurrentUserHolder
import com.lightning.piattaforma.view.LoginView
import com.lightning.piattaforma.view.SessioneCookie
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.HasElement
import com.vaadin.flow.component.Html
import com.vaadin.flow.component.Key
import com.vaadin.flow.component.KeyModifier
import com.vaadin.flow.component.Shortcuts
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.html.Aside
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Footer
import com.vaadin.flow.component.html.Header
import com.vaadin.flow.component.html.Main
import com.vaadin.flow.component.html.Nav
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.page.ColorScheme
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.RouterLayout
import com.vaadin.flow.server.VaadinServletRequest
import com.vaadin.flow.server.VaadinSession
import org.springframework.beans.factory.annotation.Value

/**
 * Una pagina che vive dentro la shell di Lightning. La shell, quando la mostra, legge da qui
 * il modulo da evidenziare, il percorso in alto e il contenuto del pannello a sinistra.
 */
interface PaginaLightning {
    val moduloId: String
    fun percorso(): List<String> = emptyList()
    fun pannello(): Component? = null
}

/**
 * La shell comune a tutti i moduli (come da mockup): barra in alto, barra dei moduli a icone,
 * pannello del modulo richiudibile, contenuto, barra di stato.
 *
 * Le pagine nuove la usano con `@Route(..., layout = LightningLayout::class)`. Per scrivere nella
 * barra di stato o nell'indicatore dei dati: `findAncestor(LightningLayout::class.java)`.
 */
class LightningLayout(
    moduli: List<ModuloLightning>,
    private val adminGuard: AdminGuard,
    private val authService: AuthService,
    /** Etichetta dell'ambiente in alto (es. PRODUZIONE); vuota = non si mostra. */
    @Value("\${lightning.ambiente:}") private val ambiente: String
) : Div(), RouterLayout {

    private val moduliOrdinati: List<ModuloLightning> =
        (moduli + ModuloInArrivo.entries).distinctBy { it.id }.sortedBy { it.ordine }

    private val badgeModulo = Span().apply { className = "lt-badge lt-badge-accento" }
    private val percorso = Nav().apply { className = "lt-percorso"; element.setAttribute("aria-label", "Percorso") }
    private val statoDati = Span().apply { className = "lt-stato-dati"; isVisible = false }
    private val pulsantiModulo = mutableMapOf<String, Button>()
    private val corpoPannello = Div().apply { className = "lt-pannello-corpo" }
    private val pannello = Aside().apply { className = "lt-pannello"; element.setAttribute("aria-label", "Navigazione del modulo") }
    private val contenuto = Main().apply { className = "lt-contenuto" }
    private val statoPagina = Div().apply { className = "lt-stato-pagina" }

    init {
        className = "lt-shell"
        add(barraAlta(), Div(barraModuli(), costruisciPannello(), contenuto).apply { className = "lt-corpo" }, barraStato())
    }

    // ================= Struttura =================

    private fun barraAlta(): Component {
        val logo = Div(
            Html("""<svg width="22" height="22" viewBox="0 0 24 24" aria-hidden="true"><rect width="24" height="24" rx="6" fill="currentColor"></rect><path d="M13.5 4 7 13.5h4.5L10 20l7-10h-4.6L13.5 4Z" fill="#fff"></path></svg>"""),
            Span("Lightning")
        ).apply { className = "lt-logo" }

        val ambienteBadge = Span(ambiente.uppercase()).apply {
            className = "lt-badge lt-badge-avviso"
            isVisible = ambiente.isNotBlank()
        }

        val ricerca = TextField().apply {
            className = "lt-ricerca"
            placeholder = "Cerca dataset, fogli, campi, valori…"
            prefixComponent = Icon(VaadinIcon.SEARCH)
            suffixComponent = Span("Ctrl K").apply { className = "lt-tasto" }
            element.setAttribute("aria-label", "Cerca")
        }
        Shortcuts.addShortcutListener(this, com.vaadin.flow.component.ShortcutEventListener { _ -> ricerca.focus() }, Key.KEY_K, KeyModifier.CONTROL)

        val tema = Button(Icon(VaadinIcon.ADJUST)) { cambiaTema() }.apply {
            className = "lt-icona-btn"
            element.setAttribute("aria-label", "Cambia tema chiaro / scuro")
            setTooltipText("Tema chiaro / scuro")
        }

        return Header(
            logo, badgeModulo, percorso, ambienteBadge,
            Div(ricerca).apply { className = "lt-ricerca-box" },
            statoDati, tema, menuUtente()
        ).apply { className = "lt-barra-alta" }
    }

    private fun menuUtente(): Component {
        val utente = CurrentUserHolder.get()
        val sigla = utente?.username?.take(2)?.uppercase() ?: "?"
        val ruolo = if (adminGuard.isAdmin()) "Admin" else utente?.roleName?.lowercase()?.replaceFirstChar { it.uppercase() } ?: ""
        val menu = MenuBar().apply { className = "lt-menu-utente" }
        val radice = menu.addItem(
            Div(Span(sigla).apply { className = "lt-avatar" }, Span("${utente?.username ?: ""} · $ruolo")).apply { className = "lt-utente" }
        )
        radice.subMenu.addItem("Cambia password") { CambiaPasswordDialog(authService).open() }
        radice.subMenu.addItem("Esci") { esci() }
        return menu
    }

    private fun barraModuli(): Component {
        val nav = Nav().apply { className = "lt-barra-moduli"; element.setAttribute("aria-label", "Moduli") }
        moduliOrdinati.forEach { m ->
            val pulsante = Button(Icon(m.icona)).apply {
                className = "lt-modulo"
                element.setAttribute("aria-label", if (m.attivo) m.nome else "${m.nome} (in arrivo)")
                setTooltipText(if (m.attivo) m.nome else "${m.nome} · in arrivo")
                isEnabled = m.attivo
                addClickListener { UI.getCurrent().navigate(m.rotta) }
            }
            pulsantiModulo[m.id] = pulsante
            nav.add(pulsante)
        }
        nav.add(Div().apply { className = "lt-spazio" })
        if (adminGuard.isAdmin()) {
            nav.add(Button(Icon(VaadinIcon.COG)).apply {
                className = "lt-modulo"
                element.setAttribute("aria-label", "Amministrazione")
                setTooltipText("Amministrazione")
                addClickListener { UI.getCurrent().navigate(AdminView::class.java) }
            })
        }
        return nav
    }

    private fun costruisciPannello(): Component {
        val chiudi = Button("«").apply {
            className = "lt-icona-btn lt-pannello-chiudi"
            element.setAttribute("aria-label", "Chiudi o apri il pannello")
            addClickListener {
                val chiuso = !pannello.classNames.contains("lt-chiuso")
                pannello.classNames.set("lt-chiuso", chiuso)
                text = if (chiuso) "»" else "«"
            }
        }
        pannello.add(chiudi, corpoPannello)
        return pannello
    }

    private fun barraStato(): Component {
        val utente = CurrentUserHolder.get()
        val ditta = utente?.codiceDittaAssegnata?.let { Azienda.fromCodice(it)?.label ?: "#$it" }
            ?: if (adminGuard.isAdmin()) "tutte (admin)" else "—"
        return Footer(
            statoPagina,
            Div().apply { className = "lt-spazio" },
            Span("Ditta: $ditta")
        ).apply { className = "lt-barra-stato" }
    }

    // ================= Contenuto =================

    override fun showRouterLayoutContent(content: HasElement) {
        contenuto.element.removeAllChildren()
        contenuto.element.appendChild(content.element)
        val pagina = content as? PaginaLightning
        evidenziaModulo(pagina?.moduloId)
        impostaPercorso(pagina?.percorso() ?: emptyList())
        impostaPannello(pagina?.pannello())
        impostaStato()
    }

    private fun evidenziaModulo(id: String?) {
        pulsantiModulo.forEach { (mid, b) -> b.classNames.set("lt-attivo", mid == id) }
        val modulo = moduliOrdinati.firstOrNull { it.id == id }
        badgeModulo.text = modulo?.nome ?: ""
        badgeModulo.isVisible = modulo != null
    }

    // ================= API per le pagine =================

    fun impostaPercorso(voci: List<String>) {
        percorso.removeAll()
        voci.forEachIndexed { i, voce ->
            if (i > 0) percorso.add(Span("›").apply { element.setAttribute("aria-hidden", "true") })
            percorso.add(Span(voce).apply { if (i == voci.lastIndex) className = "lt-percorso-ultimo" })
        }
    }

    /** Il contenuto del pannello a sinistra; null = pannello nascosto. */
    fun impostaPannello(componente: Component?) {
        corpoPannello.removeAll()
        if (componente != null) corpoPannello.add(componente)
        pannello.isVisible = componente != null
    }

    /** Le voci a sinistra nella barra di stato (es. "Pivot 182 ms", "4,1 M righe"). */
    fun impostaStato(vararg voci: String) {
        statoPagina.removeAll()
        voci.forEach { statoPagina.add(Span(it)) }
    }

    /** L'indicatore dei dati in alto (es. "Dati al 12/10 03:12"); null = nascosto. */
    fun impostaStatoDati(testo: String?, ok: Boolean = true) {
        statoDati.text = testo ?: ""
        statoDati.isVisible = testo != null
        statoDati.classNames.set("lt-errore", !ok)
    }

    // ================= Tema e uscita =================

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        applicaTema(attachEvent.ui, temaScuro())
    }

    private fun temaScuro(): Boolean = VaadinSession.getCurrent()?.getAttribute(CHIAVE_TEMA) == true

    private fun cambiaTema() {
        val scuro = !temaScuro()
        VaadinSession.getCurrent()?.setAttribute(CHIAVE_TEMA, scuro)
        UI.getCurrent()?.let { applicaTema(it, scuro) }
    }

    private fun applicaTema(ui: UI, scuro: Boolean) {
        ui.page.setColorScheme(if (scuro) ColorScheme.Value.DARK else ColorScheme.Value.LIGHT)
    }

    private fun esci() {
        val utente = CurrentUserHolder.get()
        if (utente != null) {
            val richiesta = VaadinServletRequest.getCurrent().httpServletRequest
            authService.logout(utente.sessionId, utente.userId, richiesta.remoteAddr)
            CurrentUserHolder.clear()
            SessioneCookie.cancella()
        }
        UI.getCurrent().navigate(LoginView::class.java)
    }

    private companion object {
        const val CHIAVE_TEMA = "lt-tema-scuro"
    }
}