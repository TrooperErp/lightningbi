package com.lightningbi.lightning_engine.model

import java.time.Instant
import java.util.UUID

enum class SourceStatus {
    PENDING_VIEW,
    VERIFIED,
    ERROR
}

enum class SyncMode {
    FULL_RELOAD,
    INCREMENTAL
}

data class LookupConfig(
    val targetFieldId: UUID,
    val lookupTable: String,
    val joinColumnMain: String,
    val joinColumnLookup: String,
    val valueColumn: String
)

data class DirectMapping(
    val targetFieldId: UUID,
    val sourceColumn: String
)

data class SourceConfig(
    val jdbcUrl: String,
    val username: String,
    val encryptedPassword: String,
    val driverClassName: String,
    val schema: String?,
    val mainTable: String,
    val viewName: String,
    val directMappings: List<DirectMapping>,
    val lookups: List<LookupConfig>,
    val syncMode: SyncMode = SyncMode.FULL_RELOAD
)

data class AreaSource(
    val id: UUID,
    val areaId: UUID,
    val tipoSorgente: String,
    val config: SourceConfig,
    val status: SourceStatus,
    val errorDetail: String?,
    val createdAt: Instant
)