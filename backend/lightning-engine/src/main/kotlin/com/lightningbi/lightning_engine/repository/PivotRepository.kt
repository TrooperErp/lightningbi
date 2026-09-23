package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.PivotView
import java.util.UUID

interface PivotViewRepository {
    fun findByArea(areaId: UUID): List<PivotView>
    fun findById(id: UUID): PivotView?
    fun save(view: PivotView): PivotView
    fun update(view: PivotView): PivotView
    fun delete(id: UUID): Boolean
    fun nextPosizione(areaId: UUID): Int
}