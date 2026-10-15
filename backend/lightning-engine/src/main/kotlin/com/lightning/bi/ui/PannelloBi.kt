package com.lightning.bi.ui

import com.lightning.bi.model.Area
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import java.util.UUID

/**
 * Pannello a sinistra della BI: l'elenco dei dataset; quello aperto mostra sotto i suoi fogli.
 *
 * @param fogli i fogli del dataset aperto (vuoto se nessun dataset è aperto)
 */
class PannelloBi(
    aree: List<Area>,
    areaAttiva: UUID?,
    fogli: List<Foglio>,
    foglioAttivo: String?
) : Div() {

    init {
        className = "lt-elenco"
        add(Span("DATASET").apply { className = "lt-elenco-titolo" })
        if (aree.isEmpty()) add(Span("Nessun dataset").apply { className = "lt-vuoto lt-elenco-nota" })

        aree.forEach { area ->
            val aperta = area.id == areaAttiva
            add(voce(area.nome, VaadinIcon.DATABASE, RotteBi.filtri(area.id), "lt-elenco-voce" + if (aperta) " lt-aperta" else ""))
            if (aperta) {
                fogli.forEach { f ->
                    val classe = "lt-elenco-voce lt-elenco-foglio" + if (f.id == foglioAttivo) " lt-attiva" else ""
                    add(voce(f.nome, icona(f.tipo), f.rotta, classe, f.tipo.etichetta))
                }
            }
        }
    }

    private fun voce(testo: String, icona: VaadinIcon, rotta: String, classe: String, tag: String = ""): Anchor =
        Anchor(rotta).apply {
            className = classe
            add(Icon(icona), Span(testo).apply { className = "lt-elenco-testo" })
            if (tag.isNotEmpty()) add(Span(tag).apply { className = "lt-elenco-tag" })
            // Navigazione interna (senza ricaricare la pagina)
            element.addEventListener("click") { UI.getCurrent().navigate(rotta) }.preventDefault()
        }

    private fun icona(tipo: TipoFoglio) = when (tipo) {
        TipoFoglio.FILTRI -> VaadinIcon.FILTER
        TipoFoglio.PIVOT -> VaadinIcon.TABLE
        TipoFoglio.GRAFICI -> VaadinIcon.PIE_BAR_CHART
    }
}