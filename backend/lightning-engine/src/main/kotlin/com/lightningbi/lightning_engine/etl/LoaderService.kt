package com.lightningbi.lightning_engine.etl

import com.lightningbi.lightning_engine.service.Naming
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import java.sql.PreparedStatement

@Service
class LoaderService(
    private val jdbcTemplate: JdbcTemplate
) {
    private val log = LoggerFactory.getLogger(LoaderService::class.java)

    /**
     * ClickHouse regge insert molto grandi, ma tenere in un solo batch tutte
     * le righe di un full reload significa costruire in memoria un array per
     * ogni riga prima di spedire. A blocchi il consumo resta piatto.
     */
    private val batchSize = 10_000

    /**
     * Carico incrementale: aggiunge righe senza toccare quelle esistenti.
     *
     * NOTA sul partizionamento: la versione precedente tentava un
     * DROP PARTITION basato su una colonna _partition_key che nessuno
     * popolava, su tabelle create senza clausola PARTITION BY. Non poteva
     * funzionare in nessun caso: senza partizioni dichiarate ClickHouse
     * rifiuta il comando, e senza chiave valorizzata non c'era comunque
     * niente da rilasciare.
     *
     * Finché le tabelle d'area non dichiarano una PARTITION BY, l'unico
     * incrementale corretto è l'append puro. Chi lo usa deve sapere che
     * righe già presenti verranno duplicate se la view le riespone: il
     * filtro su lbi_updated_at serve esattamente a evitarlo.
     */
    fun load(tabellaFisica: String, rows: List<Map<String, Any?>>, columns: List<String>) {
        val table = requireIdentifier(tabellaFisica, "table")
        val cols = columns.filter { it != "_partition_key" }.map { requireIdentifier(it, "column") }
        if (rows.isEmpty()) {
            log.info("Load su {}: nessuna riga da inserire", table)
            return
        }
        insertBatched(table, rows, cols)
        log.info("Load su {}: {} righe inserite", table, rows.size)
    }

    /**
     * Ricarico completo: svuota la tabella e reinserisce tutto.
     *
     * Il TRUNCATE avviene PRIMA di sapere se l'insert andrà a buon fine: se
     * l'insert fallisce a metà, la tabella resta parziale. ClickHouse non ha
     * transazioni, quindi non c'è modo di renderlo atomico senza passare da
     * una tabella di staging e uno scambio (EXCHANGE TABLES). Finché i volumi
     * restano quelli attuali il rischio è accettabile: il rimedio è rilanciare
     * la sincronizzazione.
     */
    fun truncateAndLoad(tabellaFisica: String, rows: List<Map<String, Any?>>, columns: List<String>) {
        val table = requireIdentifier(tabellaFisica, "table")
        val cols = columns.filter { it != "_partition_key" }.map { requireIdentifier(it, "column") }

        jdbcTemplate.execute("TRUNCATE TABLE $table")

        if (rows.isEmpty()) {
            log.warn("Full reload su {}: la sorgente non ha restituito righe, la tabella resta vuota", table)
            return
        }
        insertBatched(table, rows, cols)
        log.info("Full reload su {}: {} righe inserite", table, rows.size)
    }

    private fun insertBatched(table: String, rows: List<Map<String, Any?>>, cols: List<String>) {
        val placeholders = cols.joinToString(",") { "?" }
        val sql = "INSERT INTO $table (${cols.joinToString(",")}) VALUES ($placeholders)"

        rows.chunked(batchSize).forEach { chunk ->
            // BatchPreparedStatementSetter invece della variante con
            // List<Array<Any>>: quest'ultima non accetta valori nulli, e
            // TransformService ne produce legittimamente (dimensioni non
            // obbligatorie). Il vecchio "as Any" serviva proprio ad aggirare
            // quel vincolo di tipo, al prezzo di un'eccezione a runtime sulla
            // prima riga con un campo vuoto.
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
    private fun requireIdentifier(value: String, what: String): String {
        val normalized = Naming.slug(value)
        require(normalized == value) {
            "Identificatore $what non normalizzato nel registry: '$value' (atteso '$normalized')."
        }
        return value
    }
}