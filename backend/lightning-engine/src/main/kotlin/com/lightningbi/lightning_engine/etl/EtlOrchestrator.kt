package com.lightningbi.lightning_engine.etl

import tools.jackson.databind.ObjectMapper
import com.lightningbi.lightning_engine.model.EtlRun
import com.lightningbi.lightning_engine.model.EtlStato
import com.lightningbi.lightning_engine.model.EtlSyncState
import com.lightningbi.lightning_engine.repository.EtlRunRepository
import com.lightningbi.lightning_engine.repository.EtlSyncStateRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.service.EtlCompletionService
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
    private val objectMapper: ObjectMapper,
    private val etlCompletionService: EtlCompletionService
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

    fun runForArea(areaId: UUID, sourceId: UUID, sourceConfigJson: String, tabellaFisica: String) {
        val lockKey = "etl-lock:$areaId:$sourceId"
        val lockValue = UUID.randomUUID().toString()
        val acquired = redisTemplate.opsForValue()
            .setIfAbsent(lockKey, lockValue, Duration.ofHours(2)) ?: false

        if (!acquired) {
            throw IllegalStateException("ETL already running for area=$areaId source=$sourceId")
        }

        val run = EtlRun(
            id = UUID.randomUUID(), areaId = areaId, sourceId = sourceId,
            startedAt = LocalDateTime.now(), finishedAt = null,
            stato = EtlStato.RUNNING, righeProcessate = 0, righeScartate = 0, errore = null
        )
        etlRunRepository.save(run)

        try {
            val syncState = etlSyncStateRepository.find(areaId, sourceId)
            val lastSync = syncState?.lastSync
                ?.minusHours(1)
                ?.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

            @Suppress("UNCHECKED_CAST")
            val config = objectMapper.readValue(sourceConfigJson, Map::class.java) as Map<String, Any>

            val rawRows = extractor.extract(config, lastSync).toList()

            val dimensioni = registryRepository.findDimensioniByArea(areaId)
            val metriche = registryRepository.findMetricheByArea(areaId)
            val dims = registryRepository.findDimensioniByIds(dimensioni.map { it.dimensioneId })
            val dimensioneNomiById = dims.associate { it.id.toString() to it.nome }

            val (valid, errors) = transformService.transform(rawRows, dimensioni, dimensioneNomiById, metriche)

            val columns = dimensioni.map { it.colonnaFisica } + metriche.map { it.colonnaFisica } + "_partition_key"
            loaderService.load(tabellaFisica, valid, columns)

            etlCompletionService.completeSuccess(areaId, sourceId, LocalDateTime.now())

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