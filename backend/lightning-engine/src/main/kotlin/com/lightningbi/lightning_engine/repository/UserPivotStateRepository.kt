package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.UserPivotState
import java.util.UUID

interface UserPivotStateRepository {
    fun find(userId: UUID, areaId: UUID): UserPivotState?
    fun save(state: UserPivotState)
    fun delete(userId: UUID, areaId: UUID)
}