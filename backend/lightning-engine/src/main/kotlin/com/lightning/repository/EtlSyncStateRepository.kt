package com.lightning.repository

import com.lightning.model.EtlSyncState
import java.util.UUID

interface EtlSyncStateRepository {
    fun find(areaId: UUID, sourceId: UUID): EtlSyncState?
    fun upsert(state: EtlSyncState)
}