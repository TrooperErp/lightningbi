// FILE: src/main/kotlin/com/lightningbi/lightning_engine/view/EChartComponent.kt
package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.ChartData
import com.lightningbi.lightning_engine.model.ChartType
import com.vaadin.flow.component.ClientCallable
import com.vaadin.flow.component.Tag
import com.vaadin.flow.component.html.Div
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/**
 * Un singolo grafico ECharts, disegnato dentro un div dedicato.
 *
 * ECharts richiede un elemento DOM proprio per istanza (echarts.init(dom)):
 * ogni istanza di questa classe possiede il proprio div con id univoco.
 *
 * I dati arrivano già pronti da ChartService: questa classe traduce ChartData
 * nella struttura option di ECharts e la passa al browser via JSON.
 *
 * CLIC, come in Qlik: il clic seleziona, non nasconde.
 *  - barra, punto o fetta -> i valori delle Righe di quel punto;
 *  - voce della legenda -> il valore di colonna della serie (grafico che segue
 *    le Colonne) o, nella torta, la fetta.
 * La legenda non nasconde più le serie: è una selezione, non un filtro locale.
 * Radar, dispersione e cartina non sono cliccabili (i loro punti non sono un valore delle Righe).
 *
 * @param alClic riceve gli id da selezionare (dimensione -> valore)
 */
@Tag("div")
class EChartComponent(
    private val alClic: (Map<UUID, Long>) -> Unit = {}
) : Div() {

    private val chartId = "echart-${UUID.randomUUID().toString().replace("-", "")}"
    private val objectMapper = ObjectMapper()
    private val log = org.slf4j.LoggerFactory.getLogger(EChartComponent::class.java)

    /** I dati dell'ultimo disegno: servono a tradurre il clic in id. */
    private var dati: ChartData? = null

    init {
        element.setAttribute("id", chartId)
        style.set("width", "720px")
        style.set("height", "450px")
    }

    /** Clic su un punto (barra, fetta, punto della linea): seleziona i valori delle Righe di quell'indice. */
    @ClientCallable
    fun clicPunto(indice: Int) {
        log.info("[grafico] clic punto {}: chiavi {} (punti con chiavi: {})", indice, dati?.chiaviRighe?.getOrNull(indice), dati?.chiaviRighe?.size)
        val chiavi = dati?.chiaviRighe?.getOrNull(indice) ?: return
        if (chiavi.isNotEmpty()) alClic(chiavi)
    }

    /** Clic su una voce della legenda: la serie (valore di colonna) o, nella torta, la fetta con quel nome. */
    @ClientCallable
    fun clicLegenda(nome: String) {
        log.info("[grafico] clic legenda '{}': serie {}", nome, dati?.series?.map { it.metricaNome to it.chiavi })
        val d = dati ?: return
        d.series.firstOrNull { it.metricaNome == nome }?.chiavi?.let {
            if (it.isNotEmpty()) alClic(it)
            return
        }
        val indice = d.labels.indexOf(nome)
        if (indice >= 0) clicPunto(indice)
    }

    /**
     * Disegna o aggiorna il grafico con i dati forniti. Se il grafico esiste già
     * su questo div, echarts lo riusa invece di crearne uno nuovo.
     */
    fun render(chartData: ChartData) {
        dati = chartData
        val option = buildOption(chartData)
        val optionJson = objectMapper.writeValueAsString(option)
        val cliccabile = chartData.chart.tipo !in setOf(ChartType.RADAR, ChartType.SCATTER, ChartType.MAP)

        element.executeJs(
            """
    let attempts = 0;
    function mostra(testo) {
        const el = document.getElementById(${'$'}0);
        if (el) {
            el.textContent = testo;
            el.style.padding = '12px';
            el.style.color = '#b00020';
        }
    }
    function tryRender() {
        attempts++;
        const el = document.getElementById(${'$'}0);
        if (typeof echarts === 'undefined' || !el) {
            if (attempts < 60) {
                setTimeout(tryRender, 50);
            } else if (!el) {
                mostra('Riquadro del grafico non trovato nella pagina');
            } else {
                mostra('Libreria dei grafici non caricata (js/echarts.min.js)');
            }
            return;
        }
        try {
            let chart = echarts.getInstanceByDom(el);
            if (!chart) {
                chart = echarts.init(el);
            }
            chart.setOption(JSON.parse(${'$'}1), true);
            // Il clic seleziona (come in Qlik). Si tolgono i gestori di un disegno precedente.
            chart.off('click');
            chart.off('legendselectchanged');
            if (${'$'}2) {
                chart.on('click', function (p) {
                    if (p.componentType === 'series' && p.dataIndex != null) el.${'$'}server.clicPunto(p.dataIndex);
                });
                chart.on('legendselectchanged', function (p) {
                    // La legenda non nasconde: si rimostra tutto e si seleziona.
                    chart.dispatchAction({ type: 'legendAllSelect' });
                    el.${'$'}server.clicLegenda(p.name);
                });
            }
        } catch (e) {
            mostra('Errore nel disegno del grafico: ' + e.message);
        }
    }
    tryRender();
    """.trimIndent(),
            chartId, optionJson, cliccabile
        )
    }

    /**
     * Traduce ChartData + tipo nel formato "option" di ECharts.
     * PIE/DONUT usano una sola serie "pie" con coppie nome/valore, gli altri
     * xAxis/yAxis con una serie per metrica (o per valore di colonna).
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
                // La mappa richiede un GeoJSON registrato lato client (echarts.registerMap), oggi non caricato.
                mapOf(
                    "title" to mapOf(
                        "text" to "Cartina non ancora disponibile: richiede un file geografico non caricato",
                        "left" to "center", "top" to "middle",
                        "textStyle" to mapOf("fontSize" to 13, "fontWeight" to "normal")
                    )
                )
            }

            else -> {
                // BAR, BAR_HORIZONTAL, LINE, AREA: stesso schema, cambia l'orientamento e il tipo di serie.
                val isHorizontal = tipo == ChartType.BAR_HORIZONTAL
                val categoryAxis = mapOf("type" to "category", "data" to chartData.labels)
                val valueAxis = mapOf("type" to "value")

                val seriesType = when (tipo) {
                    ChartType.LINE -> "line"
                    ChartType.AREA -> "line"
                    else -> "bar"
                }
                val areaStyle = if (tipo == ChartType.AREA) mapOf("opacity" to 0.35) else null

                val series = chartData.series.map { s ->
                    val base = mutableMapOf<String, Any?>(
                        "name" to s.metricaNome,
                        "type" to seriesType,
                        "data" to s.values
                    )
                    if (areaStyle != null) base["areaStyle"] = areaStyle

                    val uniqueColors = s.pointColors?.filterNotNull()?.distinct()
                    when {
                        // Un solo colore per tutta la serie: sulla serie, così la legenda lo mostra giusto.
                        uniqueColors != null && uniqueColors.size == 1 -> {
                            base["itemStyle"] = mapOf("color" to uniqueColors.first())
                        }
                        // Colori misti (cali in rosso): colore per singolo punto.
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