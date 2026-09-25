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
 * FASE 1 (selezioni per-Analisi): "selections" è il filtro SALVATO e
 * CONDIVISO di questa vista (es. Analisi "Vendite Italia" salva
 * Nazione=Italia): chiunque apra questa PivotView applica questo
 * filtro, esattamente come già succede per pivotRows/pivotColumns/
 * pivotValues. Non è più vero, da qui in poi, che le selezioni sono
 * uniche per l'intera Area: ogni PivotView ha le sue.
 *
 * FASE 2 (non ancora implementata): un livello di selezione di sessione
 * per-utente, sovrapposto a "selections", con override sulla stessa
 * dimensione invece di AND. Fino a quel momento, "selections" è
 * l'unico stato di filtro esistente per questa vista.
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
    val selections: Map<UUID, Set<Long>> = emptyMap(),
    val posizione: Int = 0,
    val createdAt: Instant = Instant.now()
)