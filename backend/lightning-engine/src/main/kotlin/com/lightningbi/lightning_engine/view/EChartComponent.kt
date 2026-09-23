package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.ChartData
import com.lightningbi.lightning_engine.model.ChartType
import com.vaadin.flow.component.Tag
import com.vaadin.flow.component.html.Div
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Un singolo grafico ECharts, disegnato dentro un div dedicato.
 *
 * ECharts richiede un elemento DOM proprio per istanza (echarts.init(dom)):
 * non si può condividere un contenitore tra più grafici. Ogni istanza di
 * questa classe possiede il proprio div con id univoco, generato una
 * volta alla creazione.
 *
 * I dati (etichette, serie) arrivano già pronti da ChartService - questa
 * classe traduce ChartData nella struttura option di ECharts e la passa
 * al browser via JSON, non fa alcun calcolo.
 */
@Tag("div")
class EChartComponent : Div() {

    private val chartId = "echart-${UUID.randomUUID().toString().replace("-", "")}"
    private val objectMapper = ObjectMapper()

    init {
        element.setAttribute("id", chartId)
        style.set("width", "720px")
        style.set("height", "450px")
    }

    /**
     * Disegna o aggiorna il grafico con i dati forniti. Se il grafico
     * esiste già su questo div (richiamato più volte, es. dopo un
     * ricalcolo), echarts.init lo ridispone invece di crearne uno nuovo -
     * comportamento nativo di ECharts quando richiamato sullo stesso dom.
     */
    fun render(chartData: ChartData) {
        val option = buildOption(chartData)
        val optionJson = objectMapper.writeValueAsString(option)

        // executeJs gira nel browser: init prende l'elemento con l'id
        // generato, setOption disegna. notMerge=true evita che opzioni
        // di una render precedente restino "appiccicate" a una nuova
        // configurazione con meno serie o etichette diverse.
        element.executeJs(
            """
    const el = document.getElementById(${'$'}0);
    const opt = JSON.parse(${'$'}1);
    let attempts = 0;
    function tryRender() {
        attempts++;
        if (typeof echarts === 'undefined') {
            if (attempts < 60) {
                setTimeout(tryRender, 50);
            }
            return;
        }
        if (el) {
            let chart = echarts.getInstanceByDom(el);
            if (!chart) {
                chart = echarts.init(el);
            }
            chart.setOption(opt, true);
        }
    }
    tryRender();
    """.trimIndent(),
            chartId, optionJson
        )

    }

    /**
     * Traduce ChartData + tipo nel formato "option" di ECharts.
     * Ogni ChartType ha una struttura leggermente diversa: PIE/DONUT
     * usano una sola serie di tipo "pie" con i dati come coppie
     * nome/valore, gli altri usano xAxis/yAxis con una serie per metrica.
     */
    private fun buildOption(chartData: ChartData): Map<String, Any?> {
        val tipo = chartData.chart.tipo
        val palette = listOf("#4E79A7", "#F28E2B", "#E15759", "#76B7B2", "#59A14F", "#EDC948", "#B07AA1", "#FF9DA7", "#9C755F", "#BAB0AC")

        return when (tipo) {
            ChartType.PIE, ChartType.DONUT -> {
                val serie = chartData.series.firstOrNull()
                val data = chartData.labels.mapIndexed { i, label ->
                    mapOf("name" to label, "value" to (serie?.values?.getOrNull(i) ?: 0.0))
                }
                mapOf(
                    "color" to palette,
                    "tooltip" to mapOf("trigger" to "item"),
                    "legend" to mapOf("bottom" to 0),
                    "series" to listOf(
                        mapOf(
                            "type" to "pie",
                            "radius" to if (tipo == ChartType.DONUT) listOf("40%", "70%") else "70%",
                            "data" to data
                        )
                    )
                )
            }

            ChartType.RADAR -> {
                val indicators = chartData.series.map { mapOf("name" to it.metricaNome, "max" to (it.values.maxOrNull() ?: 100.0)) }
                val values = chartData.series.map { it.values.firstOrNull() ?: 0.0 }
                mapOf(
                    "color" to palette,
                    "tooltip" to mapOf("trigger" to "item"),
                    "radar" to mapOf("indicator" to indicators),
                    "series" to listOf(
                        mapOf(
                            "type" to "radar",
                            "data" to listOf(mapOf("value" to values, "name" to chartData.labels.firstOrNull().orEmpty()))
                        )
                    )
                )
            }

            ChartType.SCATTER -> {
                val xValues = chartData.series.getOrNull(0)?.values ?: emptyList()
                val yValues = chartData.series.getOrNull(1)?.values ?: emptyList()
                val points = xValues.indices.map { i -> listOf(xValues[i], yValues.getOrNull(i) ?: 0.0) }
                mapOf(
                    "color" to palette,
                    "tooltip" to mapOf("trigger" to "item"),
                    "xAxis" to mapOf("type" to "value", "name" to (chartData.series.getOrNull(0)?.metricaNome ?: "")),
                    "yAxis" to mapOf("type" to "value", "name" to (chartData.series.getOrNull(1)?.metricaNome ?: "")),
                    "series" to listOf(mapOf("type" to "scatter", "data" to points, "symbolSize" to 10))
                )
            }

            ChartType.MAP -> {
                // La mappa richiede un GeoJSON registrato lato client
                // (echarts.registerMap) che oggi non è caricato: finché
                // non lo aggiungiamo, mostriamo un messaggio invece di
                // un grafico vuoto o rotto.
                mapOf(
                    "title" to mapOf(
                        "text" to "Cartina non ancora disponibile: richiede un file geografico non caricato",
                        "left" to "center", "top" to "middle",
                        "textStyle" to mapOf("fontSize" to 13, "fontWeight" to "normal")
                    )
                )
            }

            else -> {
                // BAR, BAR_HORIZONTAL, LINE, AREA: stesso schema, cambia
                // solo l'orientamento degli assi e il tipo di serie.
                val isHorizontal = tipo == ChartType.BAR_HORIZONTAL
                val categoryAxis = mapOf("type" to "category", "data" to chartData.labels)
                val valueAxis = mapOf("type" to "value")

                val seriesType = when (tipo) {
                    ChartType.LINE -> "line"
                    ChartType.AREA -> "line"
                    else -> "bar"
                }
                val areaStyle = if (tipo == ChartType.AREA) mapOf("opacity" to 0.35) else null

                val series = chartData.series.mapIndexed { i, s ->
                    val base = mutableMapOf<String, Any?>(
                        "name" to s.metricaNome,
                        "type" to seriesType,
                        "data" to s.values
                    )
                    if (areaStyle != null) base["areaStyle"] = areaStyle

                    val uniqueColors = s.pointColors?.filterNotNull()?.distinct()
                    when {
                        // Tutti i punti della serie hanno lo stesso colore:
                        // lo si imposta a livello di SERIE (itemStyle sulla
                        // serie, non sui singoli punti), cosi' la legenda
                        // di ECharts - che legge solo il colore di serie,
                        // mai quello dei punti - mostra il colore giusto.
                        uniqueColors != null && uniqueColors.size == 1 -> {
                            base["itemStyle"] = mapOf("color" to uniqueColors.first())
                        }
                        // Colori misti nella stessa serie (caso 2 colonne
                        // con highlightDecline: alcuni punti rossi, altri
                        // blu dentro la serie "corrente"): colore per
                        // singolo punto/barra. La legenda in questo caso
                        // specifico mostra un colore che non rispecchia
                        // ogni singola barra - limite noto di ECharts,
                        // accettabile perché il dato visivo sulle barre
                        // resta corretto.
                        s.pointColors != null -> {
                            base["data"] = s.values.mapIndexed { idx, value ->
                                val color = s.pointColors.getOrNull(idx)
                                if (color != null) {
                                    mapOf("value" to value, "itemStyle" to mapOf("color" to color))
                                } else {
                                    value
                                }
                            }
                        }
                    }

                    base
                }

                mapOf(
                    "color" to palette,
                    "tooltip" to mapOf("trigger" to "axis"),
                    "legend" to if (series.size > 1) mapOf("bottom" to 0) else null,
                    "grid" to mapOf("containLabel" to true, "left" to 10, "right" to 20, "top" to 30, "bottom" to if (series.size > 1) 40 else 20),
                    "xAxis" to if (isHorizontal) valueAxis else categoryAxis,
                    "yAxis" to if (isHorizontal) categoryAxis else valueAxis,
                    "series" to series
                )
            }
        }
    }
}