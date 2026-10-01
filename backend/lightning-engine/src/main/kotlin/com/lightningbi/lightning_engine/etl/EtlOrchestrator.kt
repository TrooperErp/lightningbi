package com.lightningbi.lightning_engine.etl

import com.lightningbi.lightning_engine.connector.ConnectionOrchestrator
import com.lightningbi.lightning_engine.model.Area
import com.lightningbi.lightning_engine.model.AreaDimensione
import com.lightningbi.lightning_engine.model.AreaMetrica
import com.lightningbi.lightning_engine.model.AreaSource
import com.lightningbi.lightning_engine.model.EtlRun
import com.lightningbi.lightning_engine.model.EtlStato
import com.lightningbi.lightning_engine.model.ImportedColumn
import com.lightningbi.lightning_engine.model.ImportedTable
import com.lightningbi.lightning_engine.model.RuoloTabella
import com.lightningbi.lightning_engine.model.SyncMode
import com.lightningbi.lightning_engine.repository.EtlRunRepository
import com.lightningbi.lightning_engine.repository.ImportedTableRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.service.BitmapIndexBuilder
import com.lightningbi.lightning_engine.service.EmailService
import com.lightningbi.lightning_engine.service.EtlCompletionService
import com.lightningbi.lightning_engine.service.Naming
import com.lightningbi.lightning_engine.service.SymbolTableService
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.util.UUID

@Service
class EtlOrchestrator(
    private val registryRepository: RegistryRepository,
    private val etlRunRepository: EtlRunRepository,
    private val transformService: TransformService,
    private val loaderService: LoaderService,
    private val redisTemplate: StringRedisTemplate,
    private val etlCompletionService: EtlCompletionService,
    private val emailService: EmailService,
    private val connectionOrchestrator: ConnectionOrchestrator,
    private val bitmapIndexBuilder: BitmapIndexBuilder,
    private val importedTableRepository: ImportedTableRepository,
    private val symbolTableService: SymbolTableService
) {
    private val log = LoggerFactory.getLogger(EtlOrchestrator::class.java)

    private val lockTtl = Duration.ofHours(2)

    /**
     * Righe per blocco nel caricamento dei dataset a stella. L'estrazione
     * legge dalla sorgente in streaming: si trasforma e si carica un blocco
     * alla volta, così la memoria resta piatta qualunque sia il numero di
     * righe della tabella (leggerle tutte in una lista mandava la JVM in
     * OutOfMemoryError sui Fatti).
     */
    private val etlChunkSize = 50_000

    private val unlockScript = DefaultRedisScript(
        """
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("del", KEYS[1])
        else
            return 0
        end
        """.trimIndent(), Long::class.java
    )

    // ================= Punto d'ingresso =================

    /**
     * Sincronizza un dataset a schema a stella: N tabelle importate (Fatti
     * + Dimensioni), ciascuna caricata nella sua tabella ClickHouse.
     * Lock, registrazione dell'esecuzione e gestione errori sono qui.
     */
    fun runForArea(areaId: UUID, source: AreaSource) {

        require(source.areaId == areaId) {
            "La sorgente ${source.id} appartiene all'area ${source.areaId}, non a $areaId"
        }

        val lockKey = "etl-lock:$areaId:${source.id}"
        val lockValue = UUID.randomUUID().toString()
        val acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, lockTtl) ?: false

        if (!acquired) {
            throw IllegalStateException("ETL già in corso per area=$areaId source=${source.id}")
        }

        val run = EtlRun(
            id = UUID.randomUUID(), areaId = areaId, sourceId = source.id,
            startedAt = LocalDateTime.now(), finishedAt = null,
            stato = EtlStato.RUNNING, righeProcessate = 0, righeScartate = 0, errore = null
        )
        etlRunRepository.save(run)

        try {
            val area = registryRepository.findAreaById(areaId) ?: error("Area $areaId non trovata")

            val (caricate, scartate) = etlSchemaStella(area, source)

            etlRunRepository.update(
                run.copy(
                    finishedAt = LocalDateTime.now(),
                    stato = EtlStato.SUCCESS,
                    righeProcessate = caricate,
                    righeScartate = scartate
                )
            )
            log.info(
                "ETL area '{}' completato: {} righe caricate, {} scartate",
                area.nome, caricate, scartate
            )
        } catch (e: Exception) {
            log.error("ETL area {} fallito", areaId, e)
            emailService.sendAdminAlert(
                "URGENTE - ETL fallito su area $areaId",
                "L'ETL per l'area $areaId è fallito con errore: ${e.message ?: e::class.qualifiedName}. Controlla i log del server."
            )
            etlRunRepository.update(
                run.copy(
                    finishedAt = LocalDateTime.now(),
                    stato = EtlStato.FAILED,
                    // Su NullPointerException e simili message è null: senza
                    // fallback il log dell'esecuzione resterebbe muto proprio
                    // sull'errore che serve capire.
                    errore = e.message ?: e::class.qualifiedName ?: "Errore sconosciuto"
                )
            )
            throw e
        } finally {
            try {
                redisTemplate.execute(unlockScript, listOf(lockKey), lockValue)
            } catch (e: Exception) {
                log.warn("Rilascio del lock {} fallito, scadrà da solo entro {}h", lockKey, lockTtl.toHours(), e)
            }
        }
    }

    // ================= Schema a stella: N tabelle importate =================

    /**
     * Sincronizza un dataset a schema a stella. Ritorna (righe caricate,
     * righe scartate), sommate su tutte le tabelle.
     *
     * Solo FULL_RELOAD: senza una chiave di deduplica l'append incrementale
     * duplicherebbe righe, e con più tabelle collegate un caricamento
     * parziale lascerebbe Fatti e Dimensioni non allineati.
     *
     * Ordine: prima le Dimensioni, poi i Fatti, e la dataVersion sale UNA
     * sola volta a fine ciclo (completeSuccess): finché non sono caricate
     * tutte le tabelle, le cache esistenti restano valide.
     *
     * A fine caricamento si ricostruisce l'indice bitmap (BitmapIndexBuilder),
     * che fa il JOIN Fatti/Dimensioni una volta sola.
     */
    private fun etlSchemaStella(area: Area, source: AreaSource): Pair<Long, Long> {
        val areaId = area.id
        require(source.config.syncMode == SyncMode.FULL_RELOAD) {
            "I dataset a schema a stella supportano solo FULL_RELOAD (modalità attuale: ${source.config.syncMode})"
        }

        val dimensioni = registryRepository.findDimensioniByArea(areaId)
        val metriche = registryRepository.findMetricheByArea(areaId)
        require(dimensioni.isNotEmpty()) { "L'area '${area.nome}' non ha dimensioni configurate" }
        require(metriche.isNotEmpty()) { "L'area '${area.nome}' non ha metriche configurate" }

        val importate = importedTableRepository.findLinkedToArea(areaId)
        require(importate.count { it.ruolo == RuoloTabella.FATTI } == 1) {
            "L'area '${area.nome}' deve avere esattamente una tabella Fatti importata"
        }

        val dims = registryRepository.findDimensioniByIds(dimensioni.map { it.dimensioneId })
        val dimensioneNomiById = dims.associate { it.id.toString() to it.nome }

        val colonnePerTabella: Map<UUID, List<ImportedColumn>> =
            importate.associate { it.id to importedTableRepository.findColumnsByTable(it.id) }

        // Connessione, schema e nome della view sulla sorgente stanno nella
        // tabella importata.
        fun viewDi(t: ImportedTable): String =
            t.nomeOrigine ?: error("La tabella '${t.nomeLogico}' non ha il nome di origine sulla sorgente")
        fun connectionDi(t: ImportedTable): UUID =
            t.connectionId ?: error("La tabella '${t.nomeLogico}' non ha una connessione")

        checkColumnDriftStella(area, importate, colonnePerTabella)

        val syncStart = LocalDateTime.now()

        val metricheConColonna = metriche.filter { it.colonnaFisica != null }

        fun chiaviDi(t: ImportedTable): List<String> =
            colonnePerTabella[t.id].orEmpty().filter { it.isChiave }.map { Naming.column(it.nome) }.distinct()
        fun dimensioniDi(t: ImportedTable): List<AreaDimensione> = dimensioni.filter { it.importedTableId == t.id }
        fun metricheDi(t: ImportedTable): List<AreaMetrica> =
            if (t.ruolo == RuoloTabella.FATTI) metricheConColonna else emptyList()

        // 1) Tabelle ClickHouse e symbol table delle chiavi.
        importate.forEach { t ->
            val chiavi = chiaviDi(t)
            val colonneId = (chiavi + dimensioniDi(t).map { it.colonnaFisica }).distinct()
            val decimali = metricheDi(t).mapNotNull { it.colonnaFisica }.distinct()

            chiavi.forEach { symbolTableService.createSymbolTable(it) }

            // ORDER BY sulle sole chiavi di JOIN: con tutte le colonne
            // importate di default, ordinare su centinaia di colonne
            // renderebbe i caricamenti lentissimi. Senza chiavi (dataset
            // di soli Fatti) si ordina sulla prima colonna id.
            symbolTableService.createImportedTable(
                tabellaFisica = t.tabellaFisica,
                colonneId = colonneId,
                colonneDecimali = decimali,
                colonneOrdinamento = chiavi
            )
        }

        // 2) Estrazione, trasformazione, caricamento: Dimensioni prima, Fatti poi.
        var caricate = 0L
        var scartate = 0L

        importate.sortedBy { if (it.ruolo == RuoloTabella.FATTI) 1 else 0 }.forEach { t ->
            val chiavi = chiaviDi(t)
            val dimT = dimensioniDi(t)
            val metT = metricheDi(t)
            val colonne = (dimT.map { it.colonnaFisica } + chiavi + metT.mapNotNull { it.colonnaFisica }).distinct()

            log.info(
                "ETL area '{}' ({}): tabella '{}' ({}) -> {}",
                area.nome, areaId, t.nomeLogico, t.ruolo, t.tabellaFisica
            )

            // Full reload a blocchi: si svuota UNA volta, poi si accoda un
            // blocco per volta (vedi etlChunkSize).
            loaderService.truncate(t.tabellaFisica)

            val necessarie = colonne.toSet()
            // Nomi ORIGINALI (minuscoli, come li restituisce il connettore) delle
            // sole colonne scelte: serve per non leggere quelle su "Ignora",
            // che dopo la normalizzazione potrebbero avere lo stesso nome.
            val grezzeNecessarie = colonnePerTabella[t.id].orEmpty().map { it.nome.lowercase() }.toSet()
            var validTabella = 0L
            var scartateTabella = 0L

            connectionOrchestrator.extract(connectionDi(t), t.schemaOrigine, viewDi(t)) { righe ->
                righe.chunked(etlChunkSize).forEach { blocco ->
                    val rows = normalizzaNomiColonna(blocco, necessarie, grezzeNecessarie, t.nomeLogico)

                    val (valid, errors) = transformService.transform(
                        rows = rows,
                        dimensioni = dimT,
                        dimensioneNomiById = dimensioneNomiById,
                        metriche = metT,
                        chiavi = chiavi,
                        // Una riga di Dimensione senza chiave non si collega a nulla;
                        // una riga di Fatti senza chiave resta (id 0 = non definito).
                        chiaveObbligatoria = t.ruolo == RuoloTabella.DIMENSIONE
                    )

                    loaderService.load(t.tabellaFisica, valid, colonne)
                    validTabella += valid.size
                    scartateTabella += errors.size
                    log.info(
                        "ETL area '{}': tabella '{}' -> {} righe caricate finora",
                        area.nome, t.nomeLogico, validTabella
                    )
                }
            }

            if (validTabella == 0L && scartateTabella == 0L) {
                log.warn("ETL area '{}': la tabella '{}' non ha restituito righe", area.nome, t.nomeLogico)
            }

            log.info(
                "ETL area '{}': tabella '{}' caricata, {} righe valide, {} scartate",
                area.nome, t.nomeLogico, validTabella, scartateTabella
            )
            caricate += validTabella
            scartate += scartateTabella
        }

        // Indice bitmap dopo il caricamento di TUTTE le tabelle e prima del
        // bump della dataVersion. Fa il JOIN Fatti/Dimensioni
        // una volta sola: da qui in poi gli stati non fanno più JOIN.
        bitmapIndexBuilder.rebuild(areaId)

        etlCompletionService.completeSuccess(areaId, source.id, syncStart)
        return caricate to scartate
    }

    /**
     * Il connettore restituisce le etichette colonna in minuscolo ma con i
     * caratteri originali della sorgente (es. "_keycliente"), mentre il
     * registry usa i nomi normalizzati da Naming (es. "keycliente"). Nel
     * modello legacy la view faceva già da traduttore con gli alias; qui le
     * tabelle QLK_* si leggono così come sono, quindi si rinomina.
     *
     * Si rinominano solo le colonne SCELTE, riconosciute dal nome originale
     * (non da quello normalizzato): una colonna su "Ignora" che dopo la
     * normalizzazione ha lo stesso nome di una scelta non deve essere letta,
     * né bloccare nulla. Se a collidere sono due colonne scelte, si ferma
     * tutto (altrimenti una sovrascriverebbe l'altra in silenzio).
     */
    private fun normalizzaNomiColonna(
        rows: List<Map<String, Any?>>,
        necessarie: Set<String>,
        grezzeNecessarie: Set<String>,
        nomeTabella: String
    ): List<Map<String, Any?>> {
        if (rows.isEmpty()) return rows

        val mapping = rows.first().keys.mapNotNull { grezza ->
            if (grezza.lowercase() !in grezzeNecessarie) return@mapNotNull null
            val fisica = try { Naming.column(grezza) } catch (_: Exception) { null }
            if (fisica != null && fisica in necessarie) grezza to fisica else null
        }

        val doppie = mapping.groupingBy { it.second }.eachCount().filterValues { it > 1 }.keys
        check(doppie.isEmpty()) {
            "Nella tabella '$nomeTabella' più colonne della sorgente diventano lo stesso nome fisico: " +
                    "${doppie.joinToString(", ")}. Impostane una su Ignora."
        }

        return rows.map { riga ->
            val out = HashMap<String, Any?>(mapping.size)
            mapping.forEach { (grezza, fisica) -> out[fisica] = riga[grezza] }
            out
        }
    }

    /**
     * Pre-check per i dataset a stella: ogni colonna importata deve esistere
     * ancora sulla sua tabella sorgente. Le mancanti bloccano l'ETL con
     * email urgente, come nel modello legacy.
     *
     * NON segnala le colonne nuove: le colonne messe su "Ignora" nel wizard
     * non sono registrate, quindi risulterebbero "nuove" a ogni
     * sincronizzazione e riempirebbero di email inutili.
     */
    private fun checkColumnDriftStella(
        area: Area,
        importate: List<ImportedTable>,
        colonnePerTabella: Map<UUID, List<ImportedColumn>>
    ) {
        val mancantiPerTabella: Map<String, List<String>> = try {
            importate.associate { t ->
                val connectionId = t.connectionId ?: error("La tabella '${t.nomeLogico}' non ha una connessione")
                val nomeOrigine = t.nomeOrigine ?: error("La tabella '${t.nomeLogico}' non ha il nome di origine")
                val reali = connectionOrchestrator.listColumns(connectionId, t.schemaOrigine, nomeOrigine)
                    .map { it.name.lowercase() }.toSet()
                val attese = colonnePerTabella[t.id].orEmpty().map { it.nome }
                t.nomeLogico to attese.filter { it.lowercase() !in reali }
            }.filterValues { it.isNotEmpty() }
        } catch (e: Exception) {
            log.error("Impossibile leggere le colonne sorgente per il pre-check area {}: procedo comunque", area.id, e)
            return // l'estrazione darà l'errore vero
        }

        if (mancantiPerTabella.isNotEmpty()) {
            val dettaglio = mancantiPerTabella.entries.joinToString("; ") { (tabella, colonne) ->
                "$tabella: ${colonne.joinToString(", ")}"
            }
            val messaggio = "Il dataset '${area.nome}' (id=${area.id}) ha colonne importate non più presenti " +
                    "nella sorgente. $dettaglio. L'ETL è stato bloccato per evitare un fallimento a metà. " +
                    "Vai in \"Modifica Schema\" per sistemare, poi rilancia la sincronizzazione manualmente."
            error(messaggio)
        }
    }
}