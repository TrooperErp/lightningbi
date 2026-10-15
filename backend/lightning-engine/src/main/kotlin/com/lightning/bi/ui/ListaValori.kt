package com.lightning.bi.ui

import com.lightning.bi.service.FiltriService
import com.lightning.bi.service.StatoValore
import com.lightning.bi.service.ValoreFiltro
import com.lightning.piattaforma.service.Cronometro
import com.vaadin.flow.component.contextmenu.ContextMenu
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.NativeButton
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.value.ValueChangeMode
import java.util.UUID
import com.lightning.bi.motore.Motore

/**
 * L'elenco dei valori di un campo, come il list box di Qlik: legge solo le righe visibili
 * (regge milioni di valori), colori degli stati, ricerca in testa. Un clic aggiunge o toglie.
 *
 * Con [stato]: menu ⋮ (Seleziona tutti, Togli la selezione) e, con il tasto destro su un
 * valore, "Seleziona tutti tranne questo".
 */
class ListaValori(
    private val motore: Motore,
    private val areaId: UUID,
    private val dimensioneId: UUID,
    private val selezioni: () -> Map<UUID, Set<Long>>,
    private val stato: StatoSelezioni?,
    private val alClic: (ValoreFiltro) -> Unit
) : Div() {

    private val ricerca = TextField().apply {
        className = "lt-lista-cerca"
        placeholder = "Cerca valori"
        isClearButtonVisible = true
        valueChangeMode = ValueChangeMode.LAZY
        valueChangeTimeout = 300
        element.setAttribute("aria-label", "Cerca valori")
    }

    private val griglia = Grid<ValoreFiltro>().apply {
        className = "lt-lista-griglia"
        height = "220px"
        setPartNameGenerator { v ->
            when (v.stato) {
                StatoValore.SELEZIONATO -> "lt-val-sel"
                StatoValore.POSSIBILE -> "lt-val-pos"
                StatoValore.ESCLUSO -> "lt-val-esc"
            }
        }
        addColumn { it.etichetta }.setFlexGrow(1)
    }

    init {
        className = "lt-lista"
        griglia.setItems(
            DataProvider.fromCallbacks<ValoreFiltro>(
                { q -> Cronometro.clic("lista valori") { motore.valori(areaId, selezioni(), dimensioneId, ricerca.value, q.offset, q.limit) }.stream() },
                { _ -> Cronometro.clic("lista conta") { motore.numeroValori(areaId, selezioni(), dimensioneId, ricerca.value) } }
            )
        )
        griglia.addItemClickListener { alClic(it.item) }
        ricerca.addValueChangeListener { griglia.dataProvider.refreshAll() }

        val testa = Div(ricerca).apply { className = "lt-lista-testa" }
        stato?.let { st ->
            // ⋮ accanto alla ricerca
            val menu = NativeButton().apply {
                className = "lt-campi-azioni"
                add(Icon(VaadinIcon.ELLIPSIS_DOTS_V))
                element.setAttribute("aria-label", "Azioni sui valori")
            }
            ContextMenu(menu).apply {
                isOpenOnClick = true
                addItem("Seleziona tutti i possibili") { avvisa(st.tutti(dimensioneId)) }
                addItem("Togli la selezione del campo") { avvisa(st.imposta(dimensioneId, emptySet())) }
            }
            testa.add(menu)

            // tasto destro su un valore
            griglia.addContextMenu().apply {
                setDynamicContentHandler { valore ->
                    removeAll()
                    if (valore != null) {
                        addItem("Seleziona tutti tranne «${valore.etichetta}»") { avvisa(st.tutti(dimensioneId, valore.id)) }
                    }
                    addItem("Seleziona tutti i possibili") { avvisa(st.tutti(dimensioneId)) }
                    addItem("Togli la selezione del campo") { avvisa(st.imposta(dimensioneId, emptySet())) }
                    true
                }
            }
        }
        add(testa, griglia)
    }

    fun aggiorna() = griglia.dataProvider.refreshAll()

    private fun avvisa(messaggio: String?) {
        if (messaggio != null) Notification.show(messaggio, 4000, Notification.Position.BOTTOM_START)
    }
}