package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.repository.ImportedTableRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Ricostruisce l'indice bitmap associativo (ch_lbi_idx) di un dataset.
 *
 * Come in Qlik le tabelle restano SEPARATE: nessun JOIN. Per ogni tabella
 * (occorrenza) del dataset si fa una sola scansione della sua tabella
 * importata, "esplodendo" ogni riga in una coppia (campo, valore) per ogni
 * campo indicizzato; raggruppando per (campo, valore) si ottiene la bitmap
 * dei numeri di riga PERSISTENTI (lbi_rid, assegnati al caricamento) in cui
 * il valore compare in quella tabella.
 *
 * Campi indicizzati: quelli usati come dimensione (filtri) e quelli di
 * associazione, cioè condivisi da più tabelle (la propagazione delle
 * selezioni passa da lì anche se non sono dimensioni). Una tabella Dimensione
 * ha già tutti i suoi valori, anche quelli senza righe nei Fatti.
 *
 * Il nome del campo nell'indice è il nome del campo nel dataset (dopo
 * rinomine e qualifiche), non quello della colonna.
 *
 * Scrive in ch_lbi_idx_staging e poi sostituisce la partizione del dataset
 * con REPLACE PARTITION (atomico): chi interroga durante la ricostruzione
 * vede sempre la versione precedente completa.
 *
 * Va chiamato DOPO la sincronizzazione delle tabelle e PRIMA del bump della
 * dataVersion (EtlCompletionService): una versione dati nuova esiste solo
 * quando l'indice è già allineato.
 *
 * Limite noto (fase E5): le associazioni "per valore", cioè tra colonne dal
 * nome fisico diverso, non unificano ancora gli id. Se ne avvisa nel log.
 *
 * Le dimensioni salvate prima della migrazione 030, senza occorrenza, non
 * sono indicizzate: il dataset va salvato di nuovo.
 *
 * Costo: righe di ogni tabella x numero dei suoi campi indicizzati.
 */
@Service
class BitmapIndexBuilder(
    private val jdbcTemplate: JdbcTemplate,
    private val datasetService: DatasetService,
    private val importedTableRepository: ImportedTableRepository,
    private val symbolTableService: SymbolTableService
) {
    private val log = LoggerFactory.getLogger(BitmapIndexBuilder::class.java)

    private val mainTable = "ch_lbi_idx"
    private val stagingTable = "ch_lbi_idx_staging"

    fun rebuild(areaId: UUID) {
        val start = System.currentTimeMillis()

        val bozza = datasetService.carica(areaId) ?: error("Dataset $areaId non trovato")

        // L'UUID in forma stringa è sicuro da interpolare (formato fisso,
        // solo esadecimali e trattini): serve come letterale perché i
        // nomi di partizione in ALTER TABLE non accettano parametri "?".
        val partition = areaId.toString()

        // Pulizia di eventuali residui di una ricostruzione interrotta a metà.
        jdbcTemplate.execute("ALTER TABLE $stagingTable DROP PARTITION '$partition'")

        val campi = bozza.campi().filter { !it.escluso }
        val nomiDimensione = campi
            .filter { RiferimentoCampo(it.occorrenzaId, it.colonna) in bozza.dimensioni }
            .map { it.nomeCampo }
            .toSet()
        val associazioni = bozza.associazioniAttive()

        val indicizzati = nomiDimensione + associazioni.map { it.nomeCampo }
        val composte = bozza.chiaviComposte()

        associazioni.filter { it.perValore }.forEach {
            log.warn(
                "Dataset {}: il campo '{}' associa colonne con nomi fisici diversi; " +
                        "gli id non sono ancora unificati (fase E5)", areaId, it.nomeCampo
            )
        }

        var campiIndicizzati = 0
        bozza.occorrenze.forEach { occ ->
            val suoi = campi.filter { it.occorrenzaId == occ.id && it.nomeCampo in indicizzati }
            if (suoi.isEmpty()) return@forEach

            val tabella = importedTableRepository.findById(occ.importedTableId)
                ?: error("Tabella importata ${occ.importedTableId} non trovata")
            val fisica = Naming.requirePhysical(tabella.tabellaFisica, "tabella")
            check(Naming.RID_COLUMN in symbolTableService.colonneDi(fisica)) {
                "La tabella '${tabella.nomeLogico}' non ha i numeri di riga: serve un \"Ricarico completo\""
            }

            val voci = suoi.map { c ->
                val nome = Naming.requirePhysical(c.nomeCampo, "campo")
                val colonna = Naming.requirePhysical(c.colonna, "colonna")
                "('$nome', toUInt64($colonna))"
            }.toMutableList()
            // Chiavi composte che passano da questa tabella: voce con l'hash a 64 bit
            // della combinazione degli id (stesso ordine dei campi in tutte le tabelle).
            composte.filter { occ.id in it.occorrenze }.forEach { chiave ->
                val colonne = chiave.campi.map { nomeCampo ->
                    Naming.requirePhysical(suoi.first { it.nomeCampo == nomeCampo }.colonna, "colonna")
                }
                val nome = Naming.requirePhysical(chiave.nome, "campo")
                voci += "('$nome', cityHash64(${colonne.joinToString(", ")}))"
            }
            val coppie = voci.joinToString(", ")

            jdbcTemplate.execute(
                """
                INSERT INTO $stagingTable (area_id, tabella_id, campo, valore_id, righe)
                SELECT
                    toUUID('$partition'),
                    toUUID('${occ.id}'),
                    coppia.1,
                    coppia.2,
                    groupBitmapState(rid)
                FROM
                (
                    SELECT
                        ${Naming.RID_COLUMN} AS rid,
                        [$coppie] AS coppie
                    FROM $fisica
                )
                ARRAY JOIN coppie AS coppia
                GROUP BY coppia.1, coppia.2
                """.trimIndent()
            )
            campiIndicizzati += suoi.size
        }

        val righeIndice = jdbcTemplate.queryForObject(
            "SELECT count() FROM $stagingTable WHERE area_id = toUUID('$partition')",
            Long::class.java
        ) ?: 0L

        if (righeIndice == 0L) {
            // Niente da indicizzare (nessun campo o tabelle vuote): l'indice del dataset si svuota.
            jdbcTemplate.execute("ALTER TABLE $mainTable DROP PARTITION '$partition'")
        } else {
            jdbcTemplate.execute("ALTER TABLE $mainTable REPLACE PARTITION '$partition' FROM $stagingTable")
            jdbcTemplate.execute("ALTER TABLE $stagingTable DROP PARTITION '$partition'")
        }

        log.info(
            "Indice del dataset {} ricostruito: {} tabelle, {} campi indicizzati, {} valori, {} ms",
            areaId, bozza.occorrenze.size, campiIndicizzati, righeIndice, System.currentTimeMillis() - start
        )
    }
}