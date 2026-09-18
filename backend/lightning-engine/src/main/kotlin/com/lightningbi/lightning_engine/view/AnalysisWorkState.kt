package com.lightningbi.lightning_engine.view

import java.util.UUID

/**
 * Fotografia dello stato di lavoro su un'analisi (pivot e filtri), presa
 * nel momento in cui si lascia AssociativeExplorerView per navigare
 * altrove (es. verso ChartsView). Vaadin distrugge l'istanza della vista
 * ad ogni cambio di route - questo oggetto sopravvive nella sessione
 * utente e viene riletto quando si torna sulla stessa area, così pivot
 * e filtri non vanno persi solo perché nel frattempo si è visitata
 * un'altra pagina.
 *
 * Presa solo al momento dell'uscita, non ad ogni interazione: uno stato
 * di lavoro in corso non ha bisogno di essere fotografato in continuo,
 * solo nell'istante in cui si rischia di perderlo.
 */
data class AnalysisWorkState(
    val areaId: UUID,
    val pivotRows: List<UUID>,
    val pivotColumns: List<UUID> = emptyList(),
    val pivotValues: List<UUID>,
    val selections: Map<UUID, Set<Long>>
)