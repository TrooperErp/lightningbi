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

    /**
     * Cerca una dimensione già esistente il cui nome, una volta normalizzato,
     * coincide con quello richiesto.
     *
     * Serve perché due dimensioni con lo stesso nome fisico condividerebbero
     * la stessa symbol table ma avrebbero id diversi nel registry: i value_id
     * diventerebbero ambigui e le dimensioni "conformate" smetterebbero di
     * esserlo. Il confronto è sul nome normalizzato, non su quello logico,
     * perché "Cliente" e "CLIENTE" puntano alla stessa tabella fisica.
     */
    fun findDimensioneByNomeFisico(nome: String): Dimensione? {
        val target = Naming.slug(nome)
        return registryRepository.findAllDimensioni().firstOrNull { Naming.slug(it.nome) == target }
    }

    /**
     * Restituisce la dimensione esistente se c'è, altrimenti la crea.
     * È il metodo che i wizard devono usare: creare sempre una dimensione
     * nuova produce duplicati e rompe il riuso tra aree.
     */
    @Transactional("postgresTransactionManager")
    fun findOrCreateDimensione(
        nome: String,
        tipo: String = "string",
        conformata: Boolean = false,
        tabellaDim: String? = null,
        colonnaChiave: String? = null
    ): Dimensione {
        findDimensioneByNomeFisico(nome)?.let { existing ->
            // Rete di sicurezza: se il registry ha la dimensione ma la symbol
            // table manca (es. rollback precedente), la ricrea. CREATE TABLE
            // IF NOT EXISTS rende l'operazione idempotente.
            symbolTableService.createSymbolTable(existing.nome)
            return existing
        }
        return createDimensione(nome, tipo, conformata, tabellaDim, colonnaChiave)
    }

    /**
     * Crea una nuova dimensione.
     *
     * Ordine invertito rispetto a prima: si salva PRIMA su Postgres (dentro
     * transazione) e solo dopo si crea la symbol table su ClickHouse. Con
     * l'ordine precedente, un fallimento del save lasciava una tabella
     * ClickHouse orfana che il rollback Postgres non poteva rimuovere.
     */
    @Transactional("postgresTransactionManager")
    fun createDimensione(
        nome: String,
        tipo: String,
        conformata: Boolean,
        tabellaDim: String?,
        colonnaChiave: String?
    ): Dimensione {
        Naming.slug(nome) // fallisce subito se il nome non è convertibile
        val dim = Dimensione(UUID.randomUUID(), nome, tipo, conformata, tabellaDim, colonnaChiave)
        registryRepository.saveDimensione(dim)
        registryRepository.bumpVersion()
        symbolTableService.createSymbolTable(nome)
        return dim
    }

    @Transactional("postgresTransactionManager")
    fun linkDimensioneToArea(
        areaId: UUID,
        dimensioneId: UUID,
        colonnaFisica: String,
        obbligatoria: Boolean,
        cardinalita: Long?
    ) {
        registryRepository.saveAreaDimensione(
            AreaDimensione(areaId, dimensioneId, Naming.column(colonnaFisica), obbligatoria, cardinalita)
        )
        registryRepository.bumpVersion()
    }

    @Transactional("postgresTransactionManager")
    fun addMetrica(areaId: UUID, nome: String, colonnaFisica: String, tipoAggregazione: String): AreaMetrica {
        val metrica = AreaMetrica(
            UUID.randomUUID(), areaId, nome, Naming.column(colonnaFisica), tipoAggregazione
        )
        registryRepository.saveAreaMetrica(metrica)
        registryRepository.bumpVersion()
        return metrica
    }
}