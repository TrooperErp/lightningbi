package com.lightningbi.lightning_engine.model

import java.time.LocalDateTime
import java.util.UUID

data class EtlRun(
    val id: UUID,
    val areaId: UUID,
    val sourceId: UUID,
    val startedAt: LocalDateTime,
    val finishedAt: LocalDateTime?,
    val stato: EtlStato,
    val righeProcessate: Long,
    val righeScartate: Long,
    val errore: String?
)

data class EtlSyncState(val areaId: UUID, val sourceId: UUID, val lastSync: LocalDateTime?)