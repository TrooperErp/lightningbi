package com.lightning.service

import com.lightning.model.VersionSnapshot
import com.lightning.repository.DataVersionRepository
import com.lightning.repository.RegistryRepository
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