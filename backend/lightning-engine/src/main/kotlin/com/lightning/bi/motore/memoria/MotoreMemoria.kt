package com.lightning.bi.motore.memoria

import com.lightning.bi.model.AggregateRequest
import com.lightning.bi.model.AggregateResult
import com.lightning.bi.model.VersionSnapshot
import com.lightning.bi.motore.MotoreAssociativo
import com.lightning.bi.motore.MotoreClickHouse
import com.lightning.bi.service.ContatoreCampo
import com.lightning.bi.service.SezioneAccessoService
import com.lightning.bi.service.ValoreFiltro
import com.lightning.bi.service.VersionService
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Il motore in memoria (Motore 2.0). Tiene i dataset caricati e li ricarica quando cambia la
 * versione del registry o dei dati (dopo un salvataggio del modello o una sincronizzazione):
 * - al primo uso il dataset si carica e il chiamante aspetta;
 * - se c'è già una versione, si continua con quella e il ricaricamento va in background
 *   (per quel tempo in memoria ci sono due copie del dataset).
 *
 * Provvisorio: [aggrega] passa a ClickHouse finché non ci sono gli aggregati in memoria.
 */
@Service
class MotoreMemoria(
    private val caricatore: CaricatoreMemoria,
    private val stati: StatiMemoria,
    private val versionService: VersionService,
    private val motoreClickHouse: MotoreClickHouse
) : MotoreAssociativo {

    override val nome = "memoria"

    private val log = LoggerFactory.getLogger(MotoreMemoria::class.java)

    private val caricati = ConcurrentHashMap<UUID, DatasetMemoria>()
    private val inCaricamento = ConcurrentHashMap<UUID, CompletableFuture<DatasetMemoria>>()
    private val pool = Executors.newFixedThreadPool(2) { r ->
        Thread(r).apply { isDaemon = true; name = "memoria-dataset-${hashCode()}" }
    }

    @PreDestroy
    fun chiudi() {
        pool.shutdown()
    }

    /** Il dataset in memoria, aggiornato alle versioni correnti (vedi la descrizione della classe). */
    fun dataset(areaId: UUID): DatasetMemoria {
        val v = versionService.snapshotVersions(areaId)
        val attuale = caricati[areaId]
        if (attuale != null && attuale.registryVersion == v.registryVersion && attuale.dataVersion == v.dataVersion) {
            return attuale
        }
        val futuro = avvia(areaId)
        if (attuale != null) return attuale
        return try {
            futuro.join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }
    }

    /** Fa partire il caricamento del dataset in background, se non è già in corso. */
    fun precarica(areaId: UUID) {
        avvia(areaId)
    }
    /** True se il dataset ha una versione in memoria pronta da usare (anche se si sta ricaricando). */
    fun caricato(areaId: UUID): Boolean = caricati.containsKey(areaId)

    private fun avvia(areaId: UUID): CompletableFuture<DatasetMemoria> {
        val nuovo = CompletableFuture<DatasetMemoria>()
        inCaricamento.putIfAbsent(areaId, nuovo)?.let { return it }
        pool.execute {
            try {
                val d = caricatore.carica(areaId)
                caricati[areaId] = d
                nuovo.complete(d)
            } catch (e: Throwable) {
                log.error("Caricamento in memoria del dataset {} fallito", areaId, e)
                nuovo.completeExceptionally(e)
            } finally {
                inCaricamento.remove(areaId, nuovo)
            }
        }
        return nuovo
    }

    // ================= MotoreAssociativo =================

    override fun contatori(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioni: Collection<UUID>?,
        vincolo: SezioneAccessoService.Vincolo?
    ): Map<UUID, ContatoreCampo> = stati.contatori(dataset(areaId), selezioni, dimensioni, vincolo)

    override fun valori(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        ricerca: String?,
        offset: Int,
        limite: Int,
        vincolo: SezioneAccessoService.Vincolo?
    ): List<ValoreFiltro> = stati.valori(dataset(areaId), selezioni, dimensioneId, ricerca, offset, limite, vincolo)

    override fun numeroValori(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        ricerca: String?,
        vincolo: SezioneAccessoService.Vincolo?
    ): Int = stati.numeroValori(dataset(areaId), selezioni, dimensioneId, ricerca, vincolo)

    override fun idPossibili(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        vincolo: SezioneAccessoService.Vincolo?
    ): Set<Long> = stati.idPossibili(dataset(areaId), selezioni, dimensioneId, vincolo)

    override fun conflitti(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        valoreId: Long,
        vincolo: SezioneAccessoService.Vincolo?
    ): Set<UUID> = stati.conflitti(dataset(areaId), selezioni, dimensioneId, valoreId, vincolo)

    /** Provvisorio: ClickHouse, finché non ci sono AggregatiMemoria e SetAnalysisMemoria. */
    override fun aggrega(
        richiesta: AggregateRequest,
        versioni: VersionSnapshot,
        vincolo: SezioneAccessoService.Vincolo?
    ): AggregateResult = motoreClickHouse.aggrega(richiesta, versioni, vincolo)
}