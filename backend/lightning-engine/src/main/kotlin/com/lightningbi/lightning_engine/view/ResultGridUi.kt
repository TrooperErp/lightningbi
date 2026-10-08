package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.AggregateResult
import com.lightningbi.lightning_engine.service.DimensionSortOrders
import com.lightningbi.lightning_engine.service.PivotEngine
import com.vaadin.flow.component.grid.HeaderRow
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.Scroller
import com.vaadin.flow.component.treegrid.TreeGrid
import com.vaadin.flow.data.provider.hierarchy.TreeData
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import java.util.UUID

/**
 * Griglia risultati (TreeGrid) dell'Analisi: disegna la gerarchia di
 * Righe già costruita da AggregateService.buildRowHierarchy, con header
 * a più livelli per le dimensioni in Colonne (es. Anno sopra Mese),
 * costruito con HeaderRow.join().
 *
 * Le colonne seguono l'ordine deciso dal motore (AggregateResult.colonne: numeri per valore,
 * testo per etichetta). Con delle Colonne, ogni colonna tranne la prima di un gruppo è seguita
 * da una colonna "Var. %": la variazione rispetto alla colonna precedente dello stesso gruppo
 * (stessa misura, stessi livelli esterni), calcolata su ogni riga, anche sui totali.
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

    /** Una colonna: i valori di [chiave], oppure (se [precedente] c'è) la variazione % di [chiave] rispetto a [precedente]. */
    private class Colonna(val chiave: String, val precedente: String?, val parti: List<String>)

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
        // Una chiave è "misura|valore1|valore2...". Le variazioni non arrivano dal motore: si calcolano qui.
        val chiaviGrezze = collectAllValueKeys(rowHierarchy).filter { !it.endsWith("|$VARIAZIONE") }

        fun parti(chiave: String): List<String> = chiave.split("|")
        fun suffisso(chiave: String): String = parti(chiave).drop(1).joinToString("|")

        val livelli = chiaviGrezze.maxOfOrNull { parti(it).size - 1 } ?: 0
        val misure = chiaviGrezze.map { parti(it)[0] }.distinct()
        val unaMisura = misure.size == 1

        // Ordine: quello del motore (mesi Gen…Dic); se manca, confronto naturale delle etichette.
        // A parità di colonna, nell'ordine delle misure. I gruppi con lo stesso livello superiore
        // restano adiacenti, come richiede HeaderRow.join().
        val rango = result.colonne.withIndex().associate { (i, c) -> c to i }
        val confronto = Comparator<String> { a, b ->
            val c = (rango[suffisso(a)] ?: Int.MAX_VALUE).compareTo(rango[suffisso(b)] ?: Int.MAX_VALUE)
            if (c != 0) return@Comparator c
            val d = DimensionSortOrders.confrontoNaturale(suffisso(a), suffisso(b))
            if (d != 0) return@Comparator d
            misure.indexOf(parti(a)[0]).compareTo(misure.indexOf(parti(b)[0]))
        }
        val chiavi = chiaviGrezze.sortedWith(confronto)

        // ---- elenco colonne, con le variazioni ----
        val definizioni = mutableListOf<Colonna>()
        val ultimaDelGruppo = mutableMapOf<String, String>()
        var i = 0
        while (i < chiavi.size) {
            val suff = suffisso(chiavi[i])
            var j = i
            while (j < chiavi.size && suffisso(chiavi[j]) == suff) j++
            val blocco = chiavi.subList(i, j)
            blocco.forEach { definizioni += Colonna(it, null, parti(it)) }
            if (livelli > 0) {
                blocco.forEach { k ->
                    val p = parti(k)
                    val gruppo = p[0] + "|" + p.drop(1).dropLast(1).joinToString("|")
                    val precedente = ultimaDelGruppo[gruppo]
                    if (precedente != null) definizioni += Colonna(k, precedente, p.dropLast(1) + ETICHETTA_VARIAZIONE)
                    ultimaDelGruppo[gruppo] = k
                }
            }
            i = j
        }

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
        val colonne = definizioni.map { def ->
            val p = def.parti
            val titoloUltimaRiga = when {
                livelli == 0 -> def.chiave
                unaMisura -> formatta(p.last())
                else -> p[0]
            }
            val precedente = def.precedente
            resultsGrid.addColumn { node ->
                if (precedente == null) formatMetricValue(node.values[def.chiave])
                else formatVariazione(node.values[precedente], node.values[def.chiave])
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
            val valoriLivello = definizioni.map { it.parti.getOrNull(livello) ?: "" }
            val riga = resultsGrid.prependHeaderRow()
            pivotHeaderRows.add(riga)

            var a = 0
            while (a < colonne.size) {
                val valore = valoriLivello[a]
                var b = a
                while (b + 1 < colonne.size) {
                    val pa = definizioni[a].parti
                    val pb = definizioni[b + 1].parti
                    val stessoPrefisso = (1 until livello).all { l -> pa.getOrNull(l) == pb.getOrNull(l) }
                    if (stessoPrefisso && valoriLivello[b + 1] == valore) b++ else break
                }
                val gruppo = colonne.subList(a, b + 1).toTypedArray()
                val cella = if (gruppo.size > 1) riga.join(*gruppo) else riga.getCell(gruppo[0])
                cella.text = formatta(valore)
                a = b + 1
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
        /** Ultimo pezzo della chiave di una variazione generata dal motore: qui si ignora. */
        const val VARIAZIONE = "Variaz.%"
        const val ETICHETTA_VARIAZIONE = "Var. %"
    }

    /** (corrente - precedente) / |precedente| in percentuale; vuoto se manca un valore o il precedente è zero. */
    private fun formatVariazione(precedente: BigDecimal?, corrente: BigDecimal?): String {
        if (precedente == null || corrente == null || precedente.signum() == 0) return ""
        val v = corrente.subtract(precedente)
            .divide(precedente.abs(), 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
        return itNumberFormat.format(v) + " %"
    }

    private fun formatMetricValue(value: Any?): String = when (value) {
        null -> ""
        is Number -> itNumberFormat.format(value)
        is String -> value.toDoubleOrNull()?.let { itNumberFormat.format(it) } ?: value
        else -> value.toString()
    }
}