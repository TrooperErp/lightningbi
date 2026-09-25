package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.AggregateResult
import com.lightningbi.lightning_engine.service.PivotEngine
import com.vaadin.flow.component.grid.HeaderRow
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.Scroller
import com.vaadin.flow.component.treegrid.TreeGrid
import com.vaadin.flow.data.provider.hierarchy.TreeData
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

/**
 * Griglia risultati (TreeGrid) dell'Analisi: disegna la gerarchia di
 * Righe già costruita da AggregateService.buildRowHierarchy, con header
 * a più livelli per le dimensioni in Colonne (es. Anno sopra Mese),
 * costruito con HeaderRow.join(). Nessuna logica di raggruppamento vive
 * qui: riceve la gerarchia già pronta e si limita a disegnarla.
 */
class ResultsGridUi {

    val resultsGrid = TreeGrid<PivotEngine.PivotNode>().apply {
        className = "lbi-results-grid"
        setWidthFull()
        height = "280px"
    }

    val root = Scroller(resultsGrid).apply {
        setWidthFull()
        height = "280px"
        style.set("flex-shrink", "0")
    }

    private val pivotHeaderRows = mutableListOf<HeaderRow>()

    private val itNumberFormat = NumberFormat.getNumberInstance(Locale.ITALY).apply {
        minimumFractionDigits = 2
        maximumFractionDigits = 2
    }

    fun render(
        result: AggregateResult,
        rowHierarchy: List<PivotEngine.PivotNode>,
        rows: List<UUID>,
        dimensionNames: Map<UUID, String>
    ) {
        resultsGrid.removeAllColumns()

        // removeAllColumns() non rimuove le header row aggiunte con
        // prependHeaderRow(): le rimuovo esplicitamente per riferimento,
        // evitando accumulo ad ogni render successivo.
        pivotHeaderRows.forEach { resultsGrid.removeHeaderRow(it) }
        pivotHeaderRows.clear()

        if (rowHierarchy.isEmpty()) {
            resultsGrid.setDataProvider(TreeDataProvider(TreeData()))
            return
        }

        val treeData = TreeData<PivotEngine.PivotNode>()
        addNodesRecursively(treeData, null, rowHierarchy)
        resultsGrid.setDataProvider(TreeDataProvider(treeData))

        val rowHeader = rows.mapNotNull { dimensionNames[it] }.joinToString(" / ").ifEmpty { "Righe" }
        val hierarchyColumn = resultsGrid.addHierarchyColumn { node -> node.label }
            .setHeader(rowHeader)
            .setWidth("220px")
            .setFlexGrow(0)

        val allValueKeysRaw = collectAllValueKeys(rowHierarchy)

        // Ordino esplicitamente per livello (dal più esterno Anno al più
        // interno Mese, poi nome metrica) PRIMA di creare le colonne:
        // l'algoritmo di raggruppamento header sotto assume che le
        // colonne con lo stesso valore di livello superiore siano
        // adiacenti, ma l'ordine naturale di collectAllValueKeys non lo
        // garantisce quando ci sono più metriche - può produrre gruppi
        // di una sola colonna e far fallire HeaderRow.join() con
        // "Cannot join less than 2 cells".
        val columnLevelsRaw = allValueKeysRaw.maxOfOrNull { it.split("|").size - 1 } ?: 0
        val allValueKeys = allValueKeysRaw.sortedWith(compareBy(
            *(1..columnLevelsRaw).map { level ->
                { key: String -> key.split("|").getOrNull(level) ?: "" }
            }.toTypedArray(),
            { key: String -> key.split("|").first() }
        ))

        val dataColumns = allValueKeys.map { key ->
            val parts = key.split("|")
            val headerText = if (parts.size > 1) parts.drop(1).joinToString(" · ") else key
            resultsGrid.addColumn { node -> formatMetricValue(node.values[key]) }
                .setHeader(headerText)
                .setTooltipGenerator { key.replace("|", " · ") }
                .setWidth("120px")
                .setFlexGrow(0)
        }

        // Un livello di header aggiuntivo per ogni dimensione in Colonne,
        // sopra il livello "nome metrica · valori" già impostato con
        // .setHeader() sulle colonne dati.
        val columnLevels = allValueKeys.maxOfOrNull { it.split("|").size - 1 } ?: 0

        if (columnLevels > 0) {
            for (level in (columnLevels - 1) downTo 0) {
                val levelValues = allValueKeys.map { it.split("|").getOrNull(level + 1) ?: "" }
                val headerRow = resultsGrid.prependHeaderRow()
                pivotHeaderRows.add(headerRow)

                var i = 0
                while (i < dataColumns.size) {
                    val value = levelValues[i]
                    var j = i
                    while (j + 1 < dataColumns.size) {
                        val partsJ = allValueKeys[j + 1].split("|")
                        val partsI = allValueKeys[i].split("|")
                        val samePrefix = (0 until level).all { l -> partsI.getOrNull(l + 1) == partsJ.getOrNull(l + 1) }
                        if (samePrefix && levelValues[j + 1] == value) j++ else break
                    }
                    val group = dataColumns.subList(i, j + 1).toTypedArray()
                    val cell = if (group.size > 1) headerRow.join(*group) else headerRow.getCell(group[0])
                    cell.text = value
                    i = j + 1
                }
            }
        }

        // Riordino esplicito DOPO aver creato le header row:
        // prependHeaderRow() può alterare l'ordine interno delle colonne,
        // spostando la colonna gerarchica lontano dalla prima posizione.
        resultsGrid.setColumnOrder(buildList {
            add(hierarchyColumn)
            addAll(dataColumns)
        })

        if (result.truncated) {
            val messaggio = if (rows.isEmpty())
                "Risultato troncato: troppe righe da mostrare"
            else
                "Troppe combinazioni da mostrare: prova a togliere una dimensione dalle Righe"
            Notification.show(messaggio, 5000, Notification.Position.BOTTOM_END)
        }
    }

    fun clearAll() {
        resultsGrid.setDataProvider(TreeDataProvider(TreeData()))
        resultsGrid.removeAllColumns()
        pivotHeaderRows.forEach { resultsGrid.removeHeaderRow(it) }
        pivotHeaderRows.clear()
    }

    private fun addNodesRecursively(
        treeData: TreeData<PivotEngine.PivotNode>,
        parent: PivotEngine.PivotNode?,
        nodes: List<PivotEngine.PivotNode>
    ) {
        treeData.addItems(parent, nodes)
        nodes.forEach { node ->
            if (node.children.isNotEmpty()) {
                addNodesRecursively(treeData, node, node.children)
            }
        }
    }

    private fun collectAllValueKeys(nodes: List<PivotEngine.PivotNode>): List<String> {
        val keys = LinkedHashSet<String>()
        fun visit(list: List<PivotEngine.PivotNode>) {
            list.forEach { node ->
                keys.addAll(node.values.keys)
                if (node.children.isNotEmpty()) visit(node.children)
            }
        }
        visit(nodes)
        return keys.toList()
    }

    private fun formatMetricValue(value: Any?): String = when (value) {
        null -> ""
        is Number -> itNumberFormat.format(value)
        is String -> value.toDoubleOrNull()?.let { itNumberFormat.format(it) } ?: value
        else -> value.toString()
    }
}