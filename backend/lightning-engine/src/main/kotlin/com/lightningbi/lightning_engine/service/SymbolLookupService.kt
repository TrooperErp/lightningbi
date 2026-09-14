package com.lightningbi.lightning_engine.service

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

@Service
class SymbolLookupService(
    private val jdbcTemplate: JdbcTemplate,
    private val redisTemplate: StringRedisTemplate
) {
    private val log = LoggerFactory.getLogger(SymbolLookupService::class.java)

    private val chunkSize = 5000
    private val lockTtl = Duration.ofMinutes(5)
    private val retryDelayMs = 500L

    // Le symbol table sono append-only: un value_id, una volta assegnato, non
    // cambia mai significato. La mappa id -> stringa è quindi immutabile e
    // può essere cachata a lungo senza legarla a dataVersion.
    private val labelCacheTtl = Duration.ofDays(7)

    // L'attesa massima deve coprire il TTL del lock, altrimenti un ETL lungo
    // che lo detiene fa fallire tutti gli altri dopo pochi secondi mentre il
    // lock è ancora legittimamente occupato.
    private val maxRetries = ((lockTtl.toMillis() / retryDelayMs) + 10).toInt()

    private val unlockScript = DefaultRedisScript(
        """
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("del", KEYS[1])
        else
            return 0
        end
        """.trimIndent(), Long::class.java
    )

    // ===================== stringa -> id (scrittura, ETL) =====================

    /**
     * Restituisce gli id dei valori dati, creandoli se mancanti.
     *
     * Accetta il nome LOGICO della dimensione: il nome fisico della tabella
     * lo ricava da Naming, come tutti gli altri.
     */
    fun getOrCreateIds(dimensioneNome: String, values: Set<String>): Map<String, Long> {
        if (values.isEmpty()) return emptyMap()
        val table = Naming.symbolTable(dimensioneNome)

        // Lettura ottimistica fuori dal lock: a regime quasi tutti i valori
        // esistono già, e prendere il lock globale per una pura lettura
        // serializzava inutilmente l'intero ETL.
        val preexisting = fetchExisting(table, values)
        if (preexisting.size == values.size) return preexisting

        val lockKey = "symbol-lock:${Naming.slug(dimensioneNome)}"
        val lockValue = UUID.randomUUID().toString()

        acquireLock(lockKey, lockValue, dimensioneNome)
        try {
            // Rilettura dentro il lock: fra la lettura ottimistica e
            // l'acquisizione un altro worker può aver inserito i mancanti.
            val existing = fetchExisting(table, values)
            val missing = values - existing.keys
            if (missing.isEmpty()) return existing

            val maxId = jdbcTemplate.queryForObject(
                "SELECT max(value_id) FROM $table", Long::class.java
            ) ?: 0L
            var nextId = maxId + 1

            val newRows = missing.map { it to nextId++ }
            newRows.chunked(chunkSize).forEach { chunk ->
                jdbcTemplate.batchUpdate(
                    "INSERT INTO $table (value_id, value_string) VALUES (?, ?)",
                    chunk.map { arrayOf<Any>(it.second, it.first) }
                )
            }

            log.debug("Symbol table {}: aggiunti {} nuovi valori", table, newRows.size)
            return existing + newRows.toMap()
        } finally {
            releaseLock(lockKey, lockValue)
        }
    }

    // ===================== id -> stringa (lettura, UI) =====================

    /**
     * Risolve i value_id nelle rispettive etichette.
     *
     * Serve a chiunque debba mostrare dati all'utente: la grid e i grafici
     * ricevono dal motore solo interi, che da soli sono illeggibili.
     *
     * Gli id mancanti (valore rimosso dalla symbol table, o id inventato)
     * non compaiono nella mappa: sta al chiamante decidere il fallback.
     */
    fun resolveLabels(dimensioneNome: String, ids: Set<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val slug = Naming.slug(dimensioneNome)
        val table = Naming.symbolTable(dimensioneNome)

        val result = mutableMapOf<Long, String>()
        val missing = mutableSetOf<Long>()

        // Le etichette si cachano singolarmente perché ogni grafico/griglia
        // chiede un sottoinsieme diverso: una cache per-insieme avrebbe hit
        // rate quasi nullo, una per-id viene riusata da tutti.
        ids.forEach { id ->
            val cached = safeGet("symlabel:$slug:$id")
            if (cached != null) result[id] = cached else missing += id
        }
        if (missing.isEmpty()) return result

        missing.chunked(chunkSize).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            jdbcTemplate.query(
                "SELECT value_id, value_string FROM $table WHERE value_id IN ($placeholders)",
                { rs, _ -> rs.getLong("value_id") to rs.getString("value_string") },
                *chunk.map { it as Any }.toTypedArray()
            ).forEach { (id, label) ->
                result[id] = label
                safeSet("symlabel:$slug:$id", label, labelCacheTtl)
            }
        }

        val unresolved = ids - result.keys
        if (unresolved.isNotEmpty()) {
            log.warn(
                "Symbol table {}: {} id non risolti (es. {}). Symbol table disallineata rispetto ai fatti?",
                table, unresolved.size, unresolved.take(5)
            )
        }
        return result
    }

    /** Intera mappa id -> stringa di una dimensione. Per dimensioni piccole. */
    fun allLabels(dimensioneNome: String): Map<Long, String> {
        val table = Naming.symbolTable(dimensioneNome)
        return jdbcTemplate.query(
            "SELECT value_id, value_string FROM $table",
            { rs, _ -> rs.getLong("value_id") to rs.getString("value_string") }
        ).toMap()
    }

    // ===================== interni =====================

    private fun acquireLock(lockKey: String, lockValue: String, dimensioneNome: String) {
        var attempts = 0
        while (attempts < maxRetries) {
            val acquired = try {
                redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, lockTtl) ?: false
            } catch (e: Exception) {
                // Senza Redis non c'è mutua esclusione: ClickHouse non ha
                // vincoli di unicità, quindi proseguire significherebbe
                // generare value_id duplicati in silenzio.
                throw IllegalStateException(
                    "Redis non disponibile: impossibile garantire l'unicità dei symbol id per '$dimensioneNome'", e
                )
            }
            if (acquired) return
            attempts++
            Thread.sleep(retryDelayMs)
        }
        throw IllegalStateException(
            "Lock sui simboli di '$dimensioneNome' non acquisito dopo ${lockTtl.toMinutes()} minuti. " +
                    "Probabile ETL bloccato o lock orfano: verificare $lockKey su Redis."
        )
    }

    private fun releaseLock(lockKey: String, lockValue: String) {
        try {
            redisTemplate.execute(unlockScript, listOf(lockKey), lockValue)
        } catch (e: Exception) {
            log.warn("Rilascio del lock $lockKey fallito, scadrà da solo", e)
        }
    }

    private fun fetchExisting(table: String, values: Set<String>): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        values.chunked(chunkSize).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            jdbcTemplate.query(
                "SELECT value_string, value_id FROM $table WHERE value_string IN ($placeholders)",
                { rs, _ -> rs.getString("value_string") to rs.getLong("value_id") },
                *chunk.toTypedArray()
            ).forEach { (str, id) ->
                // MergeTree non garantisce unicità: in caso di duplicati si
                // tiene l'id più basso, così tutti i lettori convergono.
                result.merge(str, id) { a, b -> minOf(a, b) }
            }
        }
        return result
    }

    private fun safeGet(key: String): String? =
        try { redisTemplate.opsForValue().get(key) } catch (e: Exception) { null }

    private fun safeSet(key: String, value: String, ttl: Duration) {
        try { redisTemplate.opsForValue().set(key, value, ttl) } catch (_: Exception) { }
    }
}