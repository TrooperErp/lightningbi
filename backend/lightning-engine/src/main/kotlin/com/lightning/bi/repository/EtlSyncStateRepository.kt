package com.lightning.bi.repository

import com.lightning.bi.model.EtlSyncState
import java.util.UUID

interface EtlSyncStateRepository {
    fun find(areaId: UUID, sourceId: UUID): EtlSyncState?
    fun upsert(state: EtlSyncState)
}