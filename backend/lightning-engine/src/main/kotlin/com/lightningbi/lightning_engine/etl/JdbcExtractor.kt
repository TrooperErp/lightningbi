package com.lightningbi.lightning_engine.etl

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.sql.Timestamp

@Component
class JdbcExtractor : ExtractorPort {

    private val log = LoggerFactory.getLogger(JdbcExtractor::class.java)

    /** Colonna tecnica che la view espone sempre, usata per il filtro incrementale. */
    private val updatedAtAlias = "lbi_updated_at"

    override fun extract(config: Map<String, Any>, lastSync: String?): Sequence<Map<String, Any?>> {
        val jdbcUrl = config["jdbcUrl"] as String
        val username = config["username"] as String
        val password = config["password"] as String // già decifrata dal chiamante
        val driverClassName = config["driverClassName"] as String
        val viewName = config["viewName"] as String

        Class.forName(driverClassName)

        val query = "SELECT * FROM $viewName WHERE $updatedAtAlias > ?"

        return sequence {
            java.sql.DriverManager.getConnection(jdbcUrl, username, password).use { conn ->
                conn.prepareStatement(query).use { stmt ->
                    stmt.fetchSize = 5000
                    stmt.setTimestamp(1, Timestamp.valueOf(lastSync ?: "1900-01-01 00:00:00"))
                    stmt.executeQuery().use { rs ->
                        val meta = rs.metaData

                        // getColumnLabel, non getColumnName.
                        //
                        // Su una colonna con alias (SELECT COD_CLI AS cod_cli)
                        // getColumnName può restituire il nome della colonna
                        // sottostante invece dell'alias, a seconda del driver:
                        // il comportamento non è garantito dallo standard JDBC.
                        // getColumnLabel restituisce sempre l'alias quando c'è.
                        // Dato che l'intero mapping dell'ETL si regge sugli
                        // alias della view, prendere il nome sbagliato
                        // significherebbe non trovare nessuna colonna e
                        // caricare una tabella di null.
                        //
                        // Il lowercase serve perché alcuni driver (SQL Server,
                        // Oracle) possono restituire l'etichetta in maiuscolo
                        // anche quando la view la dichiara minuscola: le chiavi
                        // devono combaciare con colonnaFisica, che è sempre
                        // normalizzata da Naming.
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
                        if (updatedAtAlias !in columnLabels) {
                            throw IllegalStateException(
                                "La view $viewName non espone la colonna $updatedAtAlias, necessaria per la sincronizzazione."
                            )
                        }

                        log.debug("Estrazione da {}: colonne {}", viewName, columnLabels)

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
}