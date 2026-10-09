package com.lightningbi.lightning_engine.etl

import com.lightningbi.lightning_engine.service.Naming
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
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


    /** Massimo numero di riga: lbi_rid è un UInt32. */
    private val MAX_RID = 4_294_967_295L

    /** Chiavi per istruzione di cancellazione: tiene la lunghezza dell'SQL ragionevole. */
    private val deleteChunk = 5_000


    /**
     * Append puro: aggiunge righe senza toccare quelle esistenti. Righe già
     * presenti verranno duplicate se la sorgente le riespone.
     *
     * Ogni riga riceve un numero di riga persistente (lbi_rid), a partire da
     * [primoRid]. Restituisce il primo numero ancora libero, da passare al
     * caricamento successivo.
     */
    /**
     * Append puro: aggiunge le righe del blocco senza toccare quelle esistenti.
     * Righe già presenti verranno duplicate se la sorgente le riespone.
     *
     * Ogni riga riceve un numero di riga persistente (lbi_rid), a partire da
     * [primoRid]. Restituisce il primo numero ancora libero, da passare al
     * caricamento successivo.
     *
     * @param columns colonne da scrivere, nell'ordine: ognuna deve stare nel blocco
     *   (tra gli id o tra le copie numeriche)
     */
    fun load(tabellaFisica: String, blocco: BloccoColonne, columns: List<String>, primoRid: Long, sorgente: Int): Long {
        val table = requireIdentifier(tabellaFisica, "table")
        val cols = columns.map { requireIdentifier(it, "column") }
        require(Naming.RID_COLUMN !in cols) { "La colonna '${Naming.RID_COLUMN}' non si carica: la assegna il loader" }
        if (blocco.righe == 0) {
            log.info("Load su {}: nessuna riga da inserire", table)
            return primoRid
        }
        require(Naming.SRC_COLUMN !in cols) { "La colonna '${Naming.SRC_COLUMN}' non si carica: la assegna il loader" }
        require(sorgente in 0..255) { "Numero di sorgente non valido: $sorgente" }
        val ultimo = primoRid + blocco.righe - 1
        check(ultimo <= MAX_RID) {
            "La tabella $table supera i $MAX_RID numeri di riga: serve un ricarico completo"
        }
        insertBatched(table, blocco, cols, primoRid, sorgente)
        log.info("Load su {}: {} righe inserite", table, blocco.righe)
        return ultimo + 1
    }

    /** Primo numero di riga libero di una tabella (0 se è vuota). */
    fun prossimoRid(tabellaFisica: String): Long {
        val table = requireIdentifier(tabellaFisica, "table")
        return jdbcTemplate.queryForObject(
            "SELECT if(count() = 0, 0, toInt64(max(${Naming.RID_COLUMN})) + 1) FROM $table",
            Long::class.java
        ) ?: 0L
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
    fun deleteUnits(tabellaFisica: String, colonneUnita: List<String>, chiaviId: List<List<Long>>, sorgente: Int): Int {
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
            // Solo le righe di questa sorgente: la stessa chiave può esistere in un altro database.
            val condizioneSorgente = "($condizione) AND ${Naming.SRC_COLUMN} = $sorgente"
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
    fun distinctKeyIds(tabellaFisica: String, colonneUnita: List<String>, sorgente: Int): Set<List<Long>> {
        val table = requireIdentifier(tabellaFisica, "table")
        val colonne = colonneUnita.map { requireIdentifier(Naming.column(it), "column") }
        require(colonne.isNotEmpty()) { "Servono le colonne dell'unità" }

        val risultato = HashSet<List<Long>>()
        jdbcTemplate.query(
            "SELECT DISTINCT ${colonne.joinToString(", ")} FROM $table WHERE ${Naming.SRC_COLUMN} = $sorgente"
        ) { rs ->
            risultato.add(colonne.indices.map { rs.getLong(it + 1) })
        }
        return risultato
    }

    private fun insertBatched(table: String, blocco: BloccoColonne, cols: List<String>, primoRid: Long, sorgente: Int) {
        val tutte = cols + Naming.RID_COLUMN + Naming.SRC_COLUMN
        val placeholders = tutte.joinToString(",") { "?" }
        val sql = "INSERT INTO $table (${tutte.joinToString(",")}) VALUES ($placeholders)"

        // Per ogni colonna l'array da cui leggere, risolto una volta sola (niente ricerche per nome per riga).
        val idCols = arrayOfNulls<LongArray>(cols.size)
        val numCols = arrayOfNulls<Array<BigDecimal?>>(cols.size)
        cols.forEachIndexed { k, col ->
            val ids = blocco.ids[col]
            if (ids != null) idCols[k] = ids
            else numCols[k] = blocco.numeri[col] ?: error("La colonna '$col' non è nel blocco trasformato")
        }

        var inizio = 0
        while (inizio < blocco.righe) {
            val base = inizio
            val dimensione = minOf(batchSize, blocco.righe - base)
            jdbcTemplate.batchUpdate(sql, object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    val r = base + i
                    for (k in cols.indices) {
                        val ids = idCols[k]
                        // Le copie numeriche possono essere null (valore mancante): setObject lo accetta.
                        if (ids != null) ps.setLong(k + 1, ids[r]) else ps.setObject(k + 1, numCols[k]!![r])
                    }
                    ps.setLong(cols.size + 1, primoRid + r)
                    ps.setInt(cols.size + 2, sorgente)
                }

                override fun getBatchSize(): Int = dimensione
            })
            inizio += dimensione
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