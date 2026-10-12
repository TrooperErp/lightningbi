package com.lightning.bi.repository

import com.lightning.bi.model.AreaSource
import java.util.UUID

interface AreaSourceRepository {
    fun findByArea(areaId: UUID): List<AreaSource>
    fun findById(id: UUID): AreaSource?
    fun save(source: AreaSource) // upsert
    fun delete(id: UUID)
    fun findAll(): List<AreaSource>
}