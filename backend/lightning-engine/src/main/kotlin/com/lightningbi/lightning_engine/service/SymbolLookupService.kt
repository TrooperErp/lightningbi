package com.lightningbi.lightning_engine.service

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
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

    /**
     * Id riservato al valore mancante, allineato a TransformService.NULL_VALUE_ID.
     *
     * Le colonne dimensione su ClickHouse sono UInt32 non nullable: un dato
     * sorgente assente su una dimensione non obbligatoria viene scritto come
     * zero. Lo zero non esiste nella symbol table (i value_id partono da 1),
     * quindi va tradotto qui, altrimenti comparirebbe fra i filtri come voce
     * muta e finirebbe nel log degli id non risolti.
     */
    private val nullValueId = 0L
    private val nullValueLabel = "(non definito)"

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
     * La symbol table è quella del CAMPO: si chiama come la colonna fisica ed
     * è condivisa da tutte le tabelle con una colonna omonima. Accetta il nome
     * della colonna come arriva dalla sorgente: il nome fisico lo ricava da
     * Naming, come tutti gli altri.
     *
     * Ogni valore nuovo si inserisce con le sue due forme: il testo e, se è un
     * numero rappresentabile, il numero (value_number). I valori già presenti
     * non si riscrivono: se erano stati creati prima di value_number restano
     * senza numero.
     */
    fun getOrCreateIds(
        colonna: String,
        values: Set<String>,
        numeri: Map<String, BigDecimal> = emptyMap()
    ): Map<String, Long> {
        if (values.isEmpty()) return emptyMap()
        val table = Naming.symbolTable(colonna)

        // Lettura ottimistica fuori dal lock: a regime quasi tutti i valori
        // esistono già, e prendere il lock globale per una pura lettura
        // serializzava inutilmente l'intero ETL.
        val preexisting = fetchExisting(table, values)
        if (preexisting.size == values.size) return preexisting

        val lockKey = "symbol-lock:${Naming.slug(colonna)}"
        val lockValue = UUID.randomUUID().toString()

        acquireLock(lockKey, lockValue, colonna)
        try {
            // Rilettura dentro il lock: fra la lettura ottimistica e
            // l'acquisizione un altro worker può aver inserito i mancanti.
            val existing = fetchExisting(table, values)
            val missing = values - existing.keys
            if (missing.isEmpty()) return existing

            // max(value_id) va letto DENTRO il lock: garantisce che nessun
            // altro processo stia assegnando id concorrenti sullo stesso
            // campo nel frattempo. Il COALESCE porta il primo id a 1,
            // lasciando lo zero libero per il valore non definito.
            val maxId = jdbcTemplate.queryForObject(
                "SELECT max(value_id) FROM $table", Long::class.java
            ) ?: 0L
            var nextId = maxId + 1

            val newRows = missing.map { it to nextId++ }

            // Due inserimenti distinti, senza legare mai un null: i valori
            // numerici portano anche value_number, gli altri solo il testo.
            val (numerici, testuali) = newRows
                .map { (valore, id) -> Triple(valore, id, numeri[valore] ?: toNumero(valore)) }
                .partition { it.third != null }
            numerici.chunked(chunkSize).forEach { chunk ->
                jdbcTemplate.batchUpdate(
                    "INSERT INTO $table (value_id, value_string, value_number) VALUES (?, ?, ?)",
                    chunk.map { arrayOf<Any>(it.second, it.first, it.third!!) }
                )
            }
            testuali.chunked(chunkSize).forEach { chunk ->
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

    /**
     * Gli id dei valori che ESISTONO già nella symbol table del campo, senza
     * crearne di nuovi. Un valore assente non compare nella mappa: per la
     * chiave di un'unità vuol dire unità nuova, senza righe da cancellare.
     * La symbol table deve esistere (si crea prima con SymbolTableService).
     */
    fun findIds(colonna: String, values: Set<String>): Map<String, Long> {
        if (values.isEmpty()) return emptyMap()
        return fetchExisting(Naming.symbolTable(colonna), values)
    }

    // ===================== id -> stringa (lettura, UI) =====================

    /**
     * Risolve i value_id nelle rispettive etichette.
     *
     * Serve a chiunque debba mostrare dati all'utente: la grid e i grafici
     * ricevono dal motore solo interi, che da soli sono illeggibili.
     *
     * Gli id non risolti non compaiono nella mappa: sta al chiamante decidere
     * il fallback.
     */
    fun resolveLabels(colonna: String, ids: Set<Long>): Map<Long, String> {
        if (ids.isEmpty()) return emptyMap()
        val slug = Naming.slug(colonna)
        val table = Naming.symbolTable(colonna)

        val result = mutableMapOf<Long, String>()

        // Lo zero non va cercato in tabella: è l'id riservato al valore
        // assente, e non esiste come riga.
        if (nullValueId in ids) result[nullValueId] = nullValueLabel
        val realIds = ids.filterTo(mutableSetOf()) { it != nullValueId }
        if (realIds.isEmpty()) return result

        val missing = mutableSetOf<Long>()

        // La chiave porta la versione "v2": fino a ieri la symbol table si
        // chiamava come la dimensione del dataset, oggi come la colonna. Una
        // chiave senza versione potrebbe restituire, per 7 giorni, l'etichetta
        // di un'altra tabella con lo stesso nome.
        // Le etichette si cachano singolarmente perché ogni grafico o griglia
        // chiede un sottoinsieme diverso: una cache per-insieme avrebbe hit
        // rate quasi nullo, una per-id viene riusata da tutti.
        realIds.forEach { id ->
            val cached = safeGet("symlabel:v2:$slug:$id")
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
                safeSet("symlabel:v2:$slug:$id", label, labelCacheTtl)
            }
        }

        val unresolved = realIds - result.keys
        if (unresolved.isNotEmpty()) {
            log.warn(
                "Symbol table {}: {} id non risolti (es. {}). Symbol table disallineata rispetto ai fatti?",
                table, unresolved.size, unresolved.take(5)
            )
        }
        return result
    }

    /**
     * Intera mappa id -> stringa di un campo, con in testa la voce del
     * valore non definito. Per campi di cardinalità contenuta.
     */
    fun allLabels(colonna: String): Map<Long, String> {
        val table = Naming.symbolTable(colonna)
        val labels = jdbcTemplate.query(
            "SELECT value_id, value_string FROM $table",
            { rs, _ -> rs.getLong("value_id") to rs.getString("value_string") }
        ).toMap()
        return mapOf(nullValueId to nullValueLabel) + labels
    }

    /**
     * Etichetta di un singolo id, con fallback leggibile.
     *
     * Da usare nella UI al posto di una lookup diretta sulla mappa: mostrare
     * "#47" rende visibile un disallineamento fra fatti e symbol table,
     * mentre una cella vuota lo nasconde.
     */
    fun labelOrFallback(labels: Map<Long, String>, id: Long?): String = when {
        id == null -> "—"
        else -> labels[id] ?: "#$id"
    }

    // ===================== interni =====================

    private fun acquireLock(lockKey: String, lockValue: String, colonna: String) {
        var attempts = 0
        while (attempts < maxRetries) {
            val acquired = try {
                redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, lockTtl) ?: false
            } catch (e: Exception) {
                // Senza Redis non c'è mutua esclusione: ClickHouse non ha
                // vincoli di unicità, quindi proseguire significherebbe
                // generare value_id duplicati in silenzio.
                throw IllegalStateException(
                    "Redis non disponibile: impossibile garantire l'unicità dei symbol id per '$colonna'", e
                )
            }
            if (acquired) return
            attempts++
            Thread.sleep(retryDelayMs)
        }
        throw IllegalStateException(
            "Lock sui simboli di '$colonna' non acquisito dopo ${lockTtl.toMinutes()} minuti. " +
                    "Probabile ETL bloccato o lock orfano: verificare $lockKey su Redis."
        )
    }

    private fun releaseLock(lockKey: String, lockValue: String) {
        try {
            redisTemplate.execute(unlockScript, listOf(lockKey), lockValue)
        } catch (e: Exception) {
            // Il lock scade da solo dopo lockTtl: si logga e si prosegue,
            // il lavoro sui dati è già andato a buon fine.
            log.warn("Rilascio del lock $lockKey fallito, scadrà da solo", e)
        }
    }

    /** Limite di byte per una query con la lista dei valori: ClickHouse rifiuta oltre 256 KiB (max_query_size). */
    private val maxByteQuery = 100_000

    /**
     * Divide i valori in blocchi per numero (al massimo [chunkSize]) E per dimensione: i valori finiscono
     * nel testo della query, e con testi lunghi 5.000 valori superano il limite di ClickHouse.
     * Per ogni valore si contano il doppio dei caratteri (apici e backslash raddoppiati) più virgole e apici.
     */
    private fun blocchiPerDimensione(values: Collection<String>): List<List<String>> {
        val blocchi = mutableListOf<List<String>>()
        var corrente = mutableListOf<String>()
        var byte = 0
        for (v in values) {
            val costo = v.toByteArray(Charsets.UTF_8).size * 2 + 3
            if (corrente.isNotEmpty() && (corrente.size >= chunkSize || byte + costo > maxByteQuery)) {
                blocchi += corrente
                corrente = mutableListOf()
                byte = 0
            }
            corrente += v
            byte += costo
        }
        if (corrente.isNotEmpty()) blocchi += corrente
        return blocchi
    }

    private fun fetchExisting(table: String, values: Set<String>): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        blocchiPerDimensione(values).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            jdbcTemplate.query(
                "SELECT value_string, value_id FROM $table WHERE value_string IN ($placeholders)",
                { rs, _ -> rs.getString("value_string") to rs.getLong("value_id") },
                *chunk.toTypedArray()
            ).forEach { (str, id) ->
                // MergeTree non garantisce unicità: in caso di duplicati
                // (es. scrittura concorrente senza lock) si tiene l'id più
                // basso, così tutti i lettori convergono sullo stesso valore.
                result.merge(str, id) { a, b -> minOf(a, b) }
            }
        }
        return result
    }

    /**
     * Il valore come numero, se lo è e se sta in Decimal(38,6); altrimenti
     * null (resta solo il testo). Un numero fuori scala sarebbe rifiutato da
     * ClickHouse e fermerebbe l'ETL per un campo che nessuno ordina in modo
     * numerico.
     */
    private fun toNumero(valore: String): BigDecimal? {
        val numero = valore.trim().toBigDecimalOrNull() ?: return null
        val scalato = numero.setScale(6, RoundingMode.HALF_UP)
        return if (scalato.precision() <= 38) scalato else null
    }

    private fun safeGet(key: String): String? =
        try { redisTemplate.opsForValue().get(key) } catch (e: Exception) { null }

    private fun safeSet(key: String, value: String, ttl: Duration) {
        try { redisTemplate.opsForValue().set(key, value, ttl) } catch (_: Exception) { }
    }
}