package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.UserPivotState
import java.util.UUID

interface UserPivotStateRepository {
    fun find(userId: UUID, areaId: UUID): UserPivotState?
    fun save(state: UserPivotState)
    fun delete(userId: UUID, areaId: UUID)

    /**
     * Righe/Colonne/Valori salvati col VECCHIO formato (prima delle
     * PivotView multiple), se ancora presenti per questo utente/area.
     * Usato una sola volta, alla creazione pigra di "Vista 1", per non
     * perdere il pivot già costruito dall'utente. Dopo il primo save()
     * col nuovo formato questi campi spariscono e il metodo torna null.
     */
    fun findLegacyStructure(userId: UUID, areaId: UUID): Triple<List<UUID>, List<UUID>, List<UUID>>?
}