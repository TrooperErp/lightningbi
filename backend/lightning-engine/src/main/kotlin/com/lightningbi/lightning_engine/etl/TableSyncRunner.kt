// FILE: src/main/kotlin/com/lightningbi/lightning_engine/etl/TableSyncRunner.kt
package com.lightningbi.lightning_engine.etl

import com.lightningbi.lightning_engine.connector.ConnectionOrchestrator
import com.lightningbi.lightning_engine.model.EtlRun
import com.lightningbi.lightning_engine.model.EtlStato
import com.lightningbi.lightning_engine.model.ImportedColumn
import com.lightningbi.lightning_engine.model.ImportedTable
import com.lightningbi.lightning_engine.model.ModalitaSync
import com.lightningbi.lightning_engine.model.RuoloTabella
import com.lightningbi.lightning_engine.model.SorgenteTabella
import com.lightningbi.lightning_engine.model.TableSync
import com.lightningbi.lightning_engine.repository.CampoTestoRepository
import com.lightningbi.lightning_engine.repository.EtlRunRepository
import com.lightningbi.lightning_engine.repository.ImportedTableRepository
import com.lightningbi.lightning_engine.repository.SorgenteTabellaRepository
import com.lightningbi.lightning_engine.repository.TableSyncRepository
import com.lightningbi.lightning_engine.service.ColumnProposal
import com.lightningbi.lightning_engine.service.EmailService
import com.lightningbi.lightning_engine.service.Naming
import com.lightningbi.lightning_engine.service.SymbolLookupService
import com.lightningbi.lightning_engine.service.SymbolTableService
import com.lightningbi.lightning_engine.service.TableImportService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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
 * Sincronizza UNA tabella importata dalle sue sorgenti: completa (si ricrea la
 * tabella e si ricarica tutto) oppure incrementale (si sostituiscono solo le
 * unità cambiate). Non conosce i dataset: chi la chiama si occupa di
 * ricostruire gli indici di quelli che usano la tabella.
 *
 * SORGENTI: la connessione della tabella (numero 0) più quelle aggiuntive
 * ([SorgenteTabella], numeri 1..255), accodate nella stessa tabella come il
 * CONCATENATE di Qlik. Ogni riga porta il numero della sua sorgente nella
 * colonna nascosta lbi_src. Per ogni sorgente aggiuntiva si applicano le sue
 * regole ([RegoleSorgente]): ditta forzata sul campo azienda e prefisso sulle
 * colonne chiave, così le chiavi di database diversi non collidono.
 *
 * NOMI: verso la SORGENTE si usa il nome della colonna ([ImportedColumn.nome]);
 * verso CLICKHOUSE il nome del campo ([ImportedColumn.nomeCampo]). Le colonne
 * dell'unità, che l'admin sceglie tra i nomi delle colonne, si traducono con [campoDi].
 *
 * COMPLETA: pipeline a tre stadi (lettura -> trasformazione -> inserimento) su
 * una tabella ombra, scambiata con quella vera a fine carico.
 *
 * INCREMENTALE, per sorgente: le unità cambiate si riconoscono con una query
 * scritta dall'admin (datastamp), le righe vecchie si tolgono per chiave (solo
 * quelle della sorgente) e si reinseriscono quelle rilette; le unità sparite si
 * tolgono confrontando le chiavi. Le query si eseguono su ogni sorgente: il
 * database di ogni sorgente deve avere le stesse viste.
 *
 * ClickHouse non ha transazioni: se una incrementale si ferma a metà, l'ora
 * dell'ultima sincronizzazione NON avanza e al giro dopo le stesse unità
 * risultano di nuovo cambiate.
 *
 * Limite: l'ora di riferimento è unica per tabella (la più vecchia tra quelle
 * delle sorgenti). Va bene finché gli orologi dei database sono allineati.
 */
@Service
class TableSyncRunner(
    private val importedTableRepository: ImportedTableRepository,
    private val tableSyncRepository: TableSyncRepository,
    private val etlRunRepository: EtlRunRepository,
    private val sorgenteTabellaRepository: SorgenteTabellaRepository,
    private val connectionOrchestrator: ConnectionOrchestrator,
    private val transformService: TransformService,
    private val loaderService: LoaderService,
    private val symbolTableService: SymbolTableService,
    private val symbolLookupService: SymbolLookupService,
    private val redisTemplate: StringRedisTemplate,
    private val emailService: EmailService,
    private val tableImportService: TableImportService,
    private val campoTestoRepository: CampoTestoRepository,
    /** Massimo di unità restituite da una query di chiavi. Superato = errore bloccante. */
    @Value("\${lbi.sync.keys-max-rows:200000}") private val keysMaxRows: Int,
    @Value("\${lbi.sync.keys-timeout-seconds:600}") private val keysTimeoutSeconds: Int,
    /** Se le cancellazioni rilevate superano questa percentuale delle unità, la sincronizzazione si ferma. */
    @Value("\${lbi.sync.max-delete-percent:50}") private val maxDeletePercent: Int,
    /** Nome del campo azienda: le sorgenti con ditta forzata lo sovrascrivono. */
    @Value("\${lbi.azienda.campo:CODICE_DITTA}") campoAzienda: String
) {
    private val log = LoggerFactory.getLogger(TableSyncRunner::class.java)

    private val campoDitta = Naming.column(campoAzienda)

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

    /** Una sorgente da cui leggere: numero (lbi_src), connessione, regole e nome per i log. */
    private class Sorgente(val numero: Int, val connectionId: UUID, val regole: RegoleSorgente, val nome: String)

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
        val nomeOrigine = tabella.nomeOrigine
            ?: throw IllegalStateException("La tabella '${tabella.nomeLogico}' non ha il nome di origine")
        val schema = tabella.schemaOrigine
        val sorgenti = sorgentiDi(tabella)

        // Calendario: per le colonne data di tabelle importate prima di questo passo (o con
        // componenti nuovi in configurazione) crea i campi derivati mancanti.
        tableImportService.assicuraCalendario(tabella.id)

        val colonne = importedTableRepository.findColumnsByTable(tabella.id)
        require(colonne.isNotEmpty()) { "La tabella '${tabella.nomeLogico}' non ha colonne importate" }
        val config = tableSyncRepository.findByTable(tabella.id) ?: TableSync(importedTableId = tabella.id)

        progresso("${tabella.nomeLogico}: controllo delle colonne sulle sorgenti")
        sorgenti.forEach { controllaColonne(it, schema, nomeOrigine, tabella, colonne) }

        // L'ora di riferimento è quella delle SORGENTI, presa prima di leggere qualsiasi
        // cosa; con più sorgenti la più vecchia, per non saltare modifiche.
        val inizio = sorgenti.minOf { connectionOrchestrator.sourceNow(it.connectionId) }

        // Lato ClickHouse si usa il NOME CAMPO: colonne fisiche, copie numeriche, symbol table.
        val fisiche = colonne.map { Naming.column(it.nomeCampo) }
        val numeriche = colonne.filter { !it.isChiave && ColumnProposal.isNumerico(it.tipo) }.map { it.nomeCampo }
        val colonneCaricate = fisiche + numeriche.map { Naming.numericColumn(it) }
        // ORDER BY: chiavi di JOIN e colonne dell'unità (le cancellazioni le usano).
        val ordinamento = (colonne.filter { it.isChiave }.map { it.nomeCampo } +
                config.colonneUnita.mapNotNull { campoDi(colonne, it) })
            .filter { Naming.column(it) in fisiche }
            .distinctBy { Naming.column(it) }

        colonne.forEach { symbolTableService.createSymbolTable(it.nomeCampo) }

        val completa = forzaCompleta ||
                config.modalita == ModalitaSync.COMPLETA ||
                config.ultimaSyncInizio == null ||
                symbolTableService.colonneDi(tabella.tabellaFisica) !=
                (colonneCaricate + Naming.RID_COLUMN + Naming.SRC_COLUMN).toSet()

        val esito = if (completa) {
            sincronizzaCompleta(tabella, colonne, numeriche, ordinamento, colonneCaricate, sorgenti, schema, nomeOrigine, progresso, rinnova)
        } else {
            var totale = EsitoSync(ModalitaSync.INCREMENTALE, 0, 0, 0, 0)
            sorgenti.forEach { s ->
                val parziale = sincronizzaIncrementale(tabella, colonne, config, colonneCaricate, s, schema, nomeOrigine, progresso, rinnova)
                totale = totale.copy(
                    righeCaricate = totale.righeCaricate + parziale.righeCaricate,
                    righeScartate = totale.righeScartate + parziale.righeScartate,
                    unitaSostituite = totale.unitaSostituite + parziale.unitaSostituite,
                    unitaEliminate = totale.unitaEliminate + parziale.unitaEliminate
                )
            }
            totale
        }

        // Anche dopo una completa: così la prima incrementale può partire da qui.
        tableSyncRepository.updateUltimaSync(tabella.id, inizio)
        return esito
    }

    /** La sorgente principale (la connessione della tabella) e quelle aggiuntive, in ordine di numero. */
    private fun sorgentiDi(tabella: ImportedTable): List<Sorgente> {
        val principale = tabella.connectionId
            ?: throw IllegalStateException("La tabella '${tabella.nomeLogico}' non ha una connessione")
        val nomePrincipale = connectionOrchestrator.findById(principale)?.nome ?: "principale"
        val aggiuntive = sorgenteTabellaRepository.findByTable(tabella.id).map { s ->
            val connessione = connectionOrchestrator.findById(s.connectionId)
                ?: throw IllegalStateException("Connessione della sorgente ${s.ordine} di '${tabella.nomeLogico}' non trovata")
            Sorgente(
                numero = s.ordine,
                connectionId = s.connectionId,
                regole = RegoleSorgente(
                    campoDitta = campoDitta,
                    dittaForzata = s.dittaForzata,
                    prefissoChiavi = s.prefissoChiavi,
                    prefissiTecnici = ColumnProposal.lista(connessione.parametri, ColumnProposal.PREFISSI_COLONNA_CHIAVE)
                ),
                nome = connessione.nome
            )
        }
        return listOf(Sorgente(SorgenteTabella.PRINCIPALE, principale, RegoleSorgente.NESSUNA, nomePrincipale)) + aggiuntive
    }

    /**
     * Il nome campo (quindi la colonna fisica su ClickHouse) della colonna con quel nome sulla sorgente,
     * confrontati come identificatori. Null se la colonna non è tra quelle importate.
     */
    private fun campoDi(colonne: List<ImportedColumn>, nomeColonna: String): String? =
        colonne.firstOrNull { Naming.column(it.nome) == Naming.column(nomeColonna) }?.nomeCampo

    /** Ogni colonna importata deve esistere ancora sulla sorgente. Le nuove non si segnalano. */
    private fun controllaColonne(
        sorgente: Sorgente,
        schema: String?,
        nomeOrigine: String,
        tabella: ImportedTable,
        colonne: List<ImportedColumn>
    ) {
        val reali = try {
            connectionOrchestrator.listColumns(sorgente.connectionId, schema, nomeOrigine).map { it.name.lowercase() }.toSet()
        } catch (e: Exception) {
            // L'estrazione darà l'errore vero.
            log.warn("Impossibile leggere le colonne di '{}' sulla sorgente '{}': proseguo", tabella.nomeLogico, sorgente.nome, e)
            return
        }
        // I campi derivati (calendario) non esistono sulla sorgente: li calcola la sincronizzazione.
        val mancanti = colonne.filter { !it.derivata }.map { it.nome }.filter { it.lowercase() !in reali }
        check(mancanti.isEmpty()) {
            "Sulla sorgente '${sorgente.nome}' non ci sono più le colonne importate di '${tabella.nomeLogico}': " +
                    "${mancanti.joinToString(", ")}. La sincronizzazione è bloccata per evitare un fallimento a metà."
        }
    }

    /**
     * Il valore (già normalizzato) come è scritto su ClickHouse per quella sorgente: ditta forzata
     * sul campo azienda, prefisso sulle colonne chiave. Deve seguire le stesse regole di
     * TransformService.colonnaGrezza.
     */
    private fun regolato(colonna: ImportedColumn, testo: String, regole: RegoleSorgente): String {
        if (colonna.derivata) return testo
        val forzata = regole.dittaForzata
        if (forzata != null && Naming.column(colonna.nomeCampo) == regole.campoDitta) return forzata.toString()
        val prefisso = regole.prefissoChiavi
        if (prefisso != null &&
            (colonna.isChiave || regole.prefissiTecnici.any { colonna.nome.lowercase().startsWith(it.lowercase()) })
        ) return prefisso + testo
        return testo
    }

    // ================= Completa =================

    private fun sincronizzaCompleta(
        tabella: ImportedTable,
        colonne: List<ImportedColumn>,
        numeriche: List<String>,
        ordinamento: List<String>,
        colonneCaricate: List<String>,
        sorgenti: List<Sorgente>,
        schema: String?,
        nomeOrigine: String,
        progresso: (String) -> Unit,
        rinnova: () -> Unit
    ): EsitoSync {
        progresso("${tabella.nomeLogico}: preparo il ricarico")
        // Si carica in una tabella ombra e a fine carico la si scambia con quella vera:
        // chi legge vede sempre dati completi, e un errore a metà lascia intatta la vecchia.
        val ombra = "${tabella.tabellaFisica}__new"
        symbolTableService.dropTable(ombra) // avanzo di un tentativo interrotto
        symbolTableService.createImportedTable(
            tabellaFisica = ombra,
            colonneId = colonne.map { it.nomeCampo },
            colonneNumeriche = numeriche,
            colonneOrdinamento = ordinamento
        )

        // Pipeline a tre stadi: lettura (questo thread) -> trasformazione -> inserimento.
        // Code di un blocco: la memoria resta limitata a pochi blocchi.
        val daTrasformare = ArrayBlockingQueue<Any>(1)
        val daInserire = ArrayBlockingQueue<Any>(1)
        val errore = AtomicReference<Throwable?>(null)
        val chiaveObbligatoria = tabella.ruolo == RuoloTabella.DIMENSIONE

        // Scritti solo dal thread di inserimento; letti dopo il join (che ne garantisce la visibilità).
        var prossimoRid = 0L
        var caricate = 0L
        var scartate = 0L

        val trasforma = Thread {
            try {
                while (true) {
                    val m = prendi(daTrasformare, errore)
                    if (m === FINE) break
                    val letto = m as Letto
                    val t = System.nanoTime()
                    val blocco = transformService.transform(letto.righe, colonne, chiaveObbligatoria, letto.regole)
                    metti(
                        daInserire,
                        Trasformato(letto.numero, letto.sorgente, letto.righe.size, letto.msLettura, blocco, (System.nanoTime() - t) / 1_000_000),
                        errore
                    )
                }
                metti(daInserire, FINE, errore)
            } catch (e: Throwable) {
                errore.compareAndSet(null, e)
            }
        }.apply { name = "sync-${tabella.nomeLogico}-trasforma"; isDaemon = true }

        val inserisce = Thread {
            try {
                while (true) {
                    val m = prendi(daInserire, errore)
                    if (m === FINE) break
                    val tr = m as Trasformato
                    val t = System.nanoTime()
                    prossimoRid = loaderService.load(ombra, tr.blocco, colonneCaricate, prossimoRid, tr.sorgente)
                    val msInserimento = (System.nanoTime() - t) / 1_000_000
                    caricate += tr.blocco.righe
                    scartate += tr.blocco.scartate
                    log.info(
                        "[sync] {} blocco {} (sorgente {}, {} righe): lettura {} ms, trasformazione {} ms, inserimento {} ms",
                        tabella.nomeLogico, tr.numero, tr.sorgente, tr.righeLette,
                        tr.msLettura, tr.msTrasformazione, msInserimento
                    )
                    progresso("${tabella.nomeLogico}: $caricate righe caricate")
                }
            } catch (e: Throwable) {
                errore.compareAndSet(null, e)
            }
        }.apply { name = "sync-${tabella.nomeLogico}-inserisce"; isDaemon = true }

        val inizioTotale = System.nanoTime()
        var scambiata = false
        try {
            trasforma.start()
            inserisce.start()
            try {
                var numero = 0
                // Le sorgenti si leggono una dopo l'altra verso la stessa ombra.
                for (s in sorgenti) {
                    val inizioSorgente = System.nanoTime()
                    connectionOrchestrator.extract(s.connectionId, schema, nomeOrigine) { righe ->
                        // La lettura è pigra: il tempo fino alla consegna del blocco è lettura dalla sorgente.
                        var t = System.nanoTime()
                        righe.chunked(chunkSize).forEach { blocco ->
                            val msLettura = (System.nanoTime() - t) / 1_000_000
                            rinnova()
                            numero++
                            metti(daTrasformare, Letto(numero, s.numero, s.regole, blocco, msLettura), errore)
                            t = System.nanoTime()
                        }
                    }
                    log.info(
                        "[sync] {}: sorgente {} ('{}') letta in {} s",
                        tabella.nomeLogico, s.numero, s.nome, (System.nanoTime() - inizioSorgente) / 1_000_000_000
                    )
                }
                metti(daTrasformare, FINE, errore)
            } catch (e: Throwable) {
                // Ferma gli altri stadi; l'errore vero (se è di un altro stadio) è già in [errore].
                errore.compareAndSet(null, e)
            }
            trasforma.join()
            inserisce.join()
            errore.get()?.let { throw it }

            log.info("[sync] {}: {} righe in {} s", tabella.nomeLogico, caricate, (System.nanoTime() - inizioTotale) / 1_000_000_000)
            progresso("${tabella.nomeLogico}: sostituisco la tabella")
            symbolTableService.sostituisciTabella(ombra, tabella.tabellaFisica)
            scambiata = true
        } finally {
            if (!scambiata) {
                errore.compareAndSet(null, IllegalStateException("Sincronizzazione interrotta"))
                trasforma.join(30_000)
                inserisce.join(30_000)
                try {
                    symbolTableService.dropTable(ombra)
                } catch (e: Exception) {
                    log.warn("Pulizia della tabella ombra '{}' fallita", ombra, e)
                }
            }
        }
        if (caricate == 0L && scartate == 0L) {
            log.warn("La tabella '{}' non ha restituito righe", tabella.nomeLogico)
        }
        return EsitoSync(ModalitaSync.COMPLETA, caricate, scartate, 0, 0)
    }

    // ---------- pipeline ----------

    /** Fine del flusso in una coda della pipeline. */
    private object FINE

    private class Letto(
        val numero: Int,
        val sorgente: Int,
        val regole: RegoleSorgente,
        val righe: List<Map<String, Any?>>,
        val msLettura: Long
    )

    /** Non tiene il blocco letto: le righe grezze si liberano appena trasformate. */
    private class Trasformato(
        val numero: Int,
        val sorgente: Int,
        val righeLette: Int,
        val msLettura: Long,
        val blocco: BloccoColonne,
        val msTrasformazione: Long
    )

    /** Mette in coda aspettando il posto; si ferma se un altro stadio ha fallito. */
    private fun metti(coda: ArrayBlockingQueue<Any>, elemento: Any, errore: AtomicReference<Throwable?>) {
        while (!coda.offer(elemento, 200, TimeUnit.MILLISECONDS)) {
            if (errore.get() != null) throw PipelineFermata()
        }
    }

    /** Prende dalla coda aspettando un elemento; si ferma se un altro stadio ha fallito. */
    private fun prendi(coda: ArrayBlockingQueue<Any>, errore: AtomicReference<Throwable?>): Any {
        while (true) {
            coda.poll(200, TimeUnit.MILLISECONDS)?.let { return it }
            if (errore.get() != null) throw PipelineFermata()
        }
    }

    /** Uno stadio si ferma perché un altro è fallito: non è l'errore vero, che resta quello del primo. */
    private class PipelineFermata : RuntimeException("Pipeline fermata da un errore in un altro stadio")

    // ================= Incrementale =================

    /** Incrementale di UNA sorgente: tocca solo le righe con il suo lbi_src. */
    private fun sincronizzaIncrementale(
        tabella: ImportedTable,
        colonne: List<ImportedColumn>,
        config: TableSync,
        colonneCaricate: List<String>,
        sorgente: Sorgente,
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
        val etichetta = "${tabella.nomeLogico} (${sorgente.nome})"

        // Colonne dell'unità: nomi sulla SORGENTE (query delle chiavi, estrazione) e,
        // tradotti, nomi campo su CLICKHOUSE (symbol table, cancellazioni, confronto chiavi).
        val colonneUnita = config.colonneUnita
        val colonneUnitaImportate = colonneUnita.map { nome ->
            colonne.firstOrNull { Naming.column(it.nome) == Naming.column(nome) }
                ?: error("La colonna dell'unità '$nome' non è tra le colonne importate di '${tabella.nomeLogico}'")
        }
        val campiUnita = colonneUnitaImportate.map { it.nomeCampo }

        /** La chiave come è scritta su ClickHouse per questa sorgente, o null se ha un valore vuoto. */
        fun chiaveScritta(chiave: List<Any?>): List<String>? {
            val n = chiave.mapIndexed { j, v ->
                transformService.normalizza(v)?.let { regolato(colonneUnitaImportate[j], it, sorgente.regole) }
            }
            return if (n.any { it == null }) null else n.map { it!! }
        }

        // 1) Unità da rileggere: cambiate più quelle da rileggere sempre. Senza doppioni:
        //    la chiave si confronta come è scritta su ClickHouse; si rilegge con quella della sorgente.
        progresso("$etichetta: cerco le unità cambiate")
        val daRileggere = LinkedHashMap<List<String>, List<Any?>>()
        var senzaChiave = 0
        fun aggiungi(chiavi: List<List<Any?>>) {
            for (chiave in chiavi) {
                val scritta = chiaveScritta(chiave)
                if (scritta == null) {
                    senzaChiave++
                    continue
                }
                daRileggere.putIfAbsent(scritta, chiave)
            }
        }
        rinnova()
        aggiungi(
            connectionOrchestrator.runKeyQuery(
                sorgente.connectionId, config.queryCambiati, riferimento, colonneUnita, keysMaxRows, keysTimeoutSeconds
            ).chiavi
        )
        if (!config.querySempre.isNullOrBlank()) {
            rinnova()
            aggiungi(
                connectionOrchestrator.runKeyQuery(
                    sorgente.connectionId, config.querySempre, riferimento, colonneUnita, keysMaxRows, keysTimeoutSeconds
                ).chiavi
            )
        }
        if (senzaChiave > 0) {
            log.warn("'{}': {} unità con una colonna chiave vuota, non identificabili e ignorate", etichetta, senzaChiave)
        }

        var caricate = 0L
        var scartate = 0L

        // 2) Sostituzione: si cancellano le righe vecchie delle unità già presenti (solo di
        //    questa sorgente) e si rileggono. Una chiave senza id è una unità nuova.
        if (daRileggere.isNotEmpty()) {
            progresso("$etichetta: sostituisco ${daRileggere.size} unità")
            rinnova()
            var prossimoRid = loaderService.prossimoRid(tabella.tabellaFisica)
            val esistenti = chiaviComeId(campiUnita, daRileggere.keys.toList())
            loaderService.deleteUnits(tabella.tabellaFisica, campiUnita, esistenti, sorgente.numero)

            connectionOrchestrator.extractUnits(
                sorgente.connectionId, schema, nomeOrigine, colonneUnita, daRileggere.values.toList()
            ) { righe ->
                righe.chunked(chunkSize).forEach { blocco ->
                    rinnova()
                    val trasformato = transformService.transform(
                        blocco, colonne, chiaveObbligatoria = tabella.ruolo == RuoloTabella.DIMENSIONE, regole = sorgente.regole
                    )
                    prossimoRid = loaderService.load(tabella.tabellaFisica, trasformato, colonneCaricate, prossimoRid, sorgente.numero)
                    caricate += trasformato.righe
                    scartate += trasformato.scartate
                    progresso("$etichetta: $caricate righe riscritte")
                }
            }
        }

        // 3) Cancellazioni: unità presenti da noi e sparite dalla sorgente.
        var eliminate = 0
        if (config.confrontaCancellazioni) {
            progresso("$etichetta: cerco le unità sparite dalla sorgente")
            rinnova()
            eliminate = eliminaSparite(tabella, colonneUnita, campiUnita, sorgente, schema, nomeOrigine, rinnova, ::chiaveScritta)
        }

        return EsitoSync(ModalitaSync.INCREMENTALE, caricate, scartate, daRileggere.size.toLong(), eliminate.toLong())
    }

    /**
     * Elimina le unità di UNA sorgente che ClickHouse ha e la sorgente non ha più. Due
     * controlli bloccanti, mai silenziosi: una sorgente che restituisce zero chiavi o una
     * cancellazione di massa cancellerebbero i dati.
     */
    private fun eliminaSparite(
        tabella: ImportedTable,
        colonneUnita: List<String>,
        campiUnita: List<String>,
        sorgente: Sorgente,
        schema: String?,
        nomeOrigine: String,
        rinnova: () -> Unit,
        chiaveScritta: (List<Any?>) -> List<String>?
    ): Int {
        val inClickHouse = loaderService.distinctKeyIds(tabella.tabellaFisica, campiUnita, sorgente.numero)
        val inSorgente = HashSet<List<Long>>()

        connectionOrchestrator.extractKeys(sorgente.connectionId, schema, nomeOrigine, colonneUnita) { chiavi ->
            chiavi.chunked(5_000).forEach { blocco ->
                rinnova()
                inSorgente.addAll(chiaviComeId(campiUnita, blocco.mapNotNull { chiaveScritta(it) }))
            }
        }

        if (inClickHouse.isEmpty()) return 0
        check(inSorgente.isNotEmpty()) {
            "La sorgente '${sorgente.nome}' non ha restituito nessuna chiave per '${tabella.nomeLogico}': non elimino tutto. " +
                    "Controlla la sorgente oppure lancia il ricarico completo a mano"
        }

        val sparite = inClickHouse - inSorgente
        if (sparite.isEmpty()) return 0
        check(sparite.size.toLong() * 100 <= inClickHouse.size.toLong() * maxDeletePercent) {
            "Le unità sparite dalla sorgente '${sorgente.nome}' per '${tabella.nomeLogico}' sono ${sparite.size} su ${inClickHouse.size}: " +
                    "oltre il $maxDeletePercent%. La sincronizzazione si ferma: alza lbi.sync.max-delete-percent " +
                    "oppure lancia il ricarico completo a mano"
        }
        return loaderService.deleteUnits(tabella.tabellaFisica, campiUnita, sparite.toList(), sorgente.numero)
    }

    /**
     * Le chiavi (come sono scritte su ClickHouse, nell'ordine dei campi dell'unità) come
     * id delle symbol table dei rispettivi campi. Solo lettura: una chiave con un valore
     * senza id non compare nel risultato.
     */
    private fun chiaviComeId(campiUnita: List<String>, chiavi: List<List<String>>): List<List<Long>> {
        if (chiavi.isEmpty()) return emptyList()
        val testi = campoTestoRepository.tutti()
        val ids: List<Map<String, Long>> = campiUnita.indices.map { j ->
            symbolLookupService.findIds(
                campiUnita[j], chiavi.mapTo(HashSet()) { it[j] }, Naming.column(campiUnita[j]) in testi
            )
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