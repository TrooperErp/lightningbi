package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.AreaSource
import java.util.UUID

interface AreaSourceRepository {
    fun findByArea(areaId: UUID): List<AreaSource>
    fun findById(id: UUID): AreaSource?
    fun save(source: AreaSource) // upsert
    fun delete(id: UUID)
}