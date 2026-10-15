package com.lightning.bi.motore

import com.lightning.bi.motore.memoria.MotoreMemoria
import com.lightning.bi.service.ContatoreCampo
import com.lightning.bi.service.SezioneAccessoService
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Confronto incrociato tra i due motori, in background: non cambia cosa vede la pagina e non la
 * rallenta. A ogni chiamata calcola la stessa cosa con ClickHouse e in memoria e scrive nel log
 * (com.lightning.motore.confronto) i tempi e ogni differenza: ogni differenza è un errore da
 * uno dei due lati.
 *
 * Acceso da lbi.motore.confronto=true (nel compose: LBI_MOTORE_CONFRONTO=true).
 * Un confronto alla volta: se l'utente clicca mentre uno è in corso, si tiene solo l'ultimo.
 */
@Service
class ConfrontoMotori(
    private val clickHouse: MotoreClickHouse,
    private val memoria: MotoreMemoria,
    @Value("\${lbi.motore.confronto:false}") private val attivo: Boolean
) {
    private val log = LoggerFactory.getLogger("com.lightning.motore.confronto")

    private val pool = ThreadPoolExecutor(
        1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
        { r -> Thread(r).apply { isDaemon = true; name = "confronto-motori" } },
        ThreadPoolExecutor.DiscardOldestPolicy()
    )

    @PreDestroy
    fun chiudi() {
        pool.shutdown()
    }

    /**
     * Confronta i contatori. Da chiamare nel thread della pagina (il vincolo va calcolato lì).
     * @param nomi nome mostrato delle dimensioni, per il log
     */
    fun contatori(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioni: Collection<UUID>?,
        vincolo: SezioneAccessoService.Vincolo?,
        nomi: Map<UUID, String> = emptyMap()
    ) {
        if (!attivo) return
        pool.execute {
            try {
                var t = System.nanoTime()
                val ch = clickHouse.contatori(areaId, selezioni, dimensioni, vincolo)
                val msCh = (System.nanoTime() - t) / 1_000_000
                t = System.nanoTime()
                val mem = memoria.contatori(areaId, selezioni, dimensioni, vincolo)
                val msMem = (System.nanoTime() - t) / 1_000_000

                val differenze = (ch.keys + mem.keys).filter { ch[it] != mem[it] }
                log.info(
                    "contatori · {} campi · clickhouse {} ms · memoria {} ms · {} differenze",
                    ch.size, msCh, msMem, differenze.size
                )
                differenze.forEach { d ->
                    log.warn("  differenza '{}': clickhouse {} · memoria {}", nomi[d] ?: d, testo(ch[d]), testo(mem[d]))
                }
            } catch (e: Exception) {
                log.warn("Confronto dei contatori fallito per il dataset {}", areaId, e)
            }
        }
    }

    private fun testo(c: ContatoreCampo?): String =
        if (c == null) "assente" else "${c.possibili}/${c.totali} sel ${c.selezionati}"
}