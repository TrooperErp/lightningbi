package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.AggregateOrder
import com.lightningbi.lightning_engine.model.AreaChart
import com.lightningbi.lightning_engine.model.AreaChartMetrica
import com.lightningbi.lightning_engine.model.ChartType
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

interface AreaChartRepository {
    fun findByArea(areaId: UUID): List<AreaChart>
    fun findById(id: UUID): AreaChart?
    fun save(chart: AreaChart): AreaChart
    fun update(chart: AreaChart): AreaChart
    fun delete(id: UUID): Boolean
    fun deleteByArea(areaId: UUID): Int
    fun nextPosizione(areaId: UUID): Int

    /** Metriche di un grafico, ordinate per posizione (prima serie, seconda serie...). */
    fun findMetricheByChart(chartId: UUID): List<AreaChartMetrica>

    /**
     * Sostituisce interamente l'elenco di metriche di un grafico.
     * Più semplice di un merge riga-per-riga: un grafico ha poche metriche
     * (tipicamente 1-3), riscriverle tutte ad ogni modifica costa poco ed
     * evita la complessità di calcolare un diff.
     */
    fun replaceMetriche(chartId: UUID, metriche: List<AreaChartMetrica>)
}

@Repository
class AreaChartRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : AreaChartRepository {

    private val mapper = RowMapper { rs: ResultSet, _: Int ->
        AreaChart(
            id = UUID.fromString(rs.getString("id")),
            areaId = UUID.fromString(rs.getString("area_id")),
            titolo = rs.getString("titolo"),
            tipo = ChartType.valueOf(rs.getString("tipo")),
            orderBy = AggregateOrder.valueOf(rs.getString("order_by")),
            maxItems = rs.getObject("max_items") as? Int,
            posizione = rs.getInt("posizione"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            followsColumns = rs.getBoolean("follows_columns"),
            highlightDecline = rs.getBoolean("highlight_decline")
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
            (id, area_id, titolo, tipo, order_by, max_items, posizione, created_at, follows_columns, highlight_decline)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
            chart.id, chart.areaId, chart.titolo, chart.tipo.name,
            chart.orderBy.name, chart.maxItems, chart.posizione,
            java.sql.Timestamp.from(chart.createdAt),
            chart.followsColumns, chart.highlightDecline
        )
        return chart
    }

    override fun update(chart: AreaChart): AreaChart {
        jdbcTemplate.update(
            """
        UPDATE lbi_area_chart
           SET titolo = ?, tipo = ?, order_by = ?, max_items = ?, posizione = ?, follows_columns = ?, highlight_decline = ?
         WHERE id = ?
        """.trimIndent(),
            chart.titolo, chart.tipo.name, chart.orderBy.name,
            chart.maxItems, chart.posizione, chart.followsColumns, chart.highlightDecline, chart.id
        )
        return chart
    }

    override fun delete(id: UUID): Boolean {
        // Le righe di giunzione non hanno FK con CASCADE (coerente col
        // resto del registry, che non ne usa): vanno rimosse a mano prima
        // di cancellare il grafico, altrimenti restano orfane.
        jdbcTemplate.update("DELETE FROM lbi_area_chart_metrica WHERE chart_id = ?", id)
        return jdbcTemplate.update("DELETE FROM lbi_area_chart WHERE id = ?", id) > 0
    }

    override fun deleteByArea(areaId: UUID): Int {
        val chartIds = jdbcTemplate.query(
            "SELECT id FROM lbi_area_chart WHERE area_id = ?",
            { rs, _ -> UUID.fromString(rs.getString("id")) },
            areaId
        )
        chartIds.forEach { chartId ->
            jdbcTemplate.update("DELETE FROM lbi_area_chart_metrica WHERE chart_id = ?", chartId)
        }
        return jdbcTemplate.update("DELETE FROM lbi_area_chart WHERE area_id = ?", areaId)
    }

    override fun nextPosizione(areaId: UUID): Int {
        val max = jdbcTemplate.queryForObject(
            "SELECT COALESCE(MAX(posizione), -1) FROM lbi_area_chart WHERE area_id = ?",
            Int::class.java, areaId
        ) ?: -1
        return max + 1
    }

    override fun findMetricheByChart(chartId: UUID): List<AreaChartMetrica> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_area_chart_metrica WHERE chart_id = ? ORDER BY posizione",
            { rs, _ -> AreaChartMetrica(
                chartId = UUID.fromString(rs.getString("chart_id")),
                metricaId = UUID.fromString(rs.getString("metrica_id")),
                posizione = rs.getInt("posizione")
            ) },
            chartId
        )

    @Transactional("postgresTransactionManager")
    override fun replaceMetriche(chartId: UUID, metriche: List<AreaChartMetrica>) {
        jdbcTemplate.update("DELETE FROM lbi_area_chart_metrica WHERE chart_id = ?", chartId)
        metriche.forEach { m ->
            jdbcTemplate.update(
                "INSERT INTO lbi_area_chart_metrica (chart_id, metrica_id, posizione) VALUES (?, ?, ?)",
                m.chartId, m.metricaId, m.posizione
            )
        }
    }
}