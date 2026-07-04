package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.*
import java.util.UUID

interface RegistryRepository {
    fun findAreaByNome(nome: String): Area?
    fun findAreaById(id: UUID): Area?
    fun findDimensioniByArea(areaId: UUID): List<AreaDimensione>
    fun findDimensioniByIds(ids: List<UUID>): List<Dimensione>
    fun findMetricheByArea(areaId: UUID): List<AreaMetrica>
    fun findDimensione(id: UUID): Dimensione?
    fun getVersion(): Long
    fun bumpVersion()
    fun saveArea(area: Area)
    fun saveDimensione(dimensione: Dimensione)
    fun saveAreaDimensione(ad: AreaDimensione)
    fun saveAreaMetrica(am: AreaMetrica)
}