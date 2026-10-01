package com.lightningbi.lightning_engine.etl

import com.lightningbi.lightning_engine.connector.ConnectionOrchestrator
import com.lightningbi.lightning_engine.model.EtlRun
import com.lightningbi.lightning_engine.model.EtlStato
import com.lightningbi.lightning_engine.model.ImportedColumn
import com.lightningbi.lightning_engine.model.ImportedTable
import com.lightningbi.lightning_engine.model.ModalitaSync
import com.lightningbi.lightning_engine.model.RuoloTabella
import com.lightningbi.lightning_engine.model.TableSync
import com.lightningbi.lightning_engine.repository.EtlRunRepository
import com.lightningbi.lightning_engine.repository.ImportedTableRepository
import com.lightningbi.lightning_engine.repository.TableSyncRepository
import com.lightningbi.lightning_engine.service.ColumnProposal
import com.lightningbi.lightning_engine.service.EmailService
import com.lightningbi.lightning_engine.service.Naming
import com.lightningbi.lightning_engine.service.SymbolLookupService
import com.lightningbi.lightning_engine.service.SymbolTableService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

/** Cosa ha fatto una sincronizzazione. */
data class EsitoSync(
    /** Come è stata eseguita davvero: una incrementale senza ultima sincronizzazione parte completa. */
    val modalita: ModalitaSync,
    val righeCaricate: Long,
    val righeScartate: Long,
    val unitaSostituite: Long,
    val unitaEliminate: Long
)

/**
 * Sincronizza UNA tabella importata dalla sua sorgente: completa (si ricrea
 * la tabella e si ricarica tutto) oppure incrementale (si sostituiscono solo
 * le unità cambiate). Non conosce i dataset: chi la chiama si occupa di
 * ricostruire gli indici di quelli che usano la tabella.
 *
 * Schemi, come in Qlik: le unità cambiate si riconoscono con una query scritta
 * dall'admin (datastamp), le righe vecchie di una unità si tolgono per chiave
 * e si reinseriscono quelle rilette, le unità sparite dalla sorgente si
 * tolgono confrontando le chiavi. I dati che dipendono da altri documenti
 * (per esempio il residuo di un ordine) NON li risolve il motore: si
 * risolvono con un datastamp completo nella vista sulla sorgente o con la
 * query delle unità da rileggere sempre.
 *
 * ClickHouse non ha transazioni: se una sincronizzazione incrementale si
 * ferma a metà, alcune unità possono essere cancellate e non ancora
 * reinserite. L'ora dell'ultima sincronizzazione NON avanza, quindi al giro
 * dopo le stesse unità risultano di nuovo cambiate e vengono rilette.
 */
@Service
class TableSyncRunner(
    private val importedTableRepository: ImportedTableRepository,
    private val tableSyncRepository: TableSyncRepository,
    private val etlRunRepository: EtlRunRepository,
    private val connectionOrchestrator: ConnectionOrchestrator,
    private val transformService: TransformService,
    private val loaderService: LoaderService,
    private val symbolTableService: SymbolTableService,
    private val symbolLookupService: SymbolLookupService,
    private val redisTemplate: StringRedisTemplate,
    private val emailService: EmailService,
    /** Massimo di unità restituite da una query di chiavi. Superato = errore bloccante. */
    @Value("\${lbi.sync.keys-max-rows:200000}") private val keysMaxRows: Int,
    @Value("\${lbi.sync.keys-timeout-seconds:600}") private val keysTimeoutSeconds: Int,
    /** Se le cancellazioni rilevate superano questa percentuale delle unità, la sincronizzazione si ferma. */
    @Value("\${lbi.sync.max-delete-percent:50}") private val maxDeletePercent: Int
) {
    private val log = LoggerFactory.getLogger(TableSyncRunner::class.java)

    /** Righe per blocco: la memoria resta piatta qualunque sia la dimensione della tabella. */
    private val chunkSize = 50_000

    /** Il lock si rinnova a ogni blocco e prima delle operazioni lunghe. */
    private val lockTtl = Duration.ofMinutes(30)

    private val unlockScript = DefaultRedisScript(
        """
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("del", KEYS[1])
        else
            return 0
        end
        """.trimIndent(), Long::class.java
    )

    private val renewScript = DefaultRedisScript(
        """
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("pexpire", KEYS[1], ARGV[2])
        else
            return 0
        end
        """.trimIndent(), Long::class.java
    )

    // ================= Punto d'ingresso =================

    /**
     * @param forzaCompleta ricarico completo anche se la tabella è configurata incrementale
     * @param progresso riceve una frase per ogni passo importante (avanzamento)
     */
    fun sync(
        importedTableId: UUID,
        forzaCompleta: Boolean = false,
        progresso: (String) -> Unit = {}
    ): EsitoSync {
        val tabella = importedTableRepository.findById(importedTableId)
            ?: throw IllegalArgumentException("Tabella importata non trovata")

        val lockKey = "etl-lock:table:$importedTableId"
        val lockValue = UUID.randomUUID().toString()
        val acquisito = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, lockTtl) ?: false
        if (!acquisito) {
            throw IllegalStateException("Sincronizzazione già in corso per la tabella '${tabella.nomeLogico}'")
        }

        val run = EtlRun(
            id = UUID.randomUUID(), areaId = null, sourceId = null,
            startedAt = LocalDateTime.now(), finishedAt = null,
            stato = EtlStato.RUNNING, righeProcessate = 0, righeScartate = 0, errore = null,
            importedTableId = importedTableId
        )
        etlRunRepository.save(run)

        try {
            val esito = eseguiSync(tabella, forzaCompleta, progresso) { rinnovaLock(lockKey, lockValue) }
            etlRunRepository.update(
                run.copy(
                    finishedAt = LocalDateTime.now(),
                    stato = EtlStato.SUCCESS,
                    righeProcessate = esito.righeCaricate,
                    righeScartate = esito.righeScartate,
                    modalita = esito.modalita,
                    unitaSostituite = esito.unitaSostituite,
                    unitaEliminate = esito.unitaEliminate
                )
            )
            log.info(
                "Sincronizzazione '{}' ({}): {} righe caricate, {} scartate, {} unità sostituite, {} eliminate",
                tabella.nomeLogico, esito.modalita, esito.righeCaricate, esito.righeScartate,
                esito.unitaSostituite, esito.unitaEliminate
            )
            return esito
        } catch (e: Exception) {
            log.error("Sincronizzazione della tabella '{}' fallita", tabella.nomeLogico, e)
            val messaggio = e.message ?: e::class.qualifiedName ?: "Errore sconosciuto"
            try {
                emailService.sendAdminAlert(
                    "URGENTE - sincronizzazione fallita: tabella '${tabella.nomeLogico}'",
                    "La sincronizzazione della tabella '${tabella.nomeLogico}' è fallita con errore: $messaggio. " +
                            "Controlla i log del server."
                )
            } catch (_: Exception) {
                // L'errore vero è un altro: l'email non deve coprirlo.
            }
            etlRunRepository.update(
                run.copy(finishedAt = LocalDateTime.now(), stato = EtlStato.FAILED, errore = messaggio)
            )
            throw e
        } finally {
            try {
                redisTemplate.execute(unlockScript, listOf(lockKey), lockValue)
            } catch (e: Exception) {
                log.warn("Rilascio del lock {} fallito, scadrà da solo entro {} minuti", lockKey, lockTtl.toMinutes(), e)
            }
        }
    }

    private fun rinnovaLock(lockKey: String, lockValue: String) {
        val esito = redisTemplate.execute(renewScript, listOf(lockKey), lockValue, lockTtl.toMillis().toString())
        // Lock perso (scaduto o preso da un altro): un secondo caricamento in
        // parallelo sulla stessa tabella la corromperebbe. Meglio fermarsi.
        check(esito == 1L) { "Lock della sincronizzazione perso: la sincronizzazione si ferma per non corrompere la tabella" }
    }

    // ================= Esecuzione =================

    private fun eseguiSync(
        tabella: ImportedTable,
        forzaCompleta: Boolean,
        progresso: (String) -> Unit,
        rinnova: () -> Unit
    ): EsitoSync {
        val connectionId = tabella.connectionId
            ?: throw IllegalStateException("La tabella '${tabella.nomeLogico}' non ha una connessione")
        val nomeOrigine = tabella.nomeOrigine
            ?: throw IllegalStateException("La tabella '${tabella.nomeLogico}' non ha il nome di origine")
        val schema = tabella.schemaOrigine

        val colonne = importedTableRepository.findColumnsByTable(tabella.id)
        require(colonne.isNotEmpty()) { "La tabella '${tabella.nomeLogico}' non ha colonne importate" }
        val config = tableSyncRepository.findByTable(tabella.id) ?: TableSync(importedTableId = tabella.id)

        progresso("${tabella.nomeLogico}: controllo delle colonne sulla sorgente")
        controllaColonne(connectionId, schema, nomeOrigine, tabella, colonne)

        // L'ora di riferimento è quella della SORGENTE, presa prima di leggere
        // qualsiasi cosa: una modifica fatta mentre si sincronizza avrà un
        // datastamp posteriore e al giro dopo risulterà cambiata.
        val inizio = connectionOrchestrator.sourceNow(connectionId)

        val fisiche = colonne.map { Naming.column(it.nome) }
        val numeriche = colonne.filter { !it.isChiave && ColumnProposal.isNumerico(it.tipo) }.map { it.nome }
        val colonneCaricate = fisiche + numeriche.map { Naming.numericColumn(it) }
        // ORDER BY: chiavi di JOIN e colonne dell'unità (le cancellazioni le usano).
        val ordinamento = (colonne.filter { it.isChiave }.map { it.nome } + config.colonneUnita)
            .filter { Naming.column(it) in fisiche }
            .distinctBy { Naming.column(it) }

        colonne.forEach { symbolTableService.createSymbolTable(it.nome) }

        val completa = forzaCompleta ||
                config.modalita == ModalitaSync.COMPLETA ||
                config.ultimaSyncInizio == null ||
                symbolTableService.colonneDi(tabella.tabellaFisica) != colonneCaricate.toSet()

        val esito = if (completa) {
            sincronizzaCompleta(tabella, colonne, numeriche, ordinamento, colonneCaricate, connectionId, schema, nomeOrigine, progresso, rinnova)
        } else {
            sincronizzaIncrementale(tabella, colonne, config, colonneCaricate, connectionId, schema, nomeOrigine, progresso, rinnova)
        }

        // Anche dopo una completa: così la prima incrementale può partire da qui.
        tableSyncRepository.updateUltimaSync(tabella.id, inizio)
        return esito
    }

    /** Ogni colonna importata deve esistere ancora sulla sorgente. Le nuove non si segnalano (le ignorate non sono registrate). */
    private fun controllaColonne(
        connectionId: UUID,
        schema: String?,
        nomeOrigine: String,
        tabella: ImportedTable,
        colonne: List<ImportedColumn>
    ) {
        val reali = try {
            connectionOrchestrator.listColumns(connectionId, schema, nomeOrigine).map { it.name.lowercase() }.toSet()
        } catch (e: Exception) {
            // L'estrazione darà l'errore vero.
            log.warn("Impossibile leggere le colonne della sorgente per '{}': proseguo", tabella.nomeLogico, e)
            return
        }
        val mancanti = colonne.map { it.nome }.filter { it.lowercase() !in reali }
        check(mancanti.isEmpty()) {
            "Nella sorgente non ci sono più le colonne importate di '${tabella.nomeLogico}': ${mancanti.joinToString(", ")}. " +
                    "La sincronizzazione è bloccata per evitare un fallimento a metà."
        }
    }

    // ================= Completa =================

    private fun sincronizzaCompleta(
        tabella: ImportedTable,
        colonne: List<ImportedColumn>,
        numeriche: List<String>,
        ordinamento: List<String>,
        colonneCaricate: List<String>,
        connectionId: UUID,
        schema: String?,
        nomeOrigine: String,
        progresso: (String) -> Unit,
        rinnova: () -> Unit
    ): EsitoSync {
        progresso("${tabella.nomeLogico}: ricreo la tabella")
        // DROP e CREATE (non TRUNCATE): lo schema coincide sempre con il registry.
        symbolTableService.dropTable(tabella.tabellaFisica)
        symbolTableService.createImportedTable(
            tabellaFisica = tabella.tabellaFisica,
            colonneId = colonne.map { it.nome },
            colonneNumeriche = numeriche,
            colonneOrdinamento = ordinamento
        )

        var caricate = 0L
        var scartate = 0L
        connectionOrchestrator.extract(connectionId, schema, nomeOrigine) { righe ->
            righe.chunked(chunkSize).forEach { blocco ->
                rinnova()
                val (valide, errori) = transformService.transform(
                    blocco, colonne, chiaveObbligatoria = tabella.ruolo == RuoloTabella.DIMENSIONE
                )
                loaderService.load(tabella.tabellaFisica, valide, colonneCaricate)
                caricate += valide.size
                scartate += errori.size
                progresso("${tabella.nomeLogico}: $caricate righe caricate")
            }
        }
        if (caricate == 0L && scartate == 0L) {
            log.warn("La tabella '{}' non ha restituito righe", tabella.nomeLogico)
        }
        return EsitoSync(ModalitaSync.COMPLETA, caricate, scartate, 0, 0)
    }

    // ================= Incrementale =================

    private fun sincronizzaIncrementale(
        tabella: ImportedTable,
        colonne: List<ImportedColumn>,
        config: TableSync,
        colonneCaricate: List<String>,
        connectionId: UUID,
        schema: String?,
        nomeOrigine: String,
        progresso: (String) -> Unit,
        rinnova: () -> Unit
    ): EsitoSync {
        require(config.colonneUnita.isNotEmpty()) {
            "La tabella '${tabella.nomeLogico}' è incrementale ma non ha le colonne dell'unità"
        }
        require(!config.queryCambiati.isNullOrBlank()) {
            "La tabella '${tabella.nomeLogico}' è incrementale ma non ha la query delle unità cambiate"
        }
        val ultima = config.ultimaSyncInizio!!
        val riferimento = ultima.minusSeconds(config.margineSecondi.toLong())
        val colonneUnita = config.colonneUnita

        // 1) Unità da rileggere: cambiate più quelle da rileggere sempre. Senza
        //    doppioni: la chiave si confronta nella forma normalizzata.
        progresso("${tabella.nomeLogico}: cerco le unità cambiate")
        val daRileggere = LinkedHashMap<List<String>, List<Any?>>()
        var senzaChiave = 0
        fun aggiungi(chiavi: List<List<Any?>>) {
            for (chiave in chiavi) {
                val normalizzata = chiave.map { transformService.normalizza(it) }
                if (normalizzata.any { it == null }) {
                    senzaChiave++
                    continue
                }
                daRileggere.putIfAbsent(normalizzata.map { it!! }, chiave)
            }
        }
        rinnova()
        aggiungi(
            connectionOrchestrator.runKeyQuery(
                connectionId, config.queryCambiati, riferimento, colonneUnita, keysMaxRows, keysTimeoutSeconds
            ).chiavi
        )
        if (!config.querySempre.isNullOrBlank()) {
            rinnova()
            aggiungi(
                connectionOrchestrator.runKeyQuery(
                    connectionId, config.querySempre, riferimento, colonneUnita, keysMaxRows, keysTimeoutSeconds
                ).chiavi
            )
        }
        if (senzaChiave > 0) {
            log.warn("'{}': {} unità con una colonna chiave vuota, non identificabili e ignorate", tabella.nomeLogico, senzaChiave)
        }

        var caricate = 0L
        var scartate = 0L

        // 2) Sostituzione: si cancellano le righe vecchie delle unità già presenti
        //    e si rileggono dalla sorgente. Una chiave senza id nella symbol table
        //    è una unità nuova: non ha righe da cancellare.
        if (daRileggere.isNotEmpty()) {
            progresso("${tabella.nomeLogico}: sostituisco ${daRileggere.size} unità")
            rinnova()
            val esistenti = chiaviComeId(colonneUnita, daRileggere.keys.toList())
            loaderService.deleteUnits(tabella.tabellaFisica, colonneUnita, esistenti)

            connectionOrchestrator.extractUnits(
                connectionId, schema, nomeOrigine, colonneUnita, daRileggere.values.toList()
            ) { righe ->
                righe.chunked(chunkSize).forEach { blocco ->
                    rinnova()
                    val (valide, errori) = transformService.transform(
                        blocco, colonne, chiaveObbligatoria = tabella.ruolo == RuoloTabella.DIMENSIONE
                    )
                    loaderService.load(tabella.tabellaFisica, valide, colonneCaricate)
                    caricate += valide.size
                    scartate += errori.size
                    progresso("${tabella.nomeLogico}: $caricate righe riscritte")
                }
            }
        }

        // 3) Cancellazioni: unità presenti da noi e sparite dalla sorgente.
        var eliminate = 0
        if (config.confrontaCancellazioni) {
            progresso("${tabella.nomeLogico}: cerco le unità sparite dalla sorgente")
            rinnova()
            eliminate = eliminaSparite(tabella, colonneUnita, connectionId, schema, nomeOrigine, rinnova)
        }

        return EsitoSync(ModalitaSync.INCREMENTALE, caricate, scartate, daRileggere.size.toLong(), eliminate.toLong())
    }

    /**
     * Elimina le unità che ClickHouse ha e la sorgente non ha più. Due
     * controlli bloccanti, mai silenziosi: una sorgente che restituisce zero
     * chiavi (vista vuota per un problema) o una cancellazione di massa
     * cancellerebbero i dati. In quel caso si lancia il ricarico completo a mano.
     */
    private fun eliminaSparite(
        tabella: ImportedTable,
        colonneUnita: List<String>,
        connectionId: UUID,
        schema: String?,
        nomeOrigine: String,
        rinnova: () -> Unit
    ): Int {
        val inClickHouse = loaderService.distinctKeyIds(tabella.tabellaFisica, colonneUnita)
        val inSorgente = HashSet<List<Long>>()

        connectionOrchestrator.extractKeys(connectionId, schema, nomeOrigine, colonneUnita) { chiavi ->
            chiavi.chunked(5_000).forEach { blocco ->
                rinnova()
                val normalizzate = blocco.mapNotNull { chiave ->
                    val n = chiave.map { transformService.normalizza(it) }
                    if (n.any { it == null }) null else n.map { it!! }
                }
                inSorgente.addAll(chiaviComeId(colonneUnita, normalizzate))
            }
        }

        if (inClickHouse.isEmpty()) return 0
        check(inSorgente.isNotEmpty()) {
            "La sorgente non ha restituito nessuna chiave per '${tabella.nomeLogico}': non elimino tutto. " +
                    "Controlla la sorgente oppure lancia il ricarico completo a mano"
        }

        val sparite = inClickHouse - inSorgente
        if (sparite.isEmpty()) return 0
        check(sparite.size.toLong() * 100 <= inClickHouse.size.toLong() * maxDeletePercent) {
            "Le unità sparite dalla sorgente per '${tabella.nomeLogico}' sono ${sparite.size} su ${inClickHouse.size}: " +
                    "oltre il $maxDeletePercent%. La sincronizzazione si ferma: alza lbi.sync.max-delete-percent " +
                    "oppure lancia il ricarico completo a mano"
        }
        return loaderService.deleteUnits(tabella.tabellaFisica, colonneUnita, sparite.toList())
    }

    /**
     * Le chiavi (già normalizzate, nell'ordine delle colonne dell'unità) come
     * id delle symbol table dei rispettivi campi. Solo lettura: una chiave con
     * un valore senza id non compare nel risultato.
     */
    private fun chiaviComeId(colonneUnita: List<String>, chiavi: List<List<String>>): List<List<Long>> {
        if (chiavi.isEmpty()) return emptyList()
        val ids: List<Map<String, Long>> = colonneUnita.indices.map { j ->
            symbolLookupService.findIds(colonneUnita[j], chiavi.mapTo(HashSet()) { it[j] })
        }
        val risultato = ArrayList<List<Long>>(chiavi.size)
        for (chiave in chiavi) {
            val lista = ArrayList<Long>(chiave.size)
            var completa = true
            for (j in chiave.indices) {
                val id = ids[j][chiave[j]]
                if (id == null) {
                    completa = false
                    break
                }
                lista.add(id)
            }
            if (completa) risultato.add(lista)
        }
        return risultato
    }
}