package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.EtlSyncState
import java.util.UUID

interface EtlSyncStateRepository {
    fun find(areaId: UUID, sourceId: UUID): EtlSyncState?
    fun upsert(state: EtlSyncState)
}