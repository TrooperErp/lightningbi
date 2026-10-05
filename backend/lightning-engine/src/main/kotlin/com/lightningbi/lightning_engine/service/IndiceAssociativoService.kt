package com.lightningbi.lightning_engine.service

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Pulizia dell'indice bitmap associativo di un dataset su ClickHouse.
 *
 * Sta in un servizio a parte (solo JdbcTemplate) perché lo usa RegistryService
 * quando si elimina un dataset: BitmapIndexBuilder dipende da DatasetService,
 * che dipende da RegistryService, e un riferimento diretto farebbe un ciclo.
 *
 * Tocca solo l'indice: le tabelle importate non si eliminano mai da qui.
 */
@Service
class IndiceAssociativoService(
    private val jdbcTemplate: JdbcTemplate
) {
    private val log = LoggerFactory.getLogger(IndiceAssociativoService::class.java)

    /** Tabelle di indice con la partizione del dataset: quella nuova, la sua staging e la vecchia. */
    private val tabelle = listOf("ch_lbi_idx", "ch_lbi_idx_staging", "ch_lbi_assoc_bitmap", "ch_lbi_assoc_bitmap_staging")

    /**
     * Toglie la partizione del dataset da tutte le tabelle di indice. Non
     * lancia eccezioni: un indice orfano è innocuo (nessun dataset lo legge) e
     * non deve impedire l'eliminazione del dataset. Restituisce le tabelle in
     * cui la pulizia è fallita.
     */
    fun elimina(areaId: UUID): List<String> {
        // L'UUID in forma stringa è sicuro da interpolare (formato fisso).
        val partizione = areaId.toString()
        val fallite = mutableListOf<String>()
        tabelle.forEach { tabella ->
            try {
                jdbcTemplate.execute("ALTER TABLE $tabella DROP PARTITION '$partizione'")
            } catch (e: Exception) {
                fallite += tabella
                log.warn("Pulizia dell'indice {} per il dataset {} fallita", tabella, areaId, e)
            }
        }
        return fallite
    }
}