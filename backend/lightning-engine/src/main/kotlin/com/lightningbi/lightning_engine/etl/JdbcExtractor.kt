package com.lightningbi.lightning_engine.etl

import org.springframework.stereotype.Component
import javax.sql.DataSource
import java.sql.Timestamp

@Component
class JdbcExtractor(
    private val sourceDataSource: DataSource
) : ExtractorPort {

    override fun extract(config: Map<String, Any>, lastSync: String?): Sequence<Map<String, Any?>> {
        val query = config["query"] as String // contiene un solo '?' per lastSync

        return sequence {
            sourceDataSource.connection.use { conn ->
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