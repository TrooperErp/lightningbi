package com.lightningbi.lightning_engine.model

import java.time.Instant
import java.util.UUID

/**
 * Una "vista pivot" di un'Analisi: Righe, Colonne, Valori con un nome,
 * come un foglio di un'app Qlik. Condivisa per l'intera Area (chiunque
 * apra l'analisi vede lo stesso elenco di viste) - non è per singolo
 * utente. Un'Area può avere più PivotView; una sola alla volta è
 * "attiva" per un dato utente in un dato momento (vedi
 * UserPivotState.activeViewId).
 *
 * Le selezioni (verde/bianco/grigio) NON sono di proprietà della vista:
 * sono uniche e condivise per l'intera Area, valide su tutte le
 * PivotView contemporaneamente - comportamento Qlik confermato (le
 * selezioni sono a livello di app, non di foglio). Vivono in
 * UserPivotState.selections.
 *
 * I grafici (AreaChart) NON dipendono da nessuna PivotView: usano sempre
 * tutti i campi dell'Area, indipendentemente da quale vista sia attiva
 * nella griglia principale in questo momento.
 */
data class PivotView(
    val id: UUID,
    val areaId: UUID,
    val nome: String,
    val pivotRows: List<UUID> = emptyList(),
    val pivotColumns: List<UUID> = emptyList(),
    val pivotValues: List<UUID> = emptyList(),
    val posizione: Int = 0,
    val createdAt: Instant = Instant.now()
)