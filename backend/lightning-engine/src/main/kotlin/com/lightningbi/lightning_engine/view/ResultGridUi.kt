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
import com.lightningbi.lightning_engine.service.DimensionSortOrders
/**
 * Griglia risultati (TreeGrid) dell'Analisi: disegna la gerarchia di
 * Righe già costruita da AggregateService.buildRowHierarchy, con header
 * a più livelli per le dimensioni in Colonne (es. Anno sopra Mese),
 * costruito con HeaderRow.join(). Nessuna logica di raggruppamento vive
 * qui: riceve la gerarchia già pronta e si limita a disegnarla.
 */
class ResultsGridUi(
    /** Formatta le etichette per mostrarle (le date da 2025-01-16 a 16/01/2025); il motore ordina sul valore grezzo. */
    private val formatta: (String) -> String = { it }
) {

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

        // ---- chiavi delle colonne ----
        // Una chiave è "misura|valore1|valore2...". La variazione percentuale è
        // "misura|...|2025→2026|Variaz.%": qui diventa una colonna IN CODA al suo gruppo, con
        // lo stesso numero di livelli delle altre e ultimo livello "Var. %".
        val chiaviGrezze = collectAllValueKeys(rowHierarchy)

        fun parti(chiave: String): List<String> {
            val p = chiave.split("|")
            return if (p.size >= 3 && p.last() == VARIAZIONE) p.dropLast(2) + ETICHETTA_VARIAZIONE else p
        }

        val livelli = chiaviGrezze.maxOfOrNull { parti(it).size - 1 } ?: 0
        val misure = chiaviGrezze.map { parti(it)[0] }.distinct()
        val unaMisura = misure.size == 1

        // Ordine: per livello dal più esterno al più interno; la variazione sempre dopo i valori
        // del suo gruppo; poi per nome di misura. I gruppi di colonne con lo stesso valore di
        // livello superiore devono essere adiacenti, altrimenti HeaderRow.join() fallisce.
        fun confronta(a: String, b: String): Int = when {
            a == ETICHETTA_VARIAZIONE && b == ETICHETTA_VARIAZIONE -> 0
            a == ETICHETTA_VARIAZIONE -> 1
            b == ETICHETTA_VARIAZIONE -> -1
            else -> DimensionSortOrders.confrontoNaturale(a, b)
        }
        val confrontoHeader = Comparator<String> { ka, kb ->
            val pa = parti(ka)
            val pb = parti(kb)
            for (livello in 1..livelli) {
                val c = confronta(pa.getOrNull(livello) ?: "", pb.getOrNull(livello) ?: "")
                if (c != 0) return@Comparator c
            }
            DimensionSortOrders.confrontoNaturale(pa[0], pb[0])
        }
        val chiavi = chiaviGrezze.sortedWith(confrontoHeader)

        // ---- colonna delle righe ----
        // Con una sola misura e delle colonne, il suo nome non compare sopra ogni colonna: sta qui.
        val rowHeader = rows.mapNotNull { dimensionNames[it] }.joinToString(" / ").ifEmpty { "Righe" }
        val intestazioneRighe = if (livelli > 0 && unaMisura) "$rowHeader · ${misure.first()}" else rowHeader
        val hierarchyColumn = resultsGrid.addHierarchyColumn { node -> formatta(node.label) }
            .setHeader(intestazioneRighe)
            .setWidth("220px")
            .setFlexGrow(0)

        // ---- colonne dei valori ----
        // Il titolo dell'ultima riga è il valore più interno (una sola misura) oppure il nome della misura.
        val colonne = chiavi.map { chiave ->
            val p = parti(chiave)
            val titoloUltimaRiga = when {
                livelli == 0 -> chiave
                unaMisura -> formatta(p.last())
                else -> p[0]
            }
            val variazione = chiave.endsWith("|$VARIAZIONE")
            resultsGrid.addColumn { node ->
                val valore = formatMetricValue(node.values[chiave])
                if (variazione && valore.isNotEmpty()) "$valore %" else valore
            }
                .setHeader(titoloUltimaRiga)
                .setTooltipGenerator { p.joinToString(" · ") { formatta(it) } }
                .setWidth("120px")
                .setFlexGrow(0)
        }

        // Una riga di titolo in più per ogni livello di Colonne che non sta già nell'ultima riga.
        val righeIntestazione = when {
            livelli == 0 -> 0
            unaMisura -> livelli - 1
            else -> livelli
        }
        for (livello in righeIntestazione downTo 1) {
            val valoriLivello = chiavi.map { parti(it).getOrNull(livello) ?: "" }
            val riga = resultsGrid.prependHeaderRow()
            pivotHeaderRows.add(riga)

            var i = 0
            while (i < colonne.size) {
                val valore = valoriLivello[i]
                var j = i
                while (j + 1 < colonne.size) {
                    val pi = parti(chiavi[i])
                    val pj = parti(chiavi[j + 1])
                    val stessoPrefisso = (1 until livello).all { l -> pi.getOrNull(l) == pj.getOrNull(l) }
                    if (stessoPrefisso && valoriLivello[j + 1] == valore) j++ else break
                }
                val gruppo = colonne.subList(i, j + 1).toTypedArray()
                val cella = if (gruppo.size > 1) riga.join(*gruppo) else riga.getCell(gruppo[0])
                cella.text = formatta(valore)
                i = j + 1
            }
        }

        // Riordino esplicito DOPO aver creato le header row:
        // prependHeaderRow() può alterare l'ordine interno delle colonne,
        // spostando la colonna gerarchica lontano dalla prima posizione.
        resultsGrid.setColumnOrder(buildList {
            add(hierarchyColumn)
            addAll(colonne)
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

    private companion object {
        /** Ultimo pezzo della chiave di una colonna di variazione percentuale, come lo scrive AggregateService. */
        const val VARIAZIONE = "Variaz.%"
        const val ETICHETTA_VARIAZIONE = "Var. %"
    }

    private fun formatMetricValue(value: Any?): String = when (value) {
        null -> ""
        is Number -> itNumberFormat.format(value)
        is String -> value.toDoubleOrNull()?.let { itNumberFormat.format(it) } ?: value
        else -> value.toString()
    }
}