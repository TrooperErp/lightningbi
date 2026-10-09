package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.repository.ImportedTableRepository
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Un dataset caricato per le interrogazioni: la bozza (tabelle, campi,
 * associazioni), il grafo con i generatori di query e i nomi fisici delle
 * tabelle importate.
 */
class ModelloDataset(
    val areaId: UUID,
    val bozza: DatasetBozza,
    val grafo: GrafoDataset,
    /** Occorrenza -> nome fisico della tabella su ClickHouse. */
    val tabellaFisica: Map<UUID, String>
) {
    private val colonne: Map<Pair<UUID, String>, String> =
        bozza.campi().filter { !it.escluso }.associate { (it.occorrenzaId to it.nomeCampo) to it.colonna }

    /** Colonna fisica del campo nella tabella, o null se la tabella non ha il campo. */
    fun colonnaDi(tabella: UUID, campo: String): String? = colonne[tabella to campo]

    fun fisica(tabella: UUID): String =
        Naming.requirePhysical(
            tabellaFisica[tabella] ?: error("Tabella $tabella non presente nel dataset"),
            "tabella"
        )
}

/**
 * Cache dei modelli dei dataset in memoria: un dataset si rilegge dal registry
 * solo quando cambia la versione del registry (ogni salvataggio la incrementa).
 * Condivisa dal motore degli stati e dalle aggregazioni.
 */
@Service
class ModelloDatasetCache(
    private val datasetService: DatasetService,
    private val importedTableRepository: ImportedTableRepository
) {
    private val cache = ConcurrentHashMap<UUID, Pair<Long, ModelloDataset>>()

    fun get(areaId: UUID, registryVersion: Long): ModelloDataset {
        val inCache = cache[areaId]
        if (inCache != null && inCache.first == registryVersion) return inCache.second

        val bozza = datasetService.carica(areaId) ?: error("Dataset $areaId non trovato")
        val fisiche = bozza.occorrenze.associate { o ->
            val tabella = importedTableRepository.findById(o.importedTableId)
                ?: error("Tabella importata ${o.importedTableId} non trovata")
            o.id to tabella.tabellaFisica
        }
        // ModelloDataset.kt — ModelloDatasetCache.get
        val modello = ModelloDataset(areaId, bozza, GrafoDataset.da(areaId, bozza, fisiche), fisiche)
        cache[areaId] = registryVersion to modello
        return modello
    }
}