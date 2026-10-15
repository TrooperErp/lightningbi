// FILE: src/main/kotlin/com/lightningbi/lightning_engine/service/PoolQuery.kt
package com.lightning.bi.service

import com.lightning.piattaforma.service.Cronometro
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors

/**
 * Pool per le query delle pagine in parallelo (contatori, barra del calendario, valori).
 * I thread non hanno la sessione dell'utente: tutto ciò che la legge (il vincolo del
 * section access) va calcolato prima, nel thread della pagina, e passato.
 */
@Component
class PoolQuery(
    @Value("\${lbi.query.thread:8}") thread: Int
) {

    private val pool = Executors.newFixedThreadPool(thread.coerceAtLeast(1)) { r ->
        Thread(r).apply { isDaemon = true; name = "query-pagina-${hashCode()}" }
    }

    @PreDestroy
    fun chiudi() {
        pool.shutdown()
    }

    fun <T> avvia(blocco: () -> T): CompletableFuture<T> =
        CompletableFuture.supplyAsync(Cronometro.avvolgi(blocco), pool)

    /** Aspetta il risultato; l'eccezione esce com'era, non avvolta. */
    fun <T> attendi(futuro: CompletableFuture<T>): T =
        try {
            futuro.join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }
}