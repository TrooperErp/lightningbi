package com.lightningbi.lightning_engine.etl

import com.lightningbi.lightning_engine.model.AreaSource
import com.lightningbi.lightning_engine.model.EtlRun
import com.lightningbi.lightning_engine.model.EtlStato
import com.lightningbi.lightning_engine.model.SourceStatus
import com.lightningbi.lightning_engine.model.SyncMode
import com.lightningbi.lightning_engine.repository.EtlRunRepository
import com.lightningbi.lightning_engine.repository.EtlSyncStateRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.service.CryptoService
import com.lightningbi.lightning_engine.service.EtlCompletionService
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
    private val cryptoService: CryptoService
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

    private val unlockScript = DefaultRedisScript(
        """
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("del", KEYS[1])
        else
            return 0
        end
        """.trimIndent(), Long::class.java
    )

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
            val dimensioni = registryRepository.findDimensioniByArea(areaId)
            val metriche = registryRepository.findMetricheByArea(areaId)

            require(dimensioni.isNotEmpty()) { "L'area '${area.nome}' non ha dimensioni configurate" }
            require(metriche.isNotEmpty()) { "L'area '${area.nome}' non ha metriche configurate" }

            val dims = registryRepository.findDimensioniByIds(dimensioni.map { it.dimensioneId })
            val dimensioneNomiById = dims.associate { it.id.toString() to it.nome }

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
                "viewName" to source.config.viewName
            )

            log.info(
                "ETL area '{}' ({}): modalità {}, view {}, da {}",
                area.nome, areaId, source.config.syncMode, source.config.viewName, lastSync ?: "inizio"
            )

            // Nessun rimappaggio.
            //
            // La view espone gli alias normalizzati da Naming a partire dal
            // NOME DELLA COLONNA sorgente, e AreaDimensione.colonnaFisica
            // contiene lo stesso identificatore: le chiavi prodotte
            // dall'extractor coincidono già con quelle attese dal transform.
            //
            // La versione precedente rimappava per NOME DELLA DIMENSIONE
            // usando una funzione di slug locale diversa da Naming. Due
            // problemi: gli alias non coincidevano su nomi con caratteri
            // speciali (si caricavano colonne di null senza errori), e il
            // mapping per nome dimensione rende impossibili le dimensioni
            // usate più volte nella stessa area con ruoli diversi (data
            // ordine e data consegna entrambe sulla dimensione Tempo), perché
            // due colonne non possono condividere lo stesso alias.
            val rows = extractor.extract(config, lastSync).toList()

            if (rows.isEmpty()) {
                log.warn("ETL area '{}': la sorgente non ha restituito righe", area.nome)
            }

            val (valid, errors) = transformService.transform(rows, dimensioni, dimensioneNomiById, metriche)

            // Le metriche COUNT(*) senza colonnaFisica non corrispondono a nessuna
            // colonna caricata dall'ETL: sono calcolate a lettura da AggregateService,
            // non scritte riga per riga. Vanno escluse qui, altrimenti l'ETL tenta di
            // scrivere/leggere una colonna che non esiste.
            val columns = dimensioni.map { it.colonnaFisica } + metriche.mapNotNull { it.colonnaFisica }

            if (source.config.syncMode == SyncMode.FULL_RELOAD) {
                loaderService.truncateAndLoad(area.tabellaFisica, valid, columns)
            } else {
                // _partition_key non viene più passato: nessuno lo popola e le
                // tabelle d'area non dichiarano PARTITION BY. Vedi LoaderService.
                loaderService.load(area.tabellaFisica, valid, columns)
            }

            // Bump della dataVersion e registrazione dell'ultima sincronizzazione:
            // è questo che invalida le cache associative e degli aggregati.
            etlCompletionService.completeSuccess(areaId, source.id, syncStart)

            etlRunRepository.update(
                run.copy(
                    finishedAt = LocalDateTime.now(),
                    stato = EtlStato.SUCCESS,
                    righeProcessate = valid.size.toLong(),
                    righeScartate = errors.size.toLong()
                )
            )
            log.info(
                "ETL area '{}' completato: {} righe caricate, {} scartate",
                area.nome, valid.size, errors.size
            )
        } catch (e: Exception) {
            log.error("ETL area {} fallito", areaId, e)
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
}