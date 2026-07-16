package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.VersionSnapshot
import com.lightningbi.lightning_engine.repository.DataVersionRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class VersionService(
    private val registryRepository: RegistryRepository,
    private val dataVersionRepository: DataVersionRepository
) {
    fun snapshotVersions(areaId: UUID): VersionSnapshot =
        VersionSnapshot(
            registryVersion = registryRepository.getVersion(),
            dataVersion = dataVersionRepository.getVersion(areaId)
        )
}