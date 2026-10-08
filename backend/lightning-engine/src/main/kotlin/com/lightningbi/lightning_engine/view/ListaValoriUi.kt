package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.service.FiltriService
import com.lightningbi.lightning_engine.service.ValoreFiltro
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.grid.GridVariant
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.data.provider.DataProvider
import com.vaadin.flow.data.value.ValueChangeMode
import java.util.UUID

/**
 * L'elenco dei valori di UN campo, come il list box di Qlik.
 *
 * - Scorre a pagine: legge dal motore solo le righe visibili, quindi regge
 *   anche campi con milioni di valori.
 * - Ogni valore ha il suo stato: verde (selezionato), bianco (possibile),
 *   grigio (escluso). Ordine: selezionati, possibili, esclusi.
 * - Campo di ricerca in testa (senza distinguere maiuscole).
 * - Come in QlikView: clic = quel valore diventa l'unica selezione; Ctrl+clic
 *   aggiunge o toglie un valore. La decisione la prende chi usa il componente
 *   ([alClic] riceve il valore e se Ctrl era premuto).
 *
 * [selezioni] dà le selezioni correnti ogni volta che si legge, così [aggiorna]
 * ricalcola l'elenco con le selezioni nuove.
 */
class ListaValoriUi(
    private val filtri: FiltriService,
    private val areaId: UUID,
    private val dimensioneId: UUID,
    private val selezioni: () -> Map<UUID, Set<Long>>,
    private val alClic: (ValoreFiltro, Boolean) -> Unit
) : Div() {

    private val ricerca = TextField().apply {
        placeholder = "Cerca"
        isClearButtonVisible = true
        valueChangeMode = ValueChangeMode.LAZY
        valueChangeTimeout = 300
        setWidthFull()
        addClassName("lbi-qv-search")
    }

    private val griglia = Grid<ValoreFiltro>().apply {
        addThemeVariants(GridVariant.LUMO_COMPACT, GridVariant.LUMO_NO_BORDER, GridVariant.LUMO_NO_ROW_BORDERS)
        addClassName("lbi-qv-grid")
        height = "240px"
        setPartNameGenerator { valore -> "lbi-qv-val lbi-qv-val-" + valore.stato.name.lowercase() }
        addColumn { it.etichetta }.setAutoWidth(false).setFlexGrow(1)
    }

    init {
        className = "lbi-qv-list"

        griglia.setItems(
            DataProvider.fromCallbacks<ValoreFiltro>(
                { query ->
                    filtri.valori(
                        areaId, selezioni(), dimensioneId, ricerca.value,
                        query.offset, query.limit
                    ).stream()
                },
                { _ -> filtri.numeroValori(areaId, selezioni(), dimensioneId, ricerca.value) }
            )
        )
        griglia.addItemClickListener { evento -> alClic(evento.item, evento.isCtrlKey || evento.isMetaKey) }
        ricerca.addValueChangeListener { griglia.dataProvider.refreshAll() }

        add(ricerca, griglia)
    }

    /** Rilegge l'elenco con le selezioni correnti (dopo una selezione fatta altrove). */
    fun aggiorna() {
        griglia.dataProvider.refreshAll()
    }

    /** Fa rimisurare la griglia (dopo l'apertura in una finestra: l'altezza va ricalcolata). */
    fun ridimensiona() {
        griglia.element.executeJs("setTimeout(() => { this.notifyResize(); }, 300)")
    }
}