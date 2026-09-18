package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.*
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class RegistryService(
    private val registryRepository: RegistryRepository,
    private val areaSourceRepository: AreaSourceRepository,
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

    fun findDimensioneByNomeFisico(nome: String): Dimensione? {
        val target = Naming.slug(nome)
        return registryRepository.findAllDimensioni().firstOrNull { Naming.slug(it.nome) == target }
    }

    @Transactional("postgresTransactionManager")
    fun findOrCreateDimensione(
        nome: String,
        tipo: String = "string",
        conformata: Boolean = false,
        tabellaDim: String? = null,
        colonnaChiave: String? = null
    ): Dimensione {
        findDimensioneByNomeFisico(nome)?.let { existing ->
            symbolTableService.createSymbolTable(existing.nome)
            return existing
        }
        return createDimensione(nome, tipo, conformata, tabellaDim, colonnaChiave)
    }

    @Transactional("postgresTransactionManager")
    fun createDimensione(
        nome: String,
        tipo: String,
        conformata: Boolean,
        tabellaDim: String?,
        colonnaChiave: String?
    ): Dimensione {
        Naming.slug(nome)
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

    fun getColonneMetricheDisponibili(areaId: UUID): List<String> =
        registryRepository.findMetricheByArea(areaId)
            .mapNotNull { it.colonnaFisica }
            .distinct()
            .sorted()

    @Transactional("postgresTransactionManager")
    fun addMetrica(
        areaId: UUID,
        nome: String,
        colonnaFisica: String?,
        tipoAggregazione: TipoAggregazione,
        tipoMetrica: TipoMetrica = TipoMetrica.AGGREGAZIONE_COLONNA,
        espressione: String? = null
    ): AreaMetrica {
        require(colonnaFisica != null || tipoAggregazione == TipoAggregazione.COUNT) {
            "colonnaFisica è obbligatoria per l'aggregazione $tipoAggregazione"
        }
        validateNomeUnivoco(areaId, nome, escludiId = null)

        val metrica = AreaMetrica(
            id = UUID.randomUUID(),
            areaId = areaId,
            nome = nome,
            colonnaFisica = colonnaFisica?.let { Naming.column(it) },
            tipoAggregazione = tipoAggregazione,
            tipoMetrica = tipoMetrica,
            espressione = espressione
        )
        registryRepository.saveAreaMetrica(metrica)
        registryRepository.bumpVersion()
        return metrica
    }

    @Transactional("postgresTransactionManager")
    fun updateMetrica(
        metricaId: UUID,
        nuovoNome: String,
        nuovoTipoAggregazione: TipoAggregazione
    ): AreaMetrica {
        val esistente = registryRepository.findMetricaById(metricaId)
            ?: error("Metrica $metricaId non trovata")

        require(esistente.colonnaFisica != null || nuovoTipoAggregazione == TipoAggregazione.COUNT) {
            "colonnaFisica è obbligatoria per l'aggregazione $nuovoTipoAggregazione"
        }
        validateNomeUnivoco(esistente.areaId, nuovoNome, escludiId = metricaId)

        val aggiornata = esistente.copy(nome = nuovoNome, tipoAggregazione = nuovoTipoAggregazione)
        registryRepository.updateAreaMetrica(aggiornata)
        registryRepository.bumpVersion()
        return aggiornata
    }

    @Transactional("postgresTransactionManager")
    fun deleteMetrica(metricaId: UUID) {
        registryRepository.deleteAreaMetrica(metricaId)
        registryRepository.bumpVersion()
    }

    /**
     * Cancellazione completa di un'area: sorgenti, metriche, collegamenti
     * a dimensioni, la riga area, e la tabella fatti su ClickHouse.
     *
     * Non tocca lbi_dimensione: le dimensioni possono essere condivise con
     * altre aree (conformate), cancellarle qui romperebbe quelle altre aree
     * silenziosamente. Restano nel registry anche se questa era l'unica
     * area che le usava - orfane ma innocue, si possono ripulire a parte
     * in futuro con un controllo esplicito di utilizzo.
     *
     * L'ordine conta: prima le righe che referenziano l'area (sorgenti,
     * metriche, collegamenti dimensione), poi l'area stessa, poi la tabella
     * fisica ClickHouse per ultima - se qualcosa fallisce a metà, meglio
     * un'area orfana in Postgres (recuperabile) che una tabella ClickHouse
     * sparita mentre il registry pensa ancora che esista.
     */
    @Transactional("postgresTransactionManager")
    fun deleteAreaCompleta(areaId: UUID) {
        val area = registryRepository.findAreaById(areaId) ?: error("Area $areaId non trovata")

        areaSourceRepository.findByArea(areaId).forEach { areaSourceRepository.delete(it.id) }
        registryRepository.deleteAreaMetricheByArea(areaId)
        registryRepository.deleteAreaDimensioniByArea(areaId)
        registryRepository.deleteArea(areaId)
        registryRepository.bumpVersion()

        try {
            symbolTableService.dropTable(area.tabellaFisica)
        } catch (e: Exception) {
            // Il registry è già pulito: un fallimento qui lascia una
            // tabella ClickHouse orfana, non un'area rotta. Va segnalato
            // ma non deve far fallire l'intera cancellazione.
            throw IllegalStateException(
                "Area \"${area.nome}\" rimossa dal registry, ma la tabella ${area.tabellaFisica} " +
                        "su ClickHouse non è stata eliminata: ${e.message}. Va rimossa a mano.", e
            )
        }
    }

    private fun validateNomeUnivoco(areaId: UUID, nome: String, escludiId: UUID?) {
        val esistenti = registryRepository.findMetricheByArea(areaId)
        val collisione = esistenti.any { it.nome.equals(nome, ignoreCase = true) && it.id != escludiId }
        require(!collisione) { "Esiste già una metrica chiamata \"$nome\" in questa area" }
    }

    /**
     * Collega una dimensione a un'area già esistente, aggiungendo anche
     * la colonna fisica sulla tabella fatti ClickHouse se non c'è già.
     * A differenza di linkDimensioneToArea (usato anche in creazione area,
     * dove la tabella non esiste ancora), questo presume la tabella già
     * creata e la alterà in place - usarlo SOLO per aree esistenti.
     */
    @Transactional("postgresTransactionManager")
    fun linkDimensioneToExistingArea(
        areaId: UUID,
        dimensioneId: UUID,
        colonnaFisica: String,
        obbligatoria: Boolean,
        cardinalita: Long? = null
    ) {
        val area = registryRepository.findAreaById(areaId) ?: error("Area $areaId non trovata")
        symbolTableService.addColumnToAreaTable(area.tabellaFisica, colonnaFisica)
        linkDimensioneToArea(areaId, dimensioneId, colonnaFisica, obbligatoria, cardinalita)
    }
}