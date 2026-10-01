package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.*
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.repository.ImportedTableRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class RegistryService(
    private val registryRepository: RegistryRepository,
    private val areaSourceRepository: AreaSourceRepository,
    private val importedTableRepository: ImportedTableRepository,
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
    fun addMetrica(
        areaId: UUID,
        nome: String,
        colonnaFisica: String?,
        tipoAggregazione: TipoAggregazione,
        tipoMetrica: TipoMetrica = TipoMetrica.AGGREGAZIONE_COLONNA,
        espressione: String? = null,
        importedTableId: UUID? = null
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
            espressione = espressione,
            importedTableId = importedTableId
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
     * Cancellazione completa di un'area: metriche, collegamenti a
     * dimensioni, tabelle importate (e le loro colonne), sorgenti, la riga
     * area, e le tabelle su ClickHouse.
     *
     * Non tocca lbi_dimensione: le dimensioni possono essere condivise con
     * altre aree (conformate), cancellarle qui romperebbe quelle altre aree
     * silenziosamente. Restano nel registry anche se questa era l'unica
     * area che le usava - orfane ma innocue, si possono ripulire a parte
     * in futuro con un controllo esplicito di utilizzo.
     *
     * L'ORDINE CONTA per le chiavi esterne:
     *  1. metriche e collegamenti dimensione (questi ultimi puntano alle
     *     tabelle importate tramite imported_table_id)
     *  2. tabelle importate (le colonne cadono in cascata; puntano alla
     *     sorgente tramite source_id)
     *  3. sorgenti
     *  4. area
     *  5. tabelle fisiche ClickHouse per ultime - se qualcosa fallisce a
     *     metà, meglio un'area orfana in Postgres (recuperabile) che una
     *     tabella ClickHouse sparita mentre il registry pensa ancora che
     *     esista.
     *
     * Tabelle ClickHouse eliminate: la tabella fatti dell'area
     * (Area.tabellaFisica, per i dataset legacy a view singola) e la
     * tabella fisica di ogni ImportedTable (dataset a schema a stella).
     */
    @Transactional("postgresTransactionManager")
    fun deleteAreaCompleta(areaId: UUID) {
        val area = registryRepository.findAreaById(areaId) ?: error("Area $areaId non trovata")

        // Da leggere PRIMA di cancellare le righe: dopo non c'è più modo di
        // sapere quali tabelle ClickHouse appartenevano all'area.
        val tabelleFisiche = (
                listOf(area.tabellaFisica) +
                        importedTableRepository.findByArea(areaId).map { it.tabellaFisica }
                ).distinct()

        registryRepository.deleteAreaMetricheByArea(areaId)
        registryRepository.deleteAreaDimensioniByArea(areaId)
        importedTableRepository.unlinkArea(areaId)
        importedTableRepository.deleteByArea(areaId)
        areaSourceRepository.findByArea(areaId).forEach { areaSourceRepository.delete(it.id) }
        registryRepository.deleteArea(areaId)
        registryRepository.bumpVersion()

        val nonEliminate = mutableListOf<String>()
        var primoErrore: Exception? = null
        tabelleFisiche.forEach { tabella ->
            try {
                symbolTableService.dropTable(tabella)
            } catch (e: Exception) {
                nonEliminate += tabella
                if (primoErrore == null) primoErrore = e
            }
        }

        if (nonEliminate.isNotEmpty()) {
            // Il registry è già pulito: un fallimento qui lascia tabelle
            // ClickHouse orfane, non un'area rotta. Va segnalato ma non
            // deve far fallire l'intera cancellazione.
            throw IllegalStateException(
                "Area \"${area.nome}\" rimossa dal registry, ma su ClickHouse non sono state eliminate: " +
                        "${nonEliminate.joinToString(", ")} (${primoErrore?.message}). Vanno rimosse a mano.",
                primoErrore
            )
        }
    }

    private fun validateNomeUnivoco(areaId: UUID, nome: String, escludiId: UUID?) {
        val esistenti = registryRepository.findMetricheByArea(areaId)
        val collisione = esistenti.any { it.nome.equals(nome, ignoreCase = true) && it.id != escludiId }
        require(!collisione) { "Esiste già una metrica chiamata \"$nome\" in questa area" }
    }
}