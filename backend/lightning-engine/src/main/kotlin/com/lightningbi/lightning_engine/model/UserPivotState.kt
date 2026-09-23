package com.lightningbi.lightning_engine.model

import java.time.Instant
import java.util.UUID

/**
 * Stato personale di un utente su un'area: quale PivotView è attiva in
 * questo momento (activeViewId) e le selezioni correnti (verde/bianco/
 * grigio), condivise trasversalmente su tutte le PivotView della stessa
 * area - esattamente come le selezioni Qlik restano valide passando da
 * un foglio all'altro della stessa app.
 *
 * Righe/Colonne/Valori NON vivono più qui: sono proprietà della
 * PivotView referenziata da activeViewId. Una sola riga per
 * (userId, areaId), sovrascritta ad ogni cambio di vista attiva o di
 * selezione.
 */
data class UserPivotState(
    val userId: UUID,
    val areaId: UUID,
    val activeViewId: UUID?,
    val selections: Map<UUID, Set<Long>>,
    val updatedAt: Instant = Instant.now()
)