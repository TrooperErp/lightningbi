package com.lightningbi.lightning_engine.etl

import com.lightningbi.lightning_engine.service.Naming
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.sql.PreparedStatement

/** Come si cancellano le righe di un'unità su ClickHouse. */
enum class DeleteMode {
    /** `ALTER TABLE ... DELETE` con attesa del completamento: funziona su ogni versione, ma è lenta. */
    MUTATION,

    /** `DELETE FROM`: rapido, richiede un ClickHouse recente (23.3 o successivo). */
    LIGHTWEIGHT
}

@Service
class LoaderService(
    private val jdbcTemplate: JdbcTemplate,
    /** Vedi [DeleteMode]. Default sicuro: MUTATION. Si cambia nel compose, senza toccare il codice. */
    @Value("\${lbi.etl.delete-mode:MUTATION}") deleteModeConfigurato: String
) {
    private val log = LoggerFactory.getLogger(LoaderService::class.java)

    private val deleteMode: DeleteMode = try {
        DeleteMode.valueOf(deleteModeConfigurato.trim().uppercase())
    } catch (_: IllegalArgumentException) {
        throw IllegalStateException(
            "lbi.etl.delete-mode non valido: '$deleteModeConfigurato' (valori ammessi: ${DeleteMode.values().joinToString(", ")})"
        )
    }

    /**
     * ClickHouse regge insert molto grandi, ma tenere in un solo batch tutte
     * le righe di un full reload significa costruire in memoria un array per
     * ogni riga prima di spedire. A blocchi il consumo resta piatto.
     */
    private val batchSize = 10_000

    /** Chiavi per istruzione di cancellazione: tiene la lunghezza dell'SQL ragionevole. */
    private val deleteChunk = 5_000

    /**
     * Append puro: aggiunge righe senza toccare quelle esistenti. Righe già
     * presenti verranno duplicate se la sorgente le riespone.
     */
    fun load(tabellaFisica: String, rows: List<Map<String, Any?>>, columns: List<String>) {
        val table = requireIdentifier(tabellaFisica, "table")
        val cols = columns.map { requireIdentifier(it, "column") }
        if (rows.isEmpty()) {
            log.info("Load su {}: nessuna riga da inserire", table)
            return
        }
        insertBatched(table, rows, cols)
        log.info("Load su {}: {} righe inserite", table, rows.size)
    }

    /**
     * Svuota la tabella, senza caricare nulla: per i caricamenti a blocchi che
     * poi accodano con load().
     *
     * Il TRUNCATE avviene PRIMA di sapere se i load andranno a buon fine: se
     * un blocco fallisce a metà, la tabella resta parziale. ClickHouse non ha
     * transazioni, quindi non c'è modo di renderlo atomico senza passare da
     * una tabella di staging e uno scambio (EXCHANGE TABLES). Il rimedio è
     * rilanciare la sincronizzazione completa.
     */
    fun truncate(tabellaFisica: String) {
        val table = requireIdentifier(tabellaFisica, "table")
        jdbcTemplate.execute("TRUNCATE TABLE $table")
        log.info("Truncate su {}", table)
    }

    /**
     * Cancella le righe delle unità indicate, per sostituirle con quelle
     * rilette dalla sorgente. Le unità sono identificate dagli ID delle colonne
     * dell'unità (non dai valori): [chiaviId] ha una lista per unità, nell'ordine
     * di [colonneUnita].
     *
     * Con [DeleteMode.MUTATION] il comando aspetta il completamento della
     * mutation (`mutations_sync = 2`): è sincrona ma pesante su tabelle grandi.
     *
     * Restituisce quante unità ha cancellato (le chiavi passate, anche se non
     * avevano righe).
     */
    fun deleteUnits(tabellaFisica: String, colonneUnita: List<String>, chiaviId: List<List<Long>>): Int {
        val table = requireIdentifier(tabellaFisica, "table")
        val colonne = colonneUnita.map { requireIdentifier(Naming.column(it), "column") }
        require(colonne.isNotEmpty()) { "Servono le colonne dell'unità" }
        require(chiaviId.all { it.size == colonne.size }) {
            "Ogni chiave deve avere ${colonne.size} valori, uno per colonna dell'unità"
        }
        if (chiaviId.isEmpty()) return 0

        chiaviId.chunked(deleteChunk).forEach { blocco ->
            // Solo numeri (Long): nessun valore di testo finisce nell'SQL.
            val condizione = if (colonne.size == 1) {
                "${colonne[0]} IN (${blocco.joinToString(",") { it[0].toString() }})"
            } else {
                "(${colonne.joinToString(", ")}) IN (" +
                        blocco.joinToString(",") { chiave -> "(" + chiave.joinToString(",") + ")" } + ")"
            }
            val sql = when (deleteMode) {
                DeleteMode.MUTATION -> "ALTER TABLE $table DELETE WHERE $condizione SETTINGS mutations_sync = 2"
                DeleteMode.LIGHTWEIGHT -> "DELETE FROM $table WHERE $condizione"
            }
            jdbcTemplate.execute(sql)
        }
        log.info("Delete su {}: {} unità cancellate ({})", table, chiaviId.size, deleteMode)
        return chiaviId.size
    }

    /**
     * Chiavi distinte (come id) delle unità presenti in una tabella, per
     * rilevare le unità sparite dalla sorgente. Nell'ordine di [colonneUnita].
     */
    fun distinctKeyIds(tabellaFisica: String, colonneUnita: List<String>): Set<List<Long>> {
        val table = requireIdentifier(tabellaFisica, "table")
        val colonne = colonneUnita.map { requireIdentifier(Naming.column(it), "column") }
        require(colonne.isNotEmpty()) { "Servono le colonne dell'unità" }

        val risultato = HashSet<List<Long>>()
        jdbcTemplate.query("SELECT DISTINCT ${colonne.joinToString(", ")} FROM $table") { rs ->
            risultato.add(colonne.indices.map { rs.getLong(it + 1) })
        }
        return risultato
    }

    private fun insertBatched(table: String, rows: List<Map<String, Any?>>, cols: List<String>) {
        val placeholders = cols.joinToString(",") { "?" }
        val sql = "INSERT INTO $table (${cols.joinToString(",")}) VALUES ($placeholders)"

        rows.chunked(batchSize).forEach { chunk ->
            // BatchPreparedStatementSetter invece della variante con
            // List<Array<Any>>: quest'ultima non accetta valori nulli, e
            // TransformService ne produce legittimamente (le copie numeriche
            // dei valori mancanti).
            //
            // setObject accetta null e lo passa al driver, che darà semmai un
            // errore esplicito sulla colonna invece di un ClassCastException.
            jdbcTemplate.batchUpdate(sql, object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    val row = chunk[i]
                    cols.forEachIndexed { idx, col -> ps.setObject(idx + 1, row[col]) }
                }

                override fun getBatchSize(): Int = chunk.size
            })
        }
    }

    /**
     * Gli identificatori arrivano dal registry, dove Naming li ha normalizzati
     * alla creazione. Qui si verifica soltanto, come difesa contro SQL
     * injection su dati preesistenti o inseriti a mano.
     */
    private fun requireIdentifier(value: String, what: String): String =
        Naming.requirePhysical(value, what)
}