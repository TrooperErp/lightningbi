package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.AggregateOrder
import com.lightningbi.lightning_engine.model.AreaChart
import com.lightningbi.lightning_engine.model.ChartType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import org.springframework.beans.factory.annotation.Qualifier

interface AreaChartRepository {
    fun findByArea(areaId: UUID): List<AreaChart>
    fun findById(id: UUID): AreaChart?
    fun save(chart: AreaChart): AreaChart
    fun update(chart: AreaChart): AreaChart
    fun delete(id: UUID): Boolean
    fun deleteByArea(areaId: UUID): Int
    /** Prossima posizione libera nella dashboard di un'area. */
    fun nextPosizione(areaId: UUID): Int
}



@Repository
class AreaChartRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : AreaChartRepository {

    private val mapper = RowMapper { rs: ResultSet, _: Int ->
        AreaChart(
            id = rs.getObject("id", UUID::class.java),
            areaId = rs.getObject("area_id", UUID::class.java),
            titolo = rs.getString("titolo"),
            tipo = ChartType.valueOf(rs.getString("tipo")),
            groupByDimId = rs.getObject("group_by_dim_id", UUID::class.java),
            metricaId = rs.getObject("metrica_id", UUID::class.java),
            orderBy = AggregateOrder.valueOf(rs.getString("order_by")),
            // getObject e non getInt: su NULL getInt restituirebbe 0,
            // che significherebbe "nessuna riga" invece di "nessun limite".
            maxItems = rs.getObject("max_items") as? Int,
            posizione = rs.getInt("posizione"),
            createdAt = rs.getTimestamp("created_at").toInstant()
        )
    }

    override fun findByArea(areaId: UUID): List<AreaChart> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_area_chart WHERE area_id = ? ORDER BY posizione, created_at",
            mapper, areaId
        )

    override fun findById(id: UUID): AreaChart? =
        jdbcTemplate.query("SELECT * FROM lbi_area_chart WHERE id = ?", mapper, id).firstOrNull()

    override fun save(chart: AreaChart): AreaChart {
        jdbcTemplate.update(
            """
            INSERT INTO lbi_area_chart
                (id, area_id, titolo, tipo, group_by_dim_id, metrica_id,
                 order_by, max_items, posizione, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            chart.id, chart.areaId, chart.titolo, chart.tipo.name,
            chart.groupByDimId, chart.metricaId, chart.orderBy.name,
            chart.maxItems, chart.posizione, Timestamp.from(chart.createdAt)
        )
        return chart
    }

    override fun update(chart: AreaChart): AreaChart {
        jdbcTemplate.update(
            """
            UPDATE lbi_area_chart
               SET titolo = ?, tipo = ?, group_by_dim_id = ?, metrica_id = ?,
                   order_by = ?, max_items = ?, posizione = ?
             WHERE id = ?
            """.trimIndent(),
            chart.titolo, chart.tipo.name, chart.groupByDimId, chart.metricaId,
            chart.orderBy.name, chart.maxItems, chart.posizione, chart.id
        )
        return chart
    }

    override fun delete(id: UUID): Boolean =
        jdbcTemplate.update("DELETE FROM lbi_area_chart WHERE id = ?", id) > 0

    /**
     * Senza FK con CASCADE, la pulizia dei grafici alla cancellazione di
     * un'Analisi va fatta esplicitamente dal service.
     */
    override fun deleteByArea(areaId: UUID): Int =
        jdbcTemplate.update("DELETE FROM lbi_area_chart WHERE area_id = ?", areaId)

    override fun nextPosizione(areaId: UUID): Int {
        val max = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(posizione), -1) FROM lbi_area_chart WHERE area_id = ?",
            Int::class.java, areaId
        ) ?: -1
        return max + 1
    }
}