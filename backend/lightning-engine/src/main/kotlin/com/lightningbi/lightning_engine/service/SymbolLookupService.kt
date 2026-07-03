package com.lightningbi.lightning_engine.service

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
    private val validName = Regex("^[a-z0-9_]+$")
    private val chunkSize = 5000
    private val lockTtl = Duration.ofMinutes(5)
    private val maxRetries = 10

    private val unlockScript = DefaultRedisScript(
        """
        if redis.call("get", KEYS[1]) == ARGV[1] then
            return redis.call("del", KEYS[1])
        else
            return 0
        end
        """.trimIndent(), Long::class.java
    )

    fun getOrCreateIds(dimensioneNome: String, values: Set<String>): Map<String, Long> {
        require(validName.matches(dimensioneNome)) { "Invalid dimension name: $dimensioneNome" }
        if (values.isEmpty()) return emptyMap()

        val table = "ch_lbi_symbol_$dimensioneNome"
        val lockKey = "symbol-lock:$dimensioneNome"
        val lockValue = UUID.randomUUID().toString()

        var attempts = 0
        var acquired = false
        while (!acquired && attempts < maxRetries) {
            acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, lockTtl) ?: false
            if (!acquired) {
                attempts++
                Thread.sleep(500)
            }
        }
        if (!acquired) throw IllegalStateException("Could not acquire symbol lock for $dimensioneNome after $maxRetries attempts")

        try {
            val existing = fetchExisting(table, values)
            val missing = values - existing.keys
            if (missing.isEmpty()) return existing

            // max(value_id) va letto DENTRO il lock: garantisce che nessun altro processo
            // stia scrivendo id concorrenti sulla stessa dimensione nel frattempo
            var nextId = (jdbcTemplate.queryForObject(
                "SELECT max(value_id) FROM $table", Long::class.java
            ) ?: 0L) + 1

            val newRows = missing.map { it to nextId++ }
            newRows.chunked(chunkSize).forEach { chunk ->
                jdbcTemplate.batchUpdate(
                    "INSERT INTO $table (value_id, value_string) VALUES (?, ?)",
                    chunk.map { arrayOf<Any>(it.second, it.first) }
                )
            }

            return existing + newRows.toMap()
        } finally {
            redisTemplate.execute(unlockScript, listOf(lockKey), lockValue)
        }
    }

    private fun fetchExisting(table: String, values: Set<String>): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        values.chunked(chunkSize).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            result += jdbcTemplate.query(
                "SELECT value_string, value_id FROM $table WHERE value_string IN ($placeholders)",
                { rs, _ -> rs.getString("value_string") to rs.getLong("value_id") },
                *chunk.toTypedArray()
            ).toMap()
        }
        return result
    }
}