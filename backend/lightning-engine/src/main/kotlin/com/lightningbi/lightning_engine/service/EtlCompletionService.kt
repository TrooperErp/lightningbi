package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.EtlSyncState
import com.lightningbi.lightning_engine.repository.DataVersionRepository
import com.lightningbi.lightning_engine.repository.EtlSyncStateRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class EtlCompletionService(
    private val etlSyncStateRepository: EtlSyncStateRepository,
    private val dataVersionRepository: DataVersionRepository
) {
    @Transactional("postgresTransactionManager")
    fun completeSuccess(areaId: UUID, sourceId: UUID, syncTime: LocalDateTime) {
        etlSyncStateRepository.upsert(EtlSyncState(areaId, sourceId, syncTime))
        dataVersionRepository.bumpVersion(areaId)
    }
}