package com.lightning.bi.repository

import java.util.UUID

interface DataVersionRepository {
    fun getVersion(areaId: UUID): Long
    fun bumpVersion(areaId: UUID)
}