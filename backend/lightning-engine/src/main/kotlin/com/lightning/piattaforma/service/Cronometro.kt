package com.lightning.piattaforma.service

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.LongAdder

/**
 * Misura di un clic, pezzo per pezzo, con il numero e il tempo delle query per database.
 *
 * - [clic] apre la misura nel thread della pagina (se ce n'è già una, il blocco ne fa parte);
 * - [pezzo] misura un tratto con l'orologio;
 * - [avvolgi] porta il clic in un altro thread (pool, push): la riga si scrive quando
 *   è finito l'ultimo pezzo, anche se arriva dopo col push;
 * - [registraQuery] lo chiama DataSourceMisurato a ogni query.
 *
 * Una riga per clic sul logger com.lightning.timing (INFO), per esempio:
 * clic filtri 412 ms · stato 18 ms · contatori 160 ms · CH 9q 230 ms · PG 14q 31 ms
 *
 * Con le query in parallelo, il tempo delle query di un database può superare quello del clic.
 * Un blocco avvolto che non parte mai lascia il clic senza riga.
 */
object Cronometro {
    private val log = LoggerFactory.getLogger("com.lightning.timing")
    private val corrente = ThreadLocal<Clic?>()

    class Clic internal constructor(private val nome: String) {
        private val inizio = System.nanoTime()
        private val aperti = AtomicInteger(1)
        private val pezzi = LinkedHashMap<String, Long>()
        private val numeroQuery = ConcurrentHashMap<String, LongAdder>()
        private val tempoQuery = ConcurrentHashMap<String, LongAdder>()

        internal fun pezzo(nome: String, nanos: Long) {
            synchronized(pezzi) { pezzi.merge(nome, nanos) { a, b -> a + b } }
        }

        internal fun query(db: String, numero: Int, nanos: Long) {
            numeroQuery.computeIfAbsent(db) { LongAdder() }.add(numero.toLong())
            tempoQuery.computeIfAbsent(db) { LongAdder() }.add(nanos)
        }

        internal fun apri() {
            aperti.incrementAndGet()
        }

        internal fun chiudi() {
            if (aperti.decrementAndGet() == 0) log.info(riga())
        }

        private fun riga(): String = buildString {
            append("clic ").append(nome).append(' ').append(ms(System.nanoTime() - inizio)).append(" ms")
            synchronized(pezzi) { pezzi.forEach { (n, t) -> append(" · ").append(n).append(' ').append(ms(t)).append(" ms") } }
            numeroQuery.keys.sorted().forEach { db ->
                append(" · ").append(db).append(' ').append(numeroQuery.getValue(db).sum()).append("q ")
                    .append(ms(tempoQuery[db]?.sum() ?: 0)).append(" ms")
            }
        }

        private fun ms(nanos: Long) = nanos / 1_000_000
    }

    /** Apre la misura di un clic; se una è già aperta in questo thread, il blocco ne fa parte. */
    fun <T> clic(nome: String, blocco: () -> T): T {
        if (corrente.get() != null || !log.isInfoEnabled) return blocco()
        val c = Clic(nome)
        corrente.set(c)
        try {
            return blocco()
        } finally {
            corrente.remove()
            c.chiudi()
        }
    }

    /** Misura un tratto del clic corrente; senza clic esegue e basta. */
    fun <T> pezzo(nome: String, blocco: () -> T): T {
        val c = corrente.get() ?: return blocco()
        val t = System.nanoTime()
        try {
            return blocco()
        } finally {
            c.pezzo(nome, System.nanoTime() - t)
        }
    }

    /** Da chiamare nel thread del clic: il blocco restituito, eseguito altrove, conta nello stesso clic. */
    fun <T> avvolgi(blocco: () -> T): () -> T {
        val c = corrente.get() ?: return blocco
        c.apri()
        return {
            val prima = corrente.get()
            corrente.set(c)
            try {
                blocco()
            } finally {
                if (prima == null) corrente.remove() else corrente.set(prima)
                c.chiudi()
            }
        }
    }

    /** Una o più esecuzioni di uno statement, dalla prima esecuzione alla chiusura. */
    fun registraQuery(db: String, numero: Int, nanos: Long) {
        corrente.get()?.query(db, numero, nanos)
    }
}