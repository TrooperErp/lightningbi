package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.*
import com.lightningbi.lightning_engine.repository.RegistryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class RegistryService(
    private val registryRepository: RegistryRepository,
    private val symbolTableService: SymbolTableService
) {
    fun getArea(nome: String) = registryRepository.findAreaByNome(nome)
    fun getDimensioniArea(areaId: UUID) = registryRepository.findDimensioniByArea(areaId)
    fun getMetricheArea(areaId: UUID) = registryRepository.findMetricheByArea(areaId)
    fun getDimensione(id: UUID) = registryRepository.findDimensione(id)
    fun getVersion() = registryRepository.getVersion()

    @Transactional("postgresTransactionManager")
    fun createArea(nome: String, tabellaFisica: String): Area {
        val area = Area(UUID.randomUUID(), nome, tabellaFisica)
        registryRepository.saveArea(area)
        registryRepository.bumpVersion()
        return area
    }

    @Transactional("postgresTransactionManager")
    fun createDimensione(nome: String, tipo: String, conformata: Boolean, tabellaDim: String?, colonnaChiave: String?): Dimensione {
        symbolTableService.createSymbolTable(nome)
        val dim = Dimensione(UUID.randomUUID(), nome, tipo, conformata, tabellaDim, colonnaChiave)
        registryRepository.saveDimensione(dim)
        registryRepository.bumpVersion()
        return dim
    }

    @Transactional("postgresTransactionManager")
    fun linkDimensioneToArea(areaId: UUID, dimensioneId: UUID, colonnaFisica: String, obbligatoria: Boolean, cardinalita: Long?) {
        registryRepository.saveAreaDimensione(AreaDimensione(areaId, dimensioneId, colonnaFisica, obbligatoria, cardinalita))
        registryRepository.bumpVersion()
    }

    @Transactional("postgresTransactionManager")
    fun addMetrica(areaId: UUID, nome: String, colonnaFisica: String, tipoAggregazione: String) {
        registryRepository.saveAreaMetrica(AreaMetrica(UUID.randomUUID(), areaId, nome, colonnaFisica, tipoAggregazione))
        registryRepository.bumpVersion()
    }
}