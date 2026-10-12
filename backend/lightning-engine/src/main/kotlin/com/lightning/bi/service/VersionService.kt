package com.lightning.bi.service

import com.lightning.bi.model.VersionSnapshot
import com.lightning.bi.repository.DataVersionRepository
import com.lightning.bi.repository.RegistryRepository
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