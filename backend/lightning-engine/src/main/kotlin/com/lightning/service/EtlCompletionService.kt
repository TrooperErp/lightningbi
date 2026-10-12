package com.lightning.service

import com.lightning.model.EtlSyncState
import com.lightning.repository.DataVersionRepository
import com.lightning.repository.EtlSyncStateRepository
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

    /**
     * Dopo la ricostruzione dell'indice di un dataset: alza la sua dataVersion,
     * così le cache calcolate sui dati vecchi si invalidano.
     */
    @Transactional("postgresTransactionManager")
    fun completeAreaRefresh(areaId: UUID) {
        dataVersionRepository.bumpVersion(areaId)
    }
}