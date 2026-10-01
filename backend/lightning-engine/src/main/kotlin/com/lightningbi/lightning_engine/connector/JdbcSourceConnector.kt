package com.lightningbi.lightning_engine.connector

import com.lightningbi.lightning_engine.model.SourceConnection
import com.lightningbi.lightning_engine.service.ColumnInfo
import com.lightningbi.lightning_engine.service.SourceConnectionService
import com.lightningbi.lightning_engine.service.TableInfo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.DriverManager
import com.lightningbi.lightning_engine.service.KeyQueryProbe
import com.lightningbi.lightning_engine.service.QueryNonValidaException
import com.lightningbi.lightning_engine.service.QueryScadutaException
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.time.LocalDateTime
import com.lightningbi.lightning_engine.service.KeyQueryResult
import com.lightningbi.lightning_engine.service.TroppeChiaviException
import java.sql.ResultSet
/**
 * Connettore per le sorgenti JDBC: SQL Server (mssql) e PostgreSQL (psql).
 *
 * Parametri della connessione: jdbcUrl, username, database. Segreto: password.
 * Il driver non si sceglie a mano: lo ricava dal tipo.
 *
 * Ogni operazione apre la sua connessione e la richiude (vedi SourceConnector).
 */
@Component
class JdbcSourceConnector(
    private val connectionService: SourceConnectionService
) : SourceConnector {

    private val log = LoggerFactory.getLogger(JdbcSourceConnector::class.java)

    companion object {
        const val PARAM_JDBC_URL = "jdbcUrl"
        const val PARAM_USERNAME = "username"

        /**
         * Nome del database, usato nel nome fisico delle tabelle importate
         * (es. mssql_sem__clienti). Non serve per connettersi: l'indirizzo lo
         * contiene già.
         */
        const val PARAM_DATABASE = "database"
        const val SECRET_PASSWORD = "password"
    }

    private val driverPerTipo = mapOf(
        "mssql" to "com.microsoft.sqlserver.jdbc.SQLServerDriver",
        "psql" to "org.postgresql.Driver"
    )

    override val tipiSupportati: Set<String> = driverPerTipo.keys

    /** Gli schemi di sistema non sono mai sorgenti dati. */
    private val schemiDiSistema = setOf("information_schema", "sys", "guest", "pg_catalog", "pg_toast")

    /** Identificatori ammessi nelle query costruite qui (schema e tabella). */
    private val identificatoreSicuro = Regex("^[A-Za-z_][A-Za-z0-9_\$#]*$")

    override fun testConnection(connection: SourceConnection) {
        apri(connection).use { conn ->
            check(conn.isValid(5)) { "La connessione a '${connection.nome}' non risponde" }
        }
    }

    override fun listSchemas(connection: SourceConnection): List<String> =
        apri(connection).use { conn ->
            val risultato = mutableListOf<String>()
            conn.metaData.schemas.use { rs ->
                while (rs.next()) risultato.add(rs.getString("TABLE_SCHEM"))
            }
            risultato
                .filter { it.lowercase() !in schemiDiSistema && !it.startsWith("db_") }
                .sorted()
        }

    override fun listTables(connection: SourceConnection, schema: String?): List<TableInfo> =
        apri(connection).use { conn ->
            val risultato = mutableListOf<TableInfo>()
            conn.metaData.getTables(null, schema, "%", arrayOf("TABLE", "VIEW")).use { rs ->
                while (rs.next()) {
                    risultato.add(
                        TableInfo(
                            schema = rs.getString("TABLE_SCHEM") ?: "",
                            name = rs.getString("TABLE_NAME")
                        )
                    )
                }
            }
            risultato.sortedBy { it.name.lowercase() }
        }

    override fun listColumns(connection: SourceConnection, schema: String?, tabella: String): List<ColumnInfo> =
        apri(connection).use { conn ->
            // Nei pattern di getColumns "_" e "%" sono jolly: il nome esatto della
            // tabella (che contiene spesso "_", es. QLK_VISTACLIENTI) va protetto.
            val esc = conn.metaData.searchStringEscape
            val pattern = tabella.replace(esc, esc + esc).replace("_", esc + "_").replace("%", esc + "%")
            val risultato = mutableListOf<ColumnInfo>()
            conn.metaData.getColumns(null, schema, pattern, "%").use { rs ->
                while (rs.next()) {
                    val scala = rs.getInt("DECIMAL_DIGITS").takeUnless { rs.wasNull() }
                    risultato.add(
                        ColumnInfo(
                            name = rs.getString("COLUMN_NAME"),
                            typeName = rs.getString("TYPE_NAME"),
                            scale = scala
                        )
                    )
                }
            }
            risultato
        }

    override fun sampleRows(
        connection: SourceConnection,
        schema: String?,
        tabella: String,
        limit: Int
    ): List<Map<String, Any?>> =
        apri(connection).use { conn ->
            val qualificata = qualifica(schema, tabella)
            // Sintassi dal prodotto DB reale, non dal tipo dichiarato: più robusto.
            val prodotto = conn.metaData.databaseProductName ?: ""
            val sql = if (prodotto.contains("Microsoft SQL Server", ignoreCase = true)) {
                "SELECT TOP $limit * FROM $qualificata"
            } else {
                "SELECT * FROM $qualificata LIMIT $limit"
            }
            val righe = mutableListOf<Map<String, Any?>>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    val meta = rs.metaData
                    while (rs.next()) {
                        righe.add((1..meta.columnCount).associate { i -> meta.getColumnName(i) to rs.getObject(i) })
                    }
                }
            }
            righe
        }
    override fun sourceNow(connection: SourceConnection): LocalDateTime =
        apri(connection).use { conn ->
            // Sintassi dal prodotto DB reale, come in sampleRows. Si chiede un
            // timestamp SENZA fuso (SYSDATETIME / localtimestamp): è l'ora
            // locale della sorgente, la stessa dei suoi datastamp.
            val prodotto = conn.metaData.databaseProductName ?: ""
            val sql = if (prodotto.contains("Microsoft SQL Server", ignoreCase = true)) {
                "SELECT SYSDATETIME()"
            } else {
                "SELECT localtimestamp"
            }
            conn.createStatement().use { stmt ->
                stmt.executeQuery(sql).use { rs ->
                    check(rs.next()) { "La sorgente non ha restituito l'ora corrente" }
                    // getObject con LocalDateTime: nessuna conversione di fuso.
                    rs.getObject(1, LocalDateTime::class.java)
                        ?: error("La sorgente ha restituito un'ora nulla")
                }
            }
        }

    override fun probeKeyQuery(
        connection: SourceConnection,
        query: String,
        ultimaSync: LocalDateTime,
        timeoutSecondi: Int
    ): KeyQueryProbe {
        val (sql, parametri) = preparaQueryDiChiavi(query)
        try {
            return apri(connection).use { conn ->
                conn.isReadOnly = true
                conn.prepareStatement(sql).use { stmt ->
                    stmt.queryTimeout = timeoutSecondi
                    // La prova non restituisce mai un insieme di chiavi: una riga basta.
                    stmt.maxRows = 1
                    // :ultima_sync è un parametro legato, mai sostituito nel testo.
                    for (k in 1..parametri) stmt.setObject(k, ultimaSync)
                    stmt.executeQuery().use { rs ->
                        val meta = rs.metaData
                        val colonne = (1..meta.columnCount).map { meta.getColumnLabel(it) }
                        val esempio = if (rs.next()) {
                            colonne.mapIndexed { i, nome -> nome to rs.getObject(i + 1) }.toMap()
                        } else null
                        KeyQueryProbe(colonne, esempio)
                    }
                }
            }
        } catch (e: SQLTimeoutException) {
            throw QueryScadutaException("La query non ha risposto entro $timeoutSecondi secondi", e)
        } catch (e: SQLException) {
            // PostgreSQL segnala il timeout con lo stato 57014 (query annullata).
            if (e.sqlState == "57014") {
                throw QueryScadutaException("La query non ha risposto entro $timeoutSecondi secondi", e)
            }
            throw QueryNonValidaException("La sorgente ha rifiutato la query: ${e.message}", e)
        }
    }
    /** Massimo di parametri legati per istruzione: SQL Server ne ammette 2100. */
    private val maxParametri = 2000

    override fun runKeyQuery(
        connection: SourceConnection,
        query: String,
        ultimaSync: LocalDateTime,
        colonneUnita: List<String>,
        maxRighe: Int,
        timeoutSecondi: Int
    ): KeyQueryResult {
        require(maxRighe > 0) { "Il massimo di righe deve essere positivo" }
        val (sql, parametri) = preparaQueryDiChiavi(query)
        try {
            return apri(connection).use { conn ->
                conn.isReadOnly = true
                conn.prepareStatement(sql).use { stmt ->
                    stmt.queryTimeout = timeoutSecondi
                    stmt.fetchSize = 5000
                    // Una riga oltre il massimo basta per accorgersi che lo supera.
                    stmt.maxRows = maxRighe + 1
                    // :ultima_sync è un parametro legato, mai sostituito nel testo.
                    for (k in 1..parametri) stmt.setObject(k, ultimaSync)
                    stmt.executeQuery().use { rs ->
                        val etichette = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
                        if (etichette.size != colonneUnita.size) {
                            throw QueryNonValidaException(
                                "La query restituisce le colonne [${etichette.joinToString(", ")}] ma l'unità è " +
                                        "[${colonneUnita.joinToString(", ")}]: devono coincidere (stessi nomi)"
                            )
                        }
                        val posizioni = colonneUnita.map { col ->
                            val i = etichette.indexOfFirst { it.equals(col, ignoreCase = true) }
                            if (i < 0) {
                                throw QueryNonValidaException(
                                    "La query non restituisce la colonna '$col' dell'unità. Colonne restituite: " +
                                            etichette.joinToString(", ")
                                )
                            }
                            i + 1
                        }

                        val chiavi = ArrayList<List<Any?>>()
                        while (rs.next()) {
                            // Mai troncare: un risultato troncato salterebbe unità cambiate in silenzio.
                            if (chiavi.size >= maxRighe) {
                                throw TroppeChiaviException(
                                    maxRighe,
                                    "La query ha restituito più di $maxRighe unità. La sincronizzazione si ferma: " +
                                            "alza il massimo oppure lancia il ricarico completo a mano"
                                )
                            }
                            chiavi.add(posizioni.map { rs.getObject(it) })
                        }
                        KeyQueryResult(chiavi)
                    }
                }
            }
        } catch (e: SQLTimeoutException) {
            throw QueryScadutaException("La query non ha risposto entro $timeoutSecondi secondi", e)
        } catch (e: SQLException) {
            if (e.sqlState == "57014") {
                throw QueryScadutaException("La query non ha risposto entro $timeoutSecondi secondi", e)
            }
            throw QueryNonValidaException("La sorgente ha rifiutato la query: ${e.message}", e)
        }
    }

    override fun extractUnits(
        connection: SourceConnection,
        schema: String?,
        tabella: String,
        colonneUnita: List<String>,
        chiavi: List<List<Any?>>,
        consumatore: (Sequence<Map<String, Any?>>) -> Unit
    ) {
        require(colonneUnita.isNotEmpty()) { "Servono le colonne dell'unità" }
        require(chiavi.all { it.size == colonneUnita.size && it.none { v -> v == null } }) {
            "Le chiavi delle unità devono avere tutte le colonne dell'unità e nessun valore nullo"
        }
        if (chiavi.isEmpty()) {
            consumatore(emptySequence())
            return
        }

        val qualificata = qualifica(schema, tabella)
        val colonne = colonneUnita.map { quota(it) }
        val perBlocco = maxOf(1, maxParametri / colonneUnita.size)

        // Le sorgenti non hanno il confronto di righe in comune (SQL Server no):
        // una colonna sola usa IN, più colonne usano (a = ? AND b = ?) OR (...).
        // Se il consumatore si ferma a metà, la chiusura della connessione
        // rilascia statement e result set rimasti aperti.
        apri(connection).use { conn ->
            if (connection.tipo == "psql") conn.autoCommit = false
            val righe = sequence {
                for (blocco in chiavi.chunked(perBlocco)) {
                    val condizione = if (colonne.size == 1) {
                        "${colonne[0]} IN (${blocco.joinToString(",") { "?" }})"
                    } else {
                        blocco.joinToString(" OR ") { "(" + colonne.joinToString(" AND ") { c -> "$c = ?" } + ")" }
                    }
                    conn.prepareStatement("SELECT * FROM $qualificata WHERE $condizione").use { stmt ->
                        stmt.fetchSize = 5000
                        var k = 1
                        for (chiave in blocco) for (valore in chiave) stmt.setObject(k++, valore)
                        stmt.executeQuery().use { rs ->
                            val etichette = etichetteColonne(rs, qualificata)
                            while (rs.next()) yield(rigaCorrente(rs, etichette))
                        }
                    }
                }
            }
            consumatore(righe)
        }
    }

    override fun extractKeys(
        connection: SourceConnection,
        schema: String?,
        tabella: String,
        colonneUnita: List<String>,
        consumatore: (Sequence<List<Any?>>) -> Unit
    ) {
        require(colonneUnita.isNotEmpty()) { "Servono le colonne dell'unità" }
        val qualificata = qualifica(schema, tabella)
        val colonne = colonneUnita.map { quota(it) }

        apri(connection).use { conn ->
            if (connection.tipo == "psql") conn.autoCommit = false
            conn.prepareStatement("SELECT DISTINCT ${colonne.joinToString(", ")} FROM $qualificata").use { stmt ->
                stmt.fetchSize = 5000
                stmt.executeQuery().use { rs ->
                    val n = colonneUnita.size
                    consumatore(generateSequence { if (rs.next()) (1..n).map { rs.getObject(it) } else null })
                }
            }
        }
    }

    /**
     * Nome di colonna tra virgolette. Le colonne dell'unità arrivano dal
     * registry e possono avere spazi o maiuscole: si quotano, e si rifiuta
     * ciò che potrebbe uscire dalle virgolette.
     */
    private fun quota(nome: String): String {
        require(nome.isNotBlank() && '"' !in nome && nome.none { it.isISOControl() }) {
            "Nome colonna non valido: '$nome'"
        }
        return "\"$nome\""
    }

    private fun etichetteColonne(rs: ResultSet, qualificata: String): List<String> {
        val meta = rs.metaData
        val etichette = (1..meta.columnCount).map { meta.getColumnLabel(it).lowercase() }
        val duplicati = etichette.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicati.isNotEmpty()) {
            throw IllegalStateException(
                "La tabella $qualificata espone colonne con etichetta duplicata: " +
                        "${duplicati.joinToString(", ")}. Ogni colonna deve avere un alias univoco."
            )
        }
        return etichette
    }

    private fun rigaCorrente(rs: ResultSet, etichette: List<String>): Map<String, Any?> {
        val riga = HashMap<String, Any?>(etichette.size)
        etichette.forEachIndexed { i, etichetta -> riga[etichetta] = rs.getObject(i + 1) }
        return riga
    }

    /**
     * Prepara il testo di una query di chiavi: accetta una sola istruzione di
     * sola lettura (SELECT o WITH), senza commenti, e trasforma ogni
     * `:ultima_sync` fuori dai letterali in un segnaposto `?`. Restituisce il
     * testo e quanti segnaposto ci sono, da legare tutti allo stesso valore.
     */
    private fun preparaQueryDiChiavi(query: String): Pair<String, Int> {
        val testo = query.trim().removeSuffix(";").trim()
        if (testo.isEmpty()) throw QueryNonValidaException("La query è vuota")

        val prima = testo.split(Regex("\\s+"), limit = 2).first().lowercase()
        if (prima != "select" && prima != "with") {
            throw QueryNonValidaException("La query deve iniziare con SELECT o WITH: solo lettura")
        }

        val sql = StringBuilder()
        var parametri = 0
        var inLetterale = false
        var i = 0
        while (i < testo.length) {
            val c = testo[i]
            if (inLetterale) {
                sql.append(c)
                if (c == '\'') {
                    if (i + 1 < testo.length && testo[i + 1] == '\'') {
                        sql.append('\'')
                        i++
                    } else {
                        inLetterale = false
                    }
                }
                i++
                continue
            }
            when {
                c == '\'' -> { inLetterale = true; sql.append(c); i++ }
                c == ';' -> throw QueryNonValidaException("Una sola istruzione per query: trovato ';' nel testo")
                (c == '-' && testo.getOrNull(i + 1) == '-') || (c == '/' && testo.getOrNull(i + 1) == '*') ->
                    throw QueryNonValidaException("I commenti non sono ammessi nella query")
                testo.startsWith(":ultima_sync", i, ignoreCase = true) &&
                        testo.getOrNull(i + 12)?.let { it.isLetterOrDigit() || it == '_' } != true &&
                        testo.getOrNull(i - 1) != ':' -> {
                    sql.append('?')
                    parametri++
                    i += 12
                }
                else -> { sql.append(c); i++ }
            }
        }
        return sql.toString() to parametri
    }

    override fun extract(
        connection: SourceConnection,
        schema: String?,
        tabella: String,
        consumatore: (Sequence<Map<String, Any?>>) -> Unit
    ) {
        val qualificata = qualifica(schema, tabella)

        apri(connection).use { conn ->
            if (connection.tipo == "psql") conn.autoCommit = false
            conn.prepareStatement("SELECT * FROM $qualificata").use { stmt ->
                stmt.fetchSize = 5000
                stmt.executeQuery().use { rs ->
                    val meta = rs.metaData

                    // getColumnLabel, non getColumnName. Minuscolo per far
                    // combaciare le chiavi con le colonne normalizzate da Naming.
                    val etichette = (1..meta.columnCount).map { meta.getColumnLabel(it).lowercase() }

                    val duplicati = etichette.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                    if (duplicati.isNotEmpty()) {
                        throw IllegalStateException(
                            "La tabella $qualificata espone colonne con etichetta duplicata: " +
                                    "${duplicati.joinToString(", ")}. Ogni colonna deve avere un alias univoco."
                        )
                    }

                    log.debug("Estrazione da {}: colonne {}", qualificata, etichette)

                    var conteggio = 0L
                    val righe = generateSequence {
                        if (!rs.next()) {
                            null
                        } else {
                            val riga = HashMap<String, Any?>(etichette.size)
                            etichette.forEachIndexed { i, etichetta -> riga[etichetta] = rs.getObject(i + 1) }
                            conteggio++
                            riga
                        }
                    }

                    consumatore(righe)
                    log.debug("Estrazione da {}: {} righe", qualificata, conteggio)
                }
            }
        }
    }

    // ---------- interni ----------

    private fun apri(connection: SourceConnection): Connection {
        val driver = driverPerTipo[connection.tipo]
            ?: error("Il connettore JDBC non gestisce il tipo '${connection.tipo}'")
        val url = connection.parametri[PARAM_JDBC_URL]
            ?: error("La connessione '${connection.nome}' non ha il parametro $PARAM_JDBC_URL")
        val utente = connection.parametri[PARAM_USERNAME]
            ?: error("La connessione '${connection.nome}' non ha il parametro $PARAM_USERNAME")
        val password = connectionService.decryptSecret(connection, SECRET_PASSWORD)

        Class.forName(driver)
        return DriverManager.getConnection(url, utente, password)
    }

    /** schema.tabella con entrambi i pezzi verificati: finiscono dentro una query. */
    private fun qualifica(schema: String?, tabella: String): String {
        require(identificatoreSicuro.matches(tabella)) { "Nome tabella non valido: '$tabella'" }
        if (schema.isNullOrBlank()) return "\"$tabella\""
        require(identificatoreSicuro.matches(schema)) { "Nome schema non valido: '$schema'" }
        return "\"$schema\".\"$tabella\""
    }
}