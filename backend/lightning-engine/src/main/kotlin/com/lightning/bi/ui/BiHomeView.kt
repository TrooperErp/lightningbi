package com.lightning.bi.ui

import com.lightning.bi.service.AccessoDatasetService
import com.lightning.piattaforma.ui.LightningLayout
import com.lightning.piattaforma.ui.PaginaLightning
import com.vaadin.flow.component.AttachEvent
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H1
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.router.Route
import com.vaadin.flow.router.RouterLink


/**
 * Pagina iniziale della BI dentro la shell di Lightning: i dataset che l'utente può aprire.
 * Per ora ogni dataset apre la pagina Filtri vecchia; si collegherà a quella nuova quando sarà pronta.
 */
@Route(value = "bi", layout = LightningLayout::class)
class BiHomeView(
    private val accessoDatasetService: AccessoDatasetService
) : Div(), PaginaLightning {

    override val moduloId = "bi"

    private val aree = accessoDatasetService.areeVisibili().sortedBy { it.nome.lowercase() }

    init {
        className = "lt-pagina"
        add(H1("Dataset").apply { className = "lt-titolo-pagina" })

        if (aree.isEmpty()) {
            add(Span("Non hai ancora accesso a nessun dataset.").apply { className = "lt-vuoto" })
        } else {
            val griglia = Div().apply { className = "lt-griglia-schede" }
            aree.forEach { area ->
                griglia.add(
                    com.vaadin.flow.component.html.Anchor(RotteBi.filtri(area.id)).apply {
                        className = "lt-scheda"
                        add(
                            Icon(VaadinIcon.DATABASE).apply { className = "lt-scheda-icona" },
                            Span(area.nome).apply { className = "lt-scheda-titolo" }
                        )
                    }
                )
            }
            add(griglia)
        }
    }

    override fun percorso() = listOf("Dataset")

    override fun pannello(): Component = PannelloBi(aree, null, emptyList(), null)

    override fun onAttach(attachEvent: AttachEvent) {
        super.onAttach(attachEvent)
        findAncestor(LightningLayout::class.java)?.impostaStato("${aree.size} dataset disponibili")
    }
}