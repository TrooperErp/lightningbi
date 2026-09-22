package com.lightningbi.lightning_engine.model

import java.time.Instant
import java.util.UUID

/**
 * Stato del pivot (Righe, Colonne, Valori, filtri) salvato per un
 * utente su un'area specifica. Una sola riga per (userId, areaId):
 * sovrascritta ad ogni salvataggio esplicito, nessuno storico di
 * versioni per ora.
 */
data class UserPivotState(
    val userId: UUID,
    val areaId: UUID,
    val pivotRows: List<UUID>,
    val pivotColumns: List<UUID>,
    val pivotValues: List<UUID>,
    val selections: Map<UUID, Set<Long>>,
    val updatedAt: Instant = Instant.now()
)