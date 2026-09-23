package com.lightningbi.lightning_engine.model

/**
 * Esito del calcolo di un grafico: Ready se i dati sono pronti da
 * disegnare, Incoherent se il grafico referenzia campi (Righe/Colonne)
 * non più presenti nel pivot corrente dell'Analisi - la UI disegna un
 * placeholder al posto del grafico invece di farlo sparire silenziosamente,
 * per far capire all'utente cosa sistemare (in ChartsView).
 */
sealed class ChartResult {
    data class Ready(val data: ChartData) : ChartResult()
    data class Incoherent(val chart: AreaChart, val reason: String) : ChartResult()
}