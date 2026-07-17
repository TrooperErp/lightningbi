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
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
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
    private val objectMapper: ObjectMapper,
    private val etlCompletionService: EtlCompletionService,
    private val cryptoService: CryptoService
) {

    private val unlockScript = DefaultRedisScript(
        """
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("del", KEYS[1])
        else
            return 0
        end
        """.trimIndent(), Long::class.java
    )

    private fun slug(nome: String): String =
        nome.lowercase().replace(Regex("\\s+"), "_")

    fun runForArea(areaId: UUID, source: AreaSource) {
        if (source.status != SourceStatus.VERIFIED) {
            throw IllegalStateException("Sorgente non verificata (status=${source.status}). Verifica la view prima di sincronizzare.")
        }

        val lockKey = "etl-lock:$areaId:${source.id}"
        val lockValue = UUID.randomUUID().toString()
        val acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, lockValue, Duration.ofHours(2)) ?: false

        if (!acquired) {
            throw IllegalStateException("ETL already running for area=$areaId source=${source.id}")
        }

        val run = EtlRun(
            id = UUID.randomUUID(), areaId = areaId, sourceId = source.id,
            startedAt = LocalDateTime.now(), finishedAt = null,
            stato = EtlStato.RUNNING, righeProcessate = 0, righeScartate = 0, errore = null
        )
        etlRunRepository.save(run)

        try {
            val syncState = etlSyncStateRepository.find(areaId, source.id)
            val lastSync = if (source.config.syncMode == SyncMode.FULL_RELOAD) {
                null
            } else {
                syncState?.lastSync
                    ?.minusHours(1)
                    ?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            }

            val decryptedPassword = cryptoService.decrypt(source.config.encryptedPassword)

            val config = mapOf(
                "jdbcUrl" to source.config.jdbcUrl,
                "username" to source.config.username,
                "password" to decryptedPassword,
                "driverClassName" to source.config.driverClassName,
                "viewName" to source.config.viewName
            )

            val rawRows = extractor.extract(config, lastSync).toList()

            val area = registryRepository.findAreaById(areaId) ?: error("Area not found")
            val dimensioni = registryRepository.findDimensioniByArea(areaId)
            val metriche = registryRepository.findMetricheByArea(areaId)
            val dims = registryRepository.findDimensioniByIds(dimensioni.map { it.dimensioneId })
            val dimensioneNomiById = dims.associate { it.id.toString() to it.nome }

            // La view genera colonne già con alias = slug(nome dimensione/metrica).
            // Serve solo rimappare dallo slug alla colonna fisica dell'area.
            val remappedRows = rawRows.map { row ->
                val out = mutableMapOf<String, Any?>()
                dimensioni.forEach { ad ->
                    val nome = dimensioneNomiById[ad.dimensioneId.toString()] ?: return@forEach
                    out[ad.colonnaFisica] = row[slug(nome)]
                }
                metriche.forEach { m ->
                    out[m.colonnaFisica] = row[slug(m.nome)]
                }
                out
            }

            val (valid, errors) = transformService.transform(remappedRows, dimensioni, dimensioneNomiById, metriche)

            if (source.config.syncMode == SyncMode.FULL_RELOAD) {
                loaderService.truncateAndLoad(area.tabellaFisica, valid, dimensioni.map { it.colonnaFisica } + metriche.map { it.colonnaFisica })
            } else {
                val columns = dimensioni.map { it.colonnaFisica } + metriche.map { it.colonnaFisica } + "_partition_key"
                loaderService.load(area.tabellaFisica, valid, columns)
            }

            etlCompletionService.completeSuccess(areaId, source.id, LocalDateTime.now())

            etlRunRepository.update(
                run.copy(
                    finishedAt = LocalDateTime.now(),
                    stato = EtlStato.SUCCESS,
                    righeProcessate = valid.size.toLong(),
                    righeScartate = errors.size.toLong()
                )
            )
        } catch (e: Exception) {
            etlRunRepository.update(
                run.copy(finishedAt = LocalDateTime.now(), stato = EtlStato.FAILED, errore = e.message)
            )
            throw e
        } finally {
            redisTemplate.execute(unlockScript, listOf(lockKey), lockValue)
        }
    }
}