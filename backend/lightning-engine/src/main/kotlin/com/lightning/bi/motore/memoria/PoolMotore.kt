package com.lightning.bi.motore.memoria

import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.Callable
import java.util.concurrent.ForkJoinPool

/**
 * Il pool di thread del motore in memoria, condiviso da tutti gli utenti (come il JobExecutor
 * di HANA): un clic divide un giro sulle righe in blocchi eseguiti qui.
 *
 * - lbi.motore.memoria.thread: thread del pool (0 = numero di core);
 * - lbi.motore.memoria.blocchi-per-clic: blocchi massimi di un giro (0 = metà dei core), come
 *   statement_thread_limit di HANA: un utente non prende tutti i core e gli altri non aspettano.
 *
 * Con più utenti insieme i blocchi si mettono in coda nello stesso pool: la macchina non va mai
 * oltre i thread del pool.
 */
@Component
class PoolMotore(
    @Value("\${lbi.motore.memoria.thread:0}") thread: Int,
    @Value("\${lbi.motore.memoria.blocchi-per-clic:0}") blocchi: Int
) {
    private val core = Runtime.getRuntime().availableProcessors()
    private val pool = ForkJoinPool(if (thread > 0) thread else core)
    val blocchiPerClic: Int = if (blocchi > 0) blocchi else maxOf(1, core / 2)

    @PreDestroy
    fun chiudi() {
        pool.shutdown()
    }

    /**
     * Divide le righe [0, righe) in blocchi (allineati a 64, cioè a una parola del BitSet) e
     * applica [f] a ognuno: in parallelo se le righe sono abbastanza, altrimenti nel thread chiamante.
     * Restituisce i risultati nell'ordine dei blocchi.
     */
    fun <T> inBlocchi(righe: Int, f: (da: Int, a: Int) -> T): List<T> {
        val k = minOf(blocchiPerClic, maxOf(1, righe / RIGHE_MINIME_BLOCCO))
        if (k <= 1) return listOf(f(0, righe))
        val passo = ((righe + k - 1) / k + 63) and 63.inv()
        val compiti = (0 until k).mapNotNull { i ->
            val da = i * passo
            if (da >= righe) null
            else {
                val a = minOf(righe, da + passo)
                pool.submit(Callable { f(da, a) })
            }
        }
        return compiti.map { it.join() }
    }

    private companion object {
        /** Sotto questa soglia un blocco in più costa più di quanto fa risparmiare. */
        const val RIGHE_MINIME_BLOCCO = 65_536
    }
}