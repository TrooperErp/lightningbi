package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.repository.RegistryRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Ricostruisce l'indice bitmap associativo (ch_lbi_assoc_bitmap) di
 * un'Area, leggendo per intero la sua tabella fatti.
 *
 * Una sola scansione della tabella fatti: ogni riga riceve un numero
 * (rowNumberInAllBlocks, unico all'interno della query) e viene
 * "esplosa" in una coppia (dimensione, valore) per ogni dimensione
 * dell'area. Raggruppando per (dimensione, valore) si ottiene la bitmap
 * dei numeri di riga in cui quel valore compare. Siccome il numero di
 * riga è calcolato una volta sola per riga sorgente, prima dell'ARRAY
 * JOIN, è coerente tra tutte le bitmap della stessa ricostruzione -
 * che è l'unica cosa che serve: tra una ricostruzione e l'altra la
 * numerazione può cambiare, perché l'indice viene sempre rifatto da zero.
 *
 * Scrive in ch_lbi_assoc_bitmap_staging e poi sostituisce la partizione
 * dell'area nella tabella principale con REPLACE PARTITION (atomico):
 * chi interroga l'indice durante la ricostruzione vede sempre la
 * versione precedente completa, mai una a metà.
 *
 * Va chiamato DOPO il load dei fatti e PRIMA del bump della dataVersion
 * (EtlCompletionService.completeSuccess): così una versione dati nuova
 * esiste solo quando l'indice è già allineato, e la cache degli stati
 * non può associare dati nuovi a un indice vecchio.
 *
 * I valori NULL delle dimensioni diventano 0, coerente con il motore a
 * query esistente che legge i valori con rs.getLong (NULL -> 0).
 */
@Service
class BitmapIndexBuilder(
    private val jdbcTemplate: JdbcTemplate,
    private val registryRepository: RegistryRepository
) {
    private val log = LoggerFactory.getLogger(BitmapIndexBuilder::class.java)

    private val mainTable = "ch_lbi_assoc_bitmap"
    private val stagingTable = "ch_lbi_assoc_bitmap_staging"

    fun rebuild(areaId: UUID) {
        val start = System.currentTimeMillis()

        val area = registryRepository.findAreaById(areaId) ?: error("Area $areaId non trovata")
        val dimensioni = registryRepository.findDimensioniByArea(areaId)
        val table = requireIdentifier(area.tabellaFisica, "table")

        // L'UUID in forma stringa è sicuro da interpolare (formato fisso,
        // solo esadecimali e trattini): serve come letterale perché i
        // nomi di partizione in ALTER TABLE non accettano parametri "?".
        val partition = areaId.toString()

        // Pulizia di eventuali residui di una ricostruzione precedente
        // interrotta a metà.
        jdbcTemplate.execute("ALTER TABLE $stagingTable DROP PARTITION '$partition'")

        if (dimensioni.isEmpty()) {
            jdbcTemplate.execute("ALTER TABLE $mainTable DROP PARTITION '$partition'")
            log.info("Indice bitmap area {}: nessuna dimensione, indice svuotato", areaId)
            return
        }

        val coppie = dimensioni.joinToString(", ") { d ->
            val col = requireIdentifier(d.colonnaFisica, "column")
            "('${d.dimensioneId}', toInt64(ifNull($col, 0)))"
        }

        val insertSql = """
            INSERT INTO $stagingTable (area_id, dimensione_id, valore_id, righe)
            SELECT
                toUUID('$partition'),
                toUUID(coppia.1),
                coppia.2,
                groupBitmapState(toUInt32(rid))
            FROM
            (
                SELECT
                    rowNumberInAllBlocks() AS rid,
                    [$coppie] AS coppie
                FROM $table
            )
            ARRAY JOIN coppie AS coppia
            GROUP BY coppia.1, coppia.2
        """.trimIndent()

        jdbcTemplate.execute(insertSql)

        val righeIndice = jdbcTemplate.queryForObject(
            "SELECT count() FROM $stagingTable WHERE area_id = toUUID('$partition')",
            Long::class.java
        ) ?: 0L

        if (righeIndice == 0L) {
            // Tabella fatti vuota: nessuna partizione in staging da usare
            // per REPLACE, si svuota direttamente l'indice principale.
            jdbcTemplate.execute("ALTER TABLE $mainTable DROP PARTITION '$partition'")
        } else {
            jdbcTemplate.execute("ALTER TABLE $mainTable REPLACE PARTITION '$partition' FROM $stagingTable")
            jdbcTemplate.execute("ALTER TABLE $stagingTable DROP PARTITION '$partition'")
        }

        log.info(
            "Indice bitmap area {} ricostruito: {} dimensioni, {} valori indicizzati, {} ms",
            areaId, dimensioni.size, righeIndice, System.currentTimeMillis() - start
        )
    }

    /** Stessa difesa anti-injection usata in AggregateService/AssociativeStateService. */
    private fun requireIdentifier(value: String, what: String): String {
        val normalized = Naming.slug(value)
        require(normalized == value) {
            "Identificatore $what non normalizzato nel registry: '$value' (atteso '$normalized')."
        }
        return value
    }
}