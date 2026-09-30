package com.lightningbi.lightning_engine.connector

import com.lightningbi.lightning_engine.model.SourceConnection
import com.lightningbi.lightning_engine.service.ColumnInfo
import com.lightningbi.lightning_engine.service.SourceConnectionService
import com.lightningbi.lightning_engine.service.TableInfo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.DriverManager

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
                    risultato.add(
                        ColumnInfo(
                            name = rs.getString("COLUMN_NAME"),
                            typeName = rs.getString("TYPE_NAME")
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