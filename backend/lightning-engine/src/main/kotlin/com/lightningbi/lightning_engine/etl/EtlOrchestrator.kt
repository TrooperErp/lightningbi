package com.lightningbi.lightning_engine.etl

import com.lightningbi.lightning_engine.model.Area
import com.lightningbi.lightning_engine.model.AreaDimensione
import com.lightningbi.lightning_engine.model.AreaMetrica
import com.lightningbi.lightning_engine.model.AreaSource
import com.lightningbi.lightning_engine.model.EtlRun
import com.lightningbi.lightning_engine.model.EtlStato
import com.lightningbi.lightning_engine.model.ImportedColumn
import com.lightningbi.lightning_engine.model.ImportedTable
import com.lightningbi.lightning_engine.model.RuoloTabella
import com.lightningbi.lightning_engine.model.SourceStatus
import com.lightningbi.lightning_engine.model.SyncMode
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.repository.EtlRunRepository
import com.lightningbi.lightning_engine.repository.EtlSyncStateRepository
import com.lightningbi.lightning_engine.repository.ImportedTableRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.service.BitmapIndexBuilder
import com.lightningbi.lightning_engine.service.CryptoService
import com.lightningbi.lightning_engine.service.EmailService
import com.lightningbi.lightning_engine.service.EtlCompletionService
import com.lightningbi.lightning_engine.service.MetadataService
import com.lightningbi.lightning_engine.service.Naming
import com.lightningbi.lightning_engine.service.SymbolTableService
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

@Service
class EtlOrchestrator(
    private val registryRepository: RegistryRepository,
    private val etlRunRepository: EtlRunRepository,
    private val etlSyncStateRepository: EtlSyncStateRepository,
    private val extractor: JdbcExtractor,
    private val transformService: TransformService,
    private val loaderService: LoaderService,
    private val redisTemplate: StringRedisTemplate,
    private val etlCompletionService: EtlCompletionService,
    private val cryptoService: CryptoService,
    private val emailService: EmailService,
    private val metadataService: MetadataService,
    private val areaSourceRepository: AreaSourceRepository,
    private val bitmapIndexBuilder: BitmapIndexBuilder,
    private val importedTableRepository: ImportedTableRepository,
    private val symbolTableService: SymbolTableService
) {
    private val log = LoggerFactory.getLogger(EtlOrchestrator::class.java)

    private val lockTtl = Duration.ofHours(2)

    /**
     * Margine di sovrapposizione sul carico incrementale.
     *
     * Si riparte da un'ora prima dell'ultima sincronizzazione riuscita per
     * coprire righe scritte sulla sorgente mentre l'ETL precedente era in
     * corso, e differenze di orologio fra i due server. Il prezzo è qualche
     * riga rielaborata, che con l'append puro significa qualche duplicato:
     * finché non c'è una chiave di deduplica, l'incrementale va usato solo
     * su sorgenti append-only.
     */
    private val overlapHours = 1L

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
     * Sincronizza un'area. Due modelli, scelti dalla sorgente:
     * - legacy: una view pre-denormalizzata -> una tabella fatti (etlLegacy)
     * - schema a stella: N tabelle importate, Fatti + Dimensioni, ciascuna
     *   caricata nella sua tabella ClickHouse (etlSchemaStella)
     * Lock, registrazione dell'esecuzione e gestione errori sono comuni.
     */
    fun runForArea(areaId: UUID, source: AreaSource) {
        if (source.status != SourceStatus.VERIFIED) {
            throw IllegalStateException(
                "Sorgente non verificata (status=${source.status}). Verifica la view prima di sincronizzare."
            )
        }
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

            val (caricate, scartate) =
                if (source.config.tabelle.isNotEmpty()) etlSchemaStella(area, source)
                else etlLegacy(area, source)

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

    // ================= Modello legacy: view singola =================

    /**
     * Verifica che tutte le colonne agganciate come dimensione o metrica
     * esistano ancora nella view sorgente, e segnala eventuali colonne
     * nuove non ancora configurate. Va chiamato PRIMA di extractor.extract,
     * non dopo: un ETL notturno che fallisce a metà su una colonna sparita
     * lascia lo stato peggio di uno che si ferma subito con un errore
     * chiaro.
     *
     * Colonne mancanti (agganciate ma sparite dalla view): bloccano l'ETL,
     * perché la query di estrazione fallirebbe comunque, in modo più
     * criptico e a metà lavoro. Email ad alta priorità.
     *
     * Colonne nuove (nella view ma non ancora agganciate): non bloccano
     * l'ETL, sono solo un'opportunità da segnalare. Email normale.
     */
    private fun checkColumnDrift(
        areaId: UUID,
        areaNome: String,
        source: AreaSource,
        dimensioni: List<AreaDimensione>,
        metriche: List<AreaMetrica>
    ) {
        val viewName = source.config.viewName ?: error("Sorgente legacy senza viewName")
        val colonneAttese = (dimensioni.map { it.colonnaFisica } + metriche.mapNotNull { it.colonnaFisica }).distinct()

        val colonneReali = try {
            val password = cryptoService.decrypt(source.config.encryptedPassword)
            metadataService.connect(
                source.config.jdbcUrl, source.config.username, password, source.config.driverClassName
            ).use { conn ->
                metadataService.listColumns(conn, source.config.schema, viewName).map { it.name }
            }
        } catch (e: Exception) {
            log.error("Impossibile leggere le colonne della view per il pre-check area {}: procedo comunque", areaId, e)
            return // se il pre-check stesso fallisce, non blocco l'ETL per questo: extractor.extract darà l'errore vero
        }

        val colonneRealiLower = colonneReali.map { it.lowercase() }.toSet()
        val colonneAttesLower = colonneAttese.map { it.lowercase() }.toSet()

        val mancanti = colonneAttese.filter { it.lowercase() !in colonneRealiLower }
        val nuove = colonneReali.filter { it.lowercase() !in colonneAttesLower }

        if (mancanti.isNotEmpty()) {
            val messaggio = "L'area '$areaNome' (id=$areaId) ha ${mancanti.size} colonna/e configurate ma non più " +
                    "presenti nella view sorgente '$viewName': ${mancanti.joinToString(", ")}. " +
                    "L'ETL è stato bloccato per evitare un fallimento a metà. Vai in \"Modifica Schema\" per sistemare " +
                    "le dimensioni/metriche coinvolte, poi rilancia la sincronizzazione manualmente."
            emailService.sendAdminAlert("URGENTE - ETL bloccato: colonne mancanti su '$areaNome'", messaggio)
            error(messaggio)
        }

        if (nuove.isNotEmpty()) {
            val messaggio = "L'area '$areaNome' (id=$areaId) ha ${nuove.size} colonna/e nuove nella view sorgente " +
                    "'$viewName' non ancora configurate come dimensione o metrica: " +
                    "${nuove.joinToString(", ")}. Nessuna azione richiesta, l'ETL prosegue normalmente: è solo un " +
                    "promemoria per valutare se aggiungerle in \"Modifica Schema\"."
            emailService.sendAdminAlert("Nuove colonne disponibili su '$areaNome'", messaggio)
        }
    }

    /** Ritorna (righe caricate, righe scartate). Logica invariata rispetto a prima del modello a stella. */
    private fun etlLegacy(area: Area, source: AreaSource): Pair<Long, Long> {
        val areaId = area.id
        val viewName = source.config.viewName ?: error("Sorgente legacy senza viewName")

        val dimensioni = registryRepository.findDimensioniByArea(areaId)
        val metriche = registryRepository.findMetricheByArea(areaId)

        require(dimensioni.isNotEmpty()) { "L'area '${area.nome}' non ha dimensioni configurate" }
        require(metriche.isNotEmpty()) { "L'area '${area.nome}' non ha metriche configurate" }

        val dims = registryRepository.findDimensioniByIds(dimensioni.map { it.dimensioneId })
        val dimensioneNomiById = dims.associate { it.id.toString() to it.nome }

        checkColumnDrift(areaId, area.nome, source, dimensioni, metriche)

        // L'istante di inizio va catturato PRIMA dell'estrazione, non dopo.
        // Registrando come "ultima sincronizzazione" il momento in cui
        // l'ETL finisce, tutte le righe scritte sulla sorgente durante
        // l'esecuzione finirebbero in una finestra temporale già superata
        // e non verrebbero mai raccolte.
        val syncStart = LocalDateTime.now()

        val lastSync = if (source.config.syncMode == SyncMode.FULL_RELOAD) {
            null
        } else {
            etlSyncStateRepository.find(areaId, source.id)
                ?.lastSync
                ?.minusHours(overlapHours)
                ?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        }

        val config = mapOf(
            "jdbcUrl" to source.config.jdbcUrl,
            "username" to source.config.username,
            "password" to cryptoService.decrypt(source.config.encryptedPassword),
            "driverClassName" to source.config.driverClassName,
            "viewName" to viewName
        )

        log.info(
            "ETL area '{}' ({}): modalità {}, view {}, da {}",
            area.nome, areaId, source.config.syncMode, viewName, lastSync ?: "inizio"
        )

        // Nessun rimappaggio.
        //
        // La view espone gli alias normalizzati da Naming a partire dal
        // NOME DELLA COLONNA sorgente, e AreaDimensione.colonnaFisica
        // contiene lo stesso identificatore: le chiavi prodotte
        // dall'extractor coincidono già con quelle attese dal transform.
        val rows = extractor.extract(config, lastSync).toList()

        if (rows.isEmpty()) {
            log.warn("ETL area '{}': la sorgente non ha restituito righe", area.nome)
        }

        val (valid, errors) = transformService.transform(rows, dimensioni, dimensioneNomiById, metriche)

        // Le metriche COUNT(*) senza colonnaFisica non corrispondono a nessuna
        // colonna caricata dall'ETL: sono calcolate a lettura da AggregateService,
        // non scritte riga per riga. Vanno escluse qui, altrimenti l'ETL tenta di
        // scrivere/leggere una colonna che non esiste.
        val columns = (dimensioni.map { it.colonnaFisica } + metriche.mapNotNull { it.colonnaFisica }).distinct()

        if (source.config.syncMode == SyncMode.FULL_RELOAD) {
            loaderService.truncateAndLoad(area.tabellaFisica, valid, columns)
        } else {
            // _partition_key non viene più passato: nessuno lo popola e le
            // tabelle d'area non dichiarano PARTITION BY. Vedi LoaderService.
            loaderService.load(area.tabellaFisica, valid, columns)
        }

        // Ricostruzione dell'indice bitmap associativo, DOPO il load dei
        // fatti e PRIMA del bump della dataVersion: una versione dati
        // nuova deve esistere solo quando l'indice è già allineato,
        // altrimenti la cache degli stati potrebbe associare dati nuovi
        // a un indice vecchio.
        //
        // Il motore bitmap è quello primario: un indice non allineato
        // produrrebbe stati sbagliati senza errori visibili, quindi un
        // errore qui DEVE far fallire la sincronizzazione.
        bitmapIndexBuilder.rebuild(areaId)

        // Bump della dataVersion e registrazione dell'ultima sincronizzazione:
        // è questo che invalida le cache associative e degli aggregati.
        etlCompletionService.completeSuccess(areaId, source.id, syncStart)

        return valid.size.toLong() to errors.size.toLong()
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

        val importate = importedTableRepository.findByArea(areaId)
        require(importate.count { it.ruolo == RuoloTabella.FATTI } == 1) {
            "L'area '${area.nome}' deve avere esattamente una tabella Fatti importata"
        }

        val dims = registryRepository.findDimensioniByIds(dimensioni.map { it.dimensioneId })
        val dimensioneNomiById = dims.associate { it.id.toString() to it.nome }

        val colonnePerTabella: Map<UUID, List<ImportedColumn>> =
            importate.associate { it.id to importedTableRepository.findColumnsByTable(it.id) }

        // ImportedTable non porta il nome della view sulla sorgente: sta in
        // SourceConfig.tabelle. Il nome logico è univoco nel dataset (lo
        // garantisce il wizard) e fa da legame fra i due.
        val viewPerNomeLogico = source.config.tabelle.associate { it.nomeLogico to it.viewName }
        fun viewDi(t: ImportedTable): String =
            viewPerNomeLogico[t.nomeLogico] ?: error("Tabella '${t.nomeLogico}' non presente nella configurazione della sorgente")
        fun nomeQualificato(t: ImportedTable): String {
            val view = viewDi(t)
            val schema = source.config.schema
            return if (schema.isNullOrBlank()) view else "$schema.$view"
        }

        val password = cryptoService.decrypt(source.config.encryptedPassword)
        checkColumnDriftStella(area, source, importate, colonnePerTabella, password, ::viewDi)

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
        val baseConfig = mapOf(
            "jdbcUrl" to source.config.jdbcUrl,
            "username" to source.config.username,
            "password" to password,
            "driverClassName" to source.config.driverClassName
        )

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
            // Nomi ORIGINALI (minuscoli, come li restituisce l'extractor) delle
            // sole colonne scelte: serve per non leggere quelle su "Ignora",
            // che dopo la normalizzazione potrebbero avere lo stesso nome.
            val grezzeNecessarie = colonnePerTabella[t.id].orEmpty().map { it.nome.lowercase() }.toSet()
            var validTabella = 0L
            var scartateTabella = 0L

            extractor.extract(baseConfig + ("viewName" to nomeQualificato(t)), null)
                .chunked(etlChunkSize)
                .forEach { blocco ->
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
        // bump della dataVersion (vedi etlLegacy). Fa il JOIN Fatti/Dimensioni
        // una volta sola: da qui in poi gli stati non fanno più JOIN.
        bitmapIndexBuilder.rebuild(areaId)

        etlCompletionService.completeSuccess(areaId, source.id, syncStart)
        return caricate to scartate
    }

    /**
     * L'extractor restituisce le etichette colonna in minuscolo ma con i
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
        source: AreaSource,
        importate: List<ImportedTable>,
        colonnePerTabella: Map<UUID, List<ImportedColumn>>,
        password: String,
        viewDi: (ImportedTable) -> String
    ) {
        val mancantiPerTabella: Map<String, List<String>> = try {
            metadataService.connect(
                source.config.jdbcUrl, source.config.username, password, source.config.driverClassName
            ).use { conn ->
                importate.associate { t ->
                    val reali = metadataService.listColumns(conn, source.config.schema, viewDi(t))
                        .map { it.name.lowercase() }.toSet()
                    val attese = colonnePerTabella[t.id].orEmpty().map { it.nome }
                    t.nomeLogico to attese.filter { it.lowercase() !in reali }
                }.filterValues { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            log.error("Impossibile leggere le colonne sorgente per il pre-check area {}: procedo comunque", area.id, e)
            return // extractor.extract darà l'errore vero
        }

        if (mancantiPerTabella.isNotEmpty()) {
            val dettaglio = mancantiPerTabella.entries.joinToString("; ") { (tabella, colonne) ->
                "$tabella: ${colonne.joinToString(", ")}"
            }
            val messaggio = "Il dataset '${area.nome}' (id=${area.id}) ha colonne importate non più presenti " +
                    "nella sorgente. $dettaglio. L'ETL è stato bloccato per evitare un fallimento a metà. " +
                    "Vai in \"Modifica Schema\" per sistemare, poi rilancia la sincronizzazione manualmente."
            emailService.sendAdminAlert("URGENTE - ETL bloccato: colonne mancanti su '${area.nome}'", messaggio)
            error(messaggio)
        }
    }
}