package com.lightningbi.lightning_engine.etl

import org.springframework.stereotype.Component
import java.sql.Timestamp

@Component
class JdbcExtractor : ExtractorPort {

    override fun extract(config: Map<String, Any>, lastSync: String?): Sequence<Map<String, Any?>> {
        val jdbcUrl = config["jdbcUrl"] as String
        val username = config["username"] as String
        val password = config["password"] as String // già decifrata dal chiamante
        val driverClassName = config["driverClassName"] as String
        val viewName = config["viewName"] as String

        Class.forName(driverClassName)

        val query = "SELECT * FROM $viewName WHERE lbi_updated_at > ?"

        return sequence {
            java.sql.DriverManager.getConnection(jdbcUrl, username, password).use { conn ->
                conn.prepareStatement(query).use { stmt ->
                    stmt.fetchSize = 5000
                    stmt.setTimestamp(1, Timestamp.valueOf(lastSync ?: "1900-01-01 00:00:00"))
                    stmt.executeQuery().use { rs ->
                        val meta = rs.metaData
                        while (rs.next()) {
                            val row = (1..meta.columnCount).associate {
                                meta.getColumnName(it) to rs.getObject(it)
                            }
                            yield(row)
                        }
                    }
                }
            }
        }
    }
}