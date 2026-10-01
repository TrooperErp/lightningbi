package com.lightningbi.lightning_engine.model

import java.time.LocalDateTime
import java.util.UUID

data class EtlRun(
    val id: UUID,
    val areaId: UUID?,
    val sourceId: UUID?,
    val startedAt: LocalDateTime,
    val finishedAt: LocalDateTime?,
    val stato: EtlStato,
    val righeProcessate: Long,
    val righeScartate: Long,
    val errore: String?,
    /** La tabella importata sincronizzata: una esecuzione per tabella. */
    val importedTableId: UUID? = null,
    /** Come è stata eseguita davvero (una incrementale senza ultima sincronizzazione parte completa). */
    val modalita: ModalitaSync? = null,
    val unitaSostituite: Long = 0,
    val unitaEliminate: Long = 0
)

data class EtlSyncState(val areaId: UUID, val sourceId: UUID, val lastSync: LocalDateTime?)