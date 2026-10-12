package com.lightning.bi.etl

import com.lightning.bi.model.Area
import com.lightning.bi.model.AreaSource
import com.lightning.bi.model.RuoloTabella
import com.lightning.bi.repository.ImportedTableRepository
import com.lightning.bi.repository.RegistryRepository
import com.lightning.bi.repository.TableSyncRepository
import com.lightning.bi.service.BitmapIndexBuilder
import com.lightning.bi.service.EmailService
import com.lightning.bi.service.EtlCompletionService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordina le sincronizzazioni. Il lavoro vero, per tabella, lo fa
 * [TableSyncRunner]; qui si decide quali tabelle sincronizzare e quando
 * ricostruire l'indice dei dataset che le usano.
 *
 * - [runForArea]: sincronizza tutte le tabelle di un dataset (Dimensioni
 *   prima, Fatti poi) e ricostruisce il suo indice una sola volta.
 * - [syncTabella]: sincronizza una tabella e ricostruisce l'indice di OGNI
 *   dataset che la usa, se tutte le sue tabelle sono già state caricate.
 *
 * L'indice bitmap fa il JOIN Fatti/Dimensioni una volta sola: da lì gli stati
 * non fanno più JOIN. Dopo la ricostruzione sale la dataVersion, quindi le
 * cache esistenti restano valide finché i dati non sono davvero cambiati.
 */
@Service
class EtlOrchestrator(
    private val importedTableRepository: ImportedTableRepository,
    private val tableSyncRepository: TableSyncRepository,
    private val tableSyncRunner: TableSyncRunner,
    private val bitmapIndexBuilder: BitmapIndexBuilder,
    private val etlCompletionService: EtlCompletionService,
    private val registryRepository: RegistryRepository,
    private val emailService: EmailService,
    private val redisTemplate: StringRedisTemplate,
    /** Tabelle sincronizzate insieme in "Sincronizza tutto" (di giorno l'ERP è in uso; di notte anche 4). */
    @Value("\${lbi.sync.tabelle-parallele:3}") private val tabelleParallele: Int
) {
    private val log = LoggerFactory.getLogger(EtlOrchestrator::class.java)

    private val indexLockTtl = Duration.ofMinutes(30)

    private val unlockScript = DefaultRedisScript(
        """
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("del", KEYS[1])
        else
            return 0
        end
        """.trimIndent(), Long::class.java
    )

    // ================= Un dataset =================

    /**
     * Sincronizza tutte le tabelle del dataset e ricostruisce il suo indice.
     * [source] resta nella firma finché la sorgente per dataset non sparisce
     * (blocco dopo la D minima): oggi serve solo a controllare l'appartenenza.
     *
     * @param progresso riceve una frase per ogni passo ("Tabella 2 di 3 · ...")
     */
    fun runForArea(areaId: UUID, source: AreaSource, progresso: (String) -> Unit = {}) {
        require(source.areaId == areaId) {
            "La sorgente ${source.id} appartiene all'area ${source.areaId}, non a $areaId"
        }
        val area = registryRepository.findAreaById(areaId) ?: error("Area $areaId non trovata")

        // Dimensioni prima, Fatti poi.
        val tabelle = importedTableRepository.findLinkedToArea(areaId)
            .sortedBy { if (it.ruolo == RuoloTabella.FATTI) 1 else 0 }
        require(tabelle.isNotEmpty()) { "Il dataset '${area.nome}' non ha tabelle importate collegate" }

        tabelle.forEachIndexed { i, tabella ->
            tableSyncRunner.sync(tabella.id, forzaCompleta = false) { fase ->
                progresso("Tabella ${i + 1} di ${tabelle.size} · $fase")
            }
        }
        ricostruisciIndice(area, progresso)
    }

    /**
     * Ricostruisce solo l'indice del dataset, senza ricaricare i dati: serve dopo una
     * modifica al modello o ai campi. Richiede che tutte le sue tabelle siano già state caricate.
     */
    fun aggiornaIndice(areaId: UUID, progresso: (String) -> Unit = {}) {
        val area = registryRepository.findAreaById(areaId) ?: error("Area $areaId non trovata")
        require(tutteSincronizzate(areaId)) {
            "Il dataset '${area.nome}' ha tabelle non ancora caricate: sincronizzale prima"
        }
        ricostruisciIndice(area, progresso)
    }

    // ================= Una tabella =================

    /**
     * Sincronizza una tabella e ricostruisce l'indice dei dataset che la
     * usano. Un dataset le cui altre tabelle non sono ancora state caricate
     * resta com'è: il suo indice si farà quando saranno tutte pronte.
     */
    fun syncTabella(
        importedTableId: UUID,
        forzaCompleta: Boolean = false,
        progresso: (String) -> Unit = {}
    ): EsitoSync {
        val esito = tableSyncRunner.sync(importedTableId, forzaCompleta, progresso)

        importedTableRepository.findAreaIdsUsing(importedTableId).forEach { areaId ->
            val area = registryRepository.findAreaById(areaId) ?: return@forEach
            if (tutteSincronizzate(areaId)) {
                ricostruisciIndice(area, progresso)
            } else {
                log.info(
                    "Dataset '{}': l'indice non si ricostruisce, alcune sue tabelle non sono ancora state caricate",
                    area.nome
                )
            }
        }
        return esito
    }

    /**
     * "Sincronizza tutto": le tabelle [ids] partono [tabelleParallele] alla volta. L'indice di un
     * dataset si ricostruisce UNA volta, appena finisce l'ultima delle sue tabelle in questo giro
     * (se tutte le sue tabelle sono state caricate almeno una volta): così la finestra in cui
     * tabella e indice non coincidono resta breve.
     *
     * Una tabella in errore non ferma le altre. [progresso] riceve la tabella e la frase;
     * [fineTabella] è chiamato appena una tabella termina, bene o male (da thread diversi).
     */
    fun sincronizzaTutte(
        ids: List<UUID>,
        progresso: (UUID, String) -> Unit = { _, _ -> },
        fineTabella: (EsitoTabella) -> Unit = {}
    ): List<EsitoTabella> {
        if (ids.isEmpty()) return emptyList()

        // Per ogni dataset: quante delle sue tabelle sono in questo giro.
        val restanti = ConcurrentHashMap<UUID, AtomicInteger>()
        ids.forEach { id ->
            importedTableRepository.findAreaIdsUsing(id).forEach { areaId ->
                restanti.computeIfAbsent(areaId) { AtomicInteger() }.incrementAndGet()
            }
        }

        val pool = Executors.newFixedThreadPool(tabelleParallele.coerceIn(1, ids.size)) { r ->
            Thread(r).apply { isDaemon = true; name = "sync-tutto-${hashCode()}" }
        }
        try {
            val futuri = ids.map { id ->
                pool.submit(Callable {
                    val esito = try {
                        EsitoTabella(id, tableSyncRunner.sync(id, false) { fase -> progresso(id, fase) }, null)
                    } catch (e: Exception) {
                        EsitoTabella(id, null, e)
                    }
                    fineTabella(esito)

                    // Ultima tabella del dataset in questo giro: indice, una volta sola.
                    importedTableRepository.findAreaIdsUsing(id).forEach { areaId ->
                        if (restanti[areaId]?.decrementAndGet() == 0) {
                            val area = registryRepository.findAreaById(areaId)
                            if (area != null && tutteSincronizzate(areaId)) {
                                try {
                                    ricostruisciIndice(area) { fase -> progresso(id, fase) }
                                } catch (e: Exception) {
                                    // Già registrato e segnalato via email da ricostruisciIndice.
                                    log.warn("Indice del dataset '{}' non ricostruito dopo Sincronizza tutto", area.nome)
                                }
                            }
                        }
                    }
                    esito
                })
            }
            return futuri.map { it.get() }
        } finally {
            pool.shutdown()
        }
    }

    private fun tutteSincronizzate(areaId: UUID): Boolean =
        importedTableRepository.findLinkedToArea(areaId).all { tabella ->
            tableSyncRepository.findByTable(tabella.id)?.ultimaSyncInizio != null
        }

    // ================= Indice =================

    /**
     * Ricostruisce l'indice bitmap di un dataset e alza la sua dataVersion.
     * Due sincronizzazioni di tabelle diverse dello stesso dataset possono
     * finire insieme: l'indice si ricostruisce una alla volta per dataset
     * (la seconda attende la prima).
     */
    private fun ricostruisciIndice(area: Area, progresso: (String) -> Unit) {
        progresso("${area.nome}: ricostruisco l'indice")
        val lockKey = "index-lock:${area.id}"
        val lockValue = UUID.randomUUID().toString()
        attendiLock(lockKey, lockValue, area)
        try {
            bitmapIndexBuilder.rebuild(area.id)
            etlCompletionService.completeAreaRefresh(area.id)
            log.info("Dataset '{}': indice ricostruito", area.nome)
        } catch (e: Exception) {
            log.error("Ricostruzione dell'indice del dataset '{}' fallita", area.nome, e)
            try {
                emailService.sendAdminAlert(
                    "URGENTE - indice fallito: dataset '${area.nome}'",
                    "La ricostruzione dell'indice del dataset '${area.nome}' è fallita con errore: " +
                            "${e.message ?: e::class.qualifiedName}. Controlla i log del server."
                )
            } catch (_: Exception) {
                // L'errore vero è un altro: l'email non deve coprirlo.
            }
            throw e
        } finally {
            try {
                redisTemplate.execute(unlockScript, listOf(lockKey), lockValue)
            } catch (e: Exception) {
                log.warn("Rilascio del lock {} fallito, scadrà da solo entro {} minuti", lockKey, indexLockTtl.toMinutes(), e)
            }
        }
    }

    private fun attendiLock(lockKey: String, lockValue: String, area: Area) {
        val tentativiMax = (indexLockTtl.toMillis() / 2_000L).toInt()
        repeat(tentativiMax) {
            val preso = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, indexLockTtl) ?: false
            if (preso) return
            Thread.sleep(2_000L)
        }
        throw IllegalStateException(
            "Indice del dataset '${area.nome}' occupato da più di ${indexLockTtl.toMinutes()} minuti: " +
                    "probabile lock orfano, verifica $lockKey su Redis"
        )
    }
}

/** Esito di una tabella in "Sincronizza tutto": il risultato, oppure l'errore. */
data class EsitoTabella(val tabellaId: UUID, val esito: EsitoSync?, val errore: Exception?)