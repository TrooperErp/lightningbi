package com.lightning.bi.ui

import com.lightning.bi.service.PivotViewService
import com.lightning.piattaforma.view.CurrentUserHolder
import org.springframework.stereotype.Component
import java.util.UUID

enum class TipoFoglio(val etichetta: String) { FILTRI(""), PIVOT("pivot"), GRAFICI("grafici") }

/** Un foglio di un dataset, come le schede di un'app Qlik. [id]: "filtri", "grafici" o l'id della vista. */
data class Foglio(val id: String, val nome: String, val tipo: TipoFoglio, val rotta: String)

/** I fogli di un dataset: Filtri, le viste pivot in ordine, Grafici. */
@Component
class FogliBi(private val pivotViewService: PivotViewService) {

    fun di(areaId: UUID): List<Foglio> {
        val viste = pivotViewService.findByArea(areaId).sortedBy { it.posizione }
        return buildList {
            add(Foglio(ID_FILTRI, "Filtri", TipoFoglio.FILTRI, RotteBi.filtri(areaId)))
            viste.forEach { add(Foglio(it.id.toString(), it.nome, TipoFoglio.PIVOT, RotteBi.analisi(areaId, it.id))) }

        }
    }

    /** Crea una vista pivot vuota in coda e restituisce il suo foglio (già attivo per l'utente). */
    fun nuovoFoglio(areaId: UUID): Foglio? {
        val utente = CurrentUserHolder.get() ?: return null
        val numero = pivotViewService.findByArea(areaId).size + 1
        val vista = pivotViewService.createView(utente.userId, areaId, "Foglio $numero")
        return Foglio(vista.id.toString(), vista.nome, TipoFoglio.PIVOT, RotteBi.analisi(areaId, vista.id))
    }

    companion object {
        const val ID_FILTRI = "filtri"
        const val ID_GRAFICI = "grafici"
    }
}