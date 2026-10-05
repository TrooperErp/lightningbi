package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.connector.ConnectionOrchestrator
import com.lightningbi.lightning_engine.connector.JdbcSourceConnector
import com.lightningbi.lightning_engine.model.ImportedColumn
import com.lightningbi.lightning_engine.model.ImportedTable
import com.lightningbi.lightning_engine.model.ModalitaSync
import com.lightningbi.lightning_engine.model.RuoloTabella
import com.lightningbi.lightning_engine.model.SourceConnection
import com.lightningbi.lightning_engine.model.TableSync
import com.lightningbi.lightning_engine.repository.ImportedTableRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.repository.TableSyncRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/** Una colonna scelta per l'importazione. */
data class ColonnaImport(val nome: String, val tipo: String, val isChiave: Boolean = false)

/** Una riga dell'elenco "Tabelle importate". */
data class TabellaImportataInfo(
    val tabella: ImportedTable,
    val connessione: String?,
    val nColonne: Int,
    /** Nomi dei dataset che usano la tabella. */
    val dataset: List<String>,
    val modalita: ModalitaSync?,
    val ultimaSyncInizio: LocalDateTime?
)

/**
 * Importazione delle tabelle (Fatti e Dimensioni) indipendente dai dataset:
 * una tabella appartiene alla connessione e si riusa in N dataset. Punto
 * unico usato dalla pagina "Tabelle importate". Solo admin, anche lato server.
 *
 * Qui si scrive solo il registry: le tabelle ClickHouse le crea l'ETL alla
 * prima sincronizzazione.
 */
@Service
class TableImportService(
    private val importedTableRepository: ImportedTableRepository,
    private val tableSyncRepository: TableSyncRepository,
    private val registryRepository: RegistryRepository,
    private val connectionOrchestrator: ConnectionOrchestrator,
    private val symbolTableService: SymbolTableService,
    private val adminGuard: AdminGuard,
    private val calendarioService: CalendarioService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun elenco(): List<TabellaImportataInfo> {
        adminGuard.requireAdmin()
        val nomiAree = registryRepository.findAllAree().associate { it.id to it.nome }
        val connessioni = connectionOrchestrator.findAll().associateBy { it.id }
        return importedTableRepository.findAll().map { t ->
            val sync = tableSyncRepository.findByTable(t.id)
            TabellaImportataInfo(
                tabella = t,
                connessione = t.connectionId?.let { connessioni[it]?.nome },
                nColonne = importedTableRepository.findColumnsByTable(t.id).size,
                dataset = importedTableRepository.findAreaIdsUsing(t.id).mapNotNull { nomiAree[it] }.sorted(),
                modalita = sync?.modalita,
                ultimaSyncInizio = sync?.ultimaSyncInizio
            )
        }.sortedBy { it.tabella.nomeLogico.lowercase() }
    }

    /** Colonne importate di una tabella (per scegliere le colonne dell'unità di sincronizzazione). */
    fun colonne(importedTableId: UUID): List<ImportedColumn> {
        adminGuard.requireAdmin()
        return importedTableRepository.findColumnsByTable(importedTableId)
    }

    /**
     * Nome fisico ClickHouse (`<motore>_<db>__<nome>`). Il nome del database
     * viene dal parametro della connessione, poi dallo schema.
     */
    fun nomeFisico(connessione: SourceConnection, schema: String?, nomeLogico: String): String {
        val db = connessione.parametri[JdbcSourceConnector.PARAM_DATABASE]?.takeIf { it.isNotBlank() }
            ?: schema?.takeIf { it.isNotBlank() }
            ?: "db"
        return Naming.importedTable(connessione.tipo, db, nomeLogico)
    }

    /**
     * Aggiunge una tabella importata. [colonne] sono quelle incluse (le
     * ignorate non si passano). Per una Dimensione [colonnaChiave] è la sua
     * chiave di JOIN e deve essere tra le colonne segnate come chiave; per un
     * Fatti è null.
     */
    @Transactional("postgresTransactionManager")
    fun aggiungi(
        connectionId: UUID,
        schema: String?,
        nomeOrigine: String,
        nomeLogico: String,
        ruolo: RuoloTabella,
        colonnaChiave: String?,
        colonne: List<ColonnaImport>
    ): ImportedTable {
        adminGuard.requireAdmin()
        val connessione = connectionOrchestrator.findById(connectionId)
            ?: throw IllegalArgumentException("Connessione non trovata")

        val nome = nomeLogico.trim()
        require(nome.isNotEmpty()) { "Indica il nome logico della tabella" }
        require(nomeOrigine.isNotBlank()) { "Indica la tabella o la view da importare" }
        require(colonne.isNotEmpty()) { "Scegli almeno una colonna" }

        val fisico = try {
            nomeFisico(connessione, schema, nome)
        } catch (e: Exception) {
            throw IllegalArgumentException("Nome tabella non utilizzabile: ${e.message}", e)
        }
        require(fisico !in importedTableRepository.findTabelleFisiche()) {
            "Esiste già una tabella importata con questo nome fisico ('$fisico'): cambia il nome logico"
        }

        val doppie = colonne.groupingBy { it.nome }.eachCount().filterValues { it > 1 }.keys
        require(doppie.isEmpty()) { "Colonne ripetute: ${doppie.joinToString(", ")}" }

        val collisioni = try {
            ColumnProposal.collisioni(colonne.map { it.nome })
        } catch (e: Exception) {
            throw IllegalArgumentException("Colonna non utilizzabile in $nome: ${e.message}", e)
        }
        require(collisioni.isEmpty()) { ColumnProposal.messaggioCollisioni(nome, collisioni) }

        if (ruolo == RuoloTabella.DIMENSIONE) {
            require(colonnaChiave != null) { "Una Dimensione ha bisogno della colonna chiave" }
            require(colonne.any { it.nome.equals(colonnaChiave, ignoreCase = true) && it.isChiave }) {
                "La colonna chiave '$colonnaChiave' deve essere tra le colonne scelte e segnata come chiave"
            }
        } else {
            require(colonnaChiave == null) { "Una tabella Fatti non ha una colonna chiave unica" }
        }

        val tabella = ImportedTable(
            id = UUID.randomUUID(),
            areaId = null,
            nomeLogico = nome,
            tabellaFisica = fisico,
            ruolo = ruolo,
            sourceId = null,
            colonnaChiave = colonnaChiave,
            connectionId = connessione.id,
            schemaOrigine = schema,
            nomeOrigine = nomeOrigine
        )
        importedTableRepository.save(tabella)
        val colonneTabella = colonne.map { ImportedColumn(UUID.randomUUID(), tabella.id, it.nome, it.tipo, it.isChiave) }
        // Come i campi derivati di Qlik: per ogni colonna data, anno, mese, giorno... (calendario comune).
        importedTableRepository.saveColumns(colonneTabella + colonneCalendario(tabella.id, colonneTabella))
        // Ogni tabella parte con la sua configurazione di sincronizzazione (completa).
        tableSyncRepository.save(TableSync(importedTableId = tabella.id))
        return tabella
    }

    /**
     * Elimina una tabella importata. Bloccata se un dataset la usa. Toglie il
     * registry (colonne e configurazione di sincronizzazione vanno in cascata)
     * e poi la tabella ClickHouse: se il drop fallisce resta una tabella
     * orfana, segnalata nel log, e non si annulla il registry.
     */
    fun elimina(id: UUID) {
        adminGuard.requireAdmin()
        val tabella = importedTableRepository.findById(id)
            ?: throw IllegalArgumentException("Tabella importata non trovata")

        val nomiAree = registryRepository.findAllAree().associate { it.id to it.nome }
        val usata = importedTableRepository.findAreaIdsUsing(id).mapNotNull { nomiAree[it] }.sorted()
        if (usata.isNotEmpty()) {
            throw IllegalStateException(
                "La tabella '${tabella.nomeLogico}' è usata dai dataset: ${usata.joinToString(", ")}. " +
                        "Toglila prima dai dataset."
            )
        }

        try {
            importedTableRepository.deleteById(id)
        } catch (e: DataIntegrityViolationException) {
            throw IllegalStateException(
                "La tabella '${tabella.nomeLogico}' è ancora referenziata (metriche o dimensioni di un dataset)", e
            )
        }

        try {
            symbolTableService.dropTable(tabella.tabellaFisica)
        } catch (e: Exception) {
            log.warn("Tabella importata '{}' eliminata dal registry ma la tabella ClickHouse '{}' non si è potuta eliminare: resta orfana",
                tabella.nomeLogico, tabella.tabellaFisica, e)
        }
    }
    /**
     * Le colonne derivate dal calendario per le colonne data di una tabella:
     * una per ogni componente configurato (anno, mese, giorno...). Salta quelle
     * che esistono già e quelle il cui nome fisico coinciderebbe con una colonna vera.
     */
    private fun colonneCalendario(tabellaId: UUID, colonne: List<ImportedColumn>): List<ImportedColumn> {
        val fisiche = colonne.map { Naming.column(it.nome) }.toMutableSet()
        val nuove = mutableListOf<ImportedColumn>()
        colonne.filter { !it.derivata && calendarioService.isData(it.tipo) }.forEach { data ->
            calendarioService.componenti().forEach { componente ->
                val esiste = colonne.any { it.derivataDa == data.nome && it.componente == componente.codice }
                if (esiste) return@forEach
                val nome = calendarioService.nomeLogico(data.nome, componente)
                if (!fisiche.add(Naming.column(nome))) {
                    log.warn("Campo derivato '{}' non creato: esiste già una colonna con lo stesso nome fisico", nome)
                    return@forEach
                }
                nuove += ImportedColumn(UUID.randomUUID(), tabellaId, nome, "calendario", false, data.nome, componente.codice)
            }
        }
        return nuove
    }

    /**
     * Aggiunge alla tabella le colonne derivate dalle date che mancano: tabelle
     * importate prima del calendario, o componenti aggiunti in configurazione.
     * La chiama la sincronizzazione (senza controllo admin: gira anche in un
     * thread a parte e si limita ad aggiungere campi derivati).
     *
     * @return quante colonne ha aggiunto
     */
    @Transactional("postgresTransactionManager")
    fun assicuraCalendario(importedTableId: UUID): Int {
        val colonne = importedTableRepository.findColumnsByTable(importedTableId)
        val nuove = colonneCalendario(importedTableId, colonne)
        if (nuove.isNotEmpty()) importedTableRepository.saveColumns(nuove)
        return nuove.size
    }
}