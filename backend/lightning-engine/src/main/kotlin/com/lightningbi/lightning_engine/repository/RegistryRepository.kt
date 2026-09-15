package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.*
import java.util.UUID

interface RegistryRepository {
    fun findAreaByNome(nome: String): Area?
    fun findAreaById(id: UUID): Area?
    fun findDimensioniByArea(areaId: UUID): List<AreaDimensione>
    fun findDimensioniByIds(ids: List<UUID>): List<Dimensione>
    fun findMetricheByArea(areaId: UUID): List<AreaMetrica>
    fun findMetricaById(id: UUID): AreaMetrica?
    fun findDimensione(id: UUID): Dimensione?
    fun getVersion(): Long
    fun bumpVersion()
    fun saveArea(area: Area)
    fun saveDimensione(dimensione: Dimensione)
    fun saveAreaDimensione(ad: AreaDimensione)
    fun saveAreaMetrica(am: AreaMetrica)
    fun updateAreaMetrica(am: AreaMetrica)
    fun deleteAreaMetrica(id: UUID)
    fun findAllAree(): List<Area>
    fun findAllDimensioni(): List<Dimensione>

    /**
     * Cancellazione completa di un'area dal registry: righe di collegamento
     * (dimensioni, metriche) prima, la riga area per ultima. Non tocca
     * lbi_dimensione: le dimensioni possono essere condivise con altre
     * aree (conformate), cancellarle qui romperebbe quelle altre aree.
     */
    fun deleteAreaDimensioniByArea(areaId: UUID)
    fun deleteAreaMetricheByArea(areaId: UUID)
    fun deleteArea(id: UUID)
}