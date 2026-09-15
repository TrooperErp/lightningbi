package com.lightningbi.lightning_engine.etl

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.sql.Connection
import java.sql.Timestamp

@Component
class JdbcExtractor : ExtractorPort {

    private val log = LoggerFactory.getLogger(JdbcExtractor::class.java)

    /** Colonna tecnica opzionale, usata solo per il filtro incrementale. */
    private val updatedAtAlias = "lbi_updated_at"

    /**
     * @param config deve contenere jdbcUrl, username, password, driverClassName, viewName
     * @param lastSync timestamp da cui filtrare. Se null, o se la view non
     *   espone lbi_updated_at, si estrae tutto (comportamento FULL_RELOAD).
     *
     * La colonna lbi_updated_at non è più obbligatoria. Molte view collegate
     * a LightningBI sono view aziendali preesistenti, scritte per altri
     * scopi molto prima che esistesse questo motore: pretendere che
     * espongano una colonna tecnica in più le renderebbe automaticamente
     * incompatibili. La modalità FULL_RELOAD (ricarico completo ogni volta)
     * non ha comunque bisogno di sapere cosa è cambiato, quindi non le serve.
     */
    override fun extract(config: Map<String, Any>, lastSync: String?): Sequence<Map<String, Any?>> {
        val jdbcUrl = config["jdbcUrl"] as String
        val username = config["username"] as String
        val password = config["password"] as String // già decifrata dal chiamante
        val driverClassName = config["driverClassName"] as String
        val viewName = config["viewName"] as String

        Class.forName(driverClassName)

        return sequence {
            java.sql.DriverManager.getConnection(jdbcUrl, username, password).use { conn ->
                val availableColumns = probeColumns(conn, viewName)
                val hasUpdatedAt = updatedAtAlias in availableColumns

                // Il filtro incrementale si applica solo se richiesto
                // (lastSync non null) E la colonna esiste davvero sulla
                // view. Una sorgente FULL_RELOAD, o anche una INCREMENTAL
                // la cui view preesistente non ha mai avuto quella colonna,
                // viene estratta per intero senza errori.
                val incrementale = lastSync != null && hasUpdatedAt
                val query = if (incrementale) {
                    "SELECT * FROM $viewName WHERE $updatedAtAlias > ?"
                } else {
                    "SELECT * FROM $viewName"
                }

                conn.prepareStatement(query).use { stmt ->
                    stmt.fetchSize = 5000
                    if (incrementale) {
                        stmt.setTimestamp(1, Timestamp.valueOf(lastSync))
                    }
                    stmt.executeQuery().use { rs ->
                        val meta = rs.metaData

                        // getColumnLabel, non getColumnName: vedi nota
                        // originale, invariata. Il lowercase per far
                        // combaciare le chiavi con colonnaFisica normalizzata
                        // da Naming.
                        val columnLabels = (1..meta.columnCount).map {
                            meta.getColumnLabel(it).lowercase()
                        }

                        val duplicati = columnLabels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
                        if (duplicati.isNotEmpty()) {
                            throw IllegalStateException(
                                "La view $viewName espone colonne con etichetta duplicata: " +
                                        "${duplicati.joinToString(", ")}. Ogni colonna deve avere un alias univoco."
                            )
                        }

                        log.debug(
                            "Estrazione da {}: colonne {}, incrementale={}",
                            viewName, columnLabels, incrementale
                        )

                        var count = 0L
                        while (rs.next()) {
                            val row = HashMap<String, Any?>(columnLabels.size)
                            columnLabels.forEachIndexed { i, label ->
                                row[label] = rs.getObject(i + 1)
                            }
                            count++
                            yield(row)
                        }
                        log.debug("Estrazione da {}: {} righe", viewName, count)
                    }
                }
            }
        }
    }

    /**
     * Legge le colonne esposte dalla view senza materializzare righe (WHERE
     * 1=0), per decidere se il filtro incrementale è applicabile prima di
     * costruire la query definitiva.
     */
    private fun probeColumns(conn: Connection, viewName: String): Set<String> {
        conn.prepareStatement("SELECT * FROM $viewName WHERE 1 = 0").use { stmt ->
            stmt.executeQuery().use { rs ->
                val meta = rs.metaData
                return (1..meta.columnCount).map { meta.getColumnLabel(it).lowercase() }.toSet()
            }
        }
    }
}