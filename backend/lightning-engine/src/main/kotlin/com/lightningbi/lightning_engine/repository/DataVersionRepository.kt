package com.lightningbi.lightning_engine.repository

import java.util.UUID

interface DataVersionRepository {
    fun getVersion(areaId: UUID): Long
    fun bumpVersion(areaId: UUID)
}