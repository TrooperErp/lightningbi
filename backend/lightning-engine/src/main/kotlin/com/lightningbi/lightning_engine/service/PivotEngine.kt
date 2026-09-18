package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.AggregateRow
import com.lightningbi.lightning_engine.model.AreaMetrica
import java.math.BigDecimal
import java.util.UUID
import com.lightningbi.lightning_engine.model.TipoAggregazione

/**
 * Motore di raggruppamento gerarchico generico, condiviso fra l'asse
 * Righe (destinato a TreeGrid, nodo Agente > nodo Mese) e l'asse Colonne
 * (intestazioni annidate "Anno > Trimestre" già usate in
 * AggregateService.pivotByColumns).
 *
 * Stesso principio del motore associativo di Qlik: nessuna logica
 * specifica per "Agente" o "Anno", un solo algoritmo ricorsivo che
 * funziona per qualunque lista di dimensioni, in qualunque ordine, a
 * qualunque profondità - un'area pilota con 3 dimensioni e una futura
 * area con 5 usano lo stesso codice, zero casi speciali.
 */
object PivotEngine {

    /**
     * Un nodo della gerarchia. Un nodo foglia (children vuoto) porta i
     * valori delle metriche per quella combinazione esatta; un nodo
     * intermedio porta i valori aggregati (somma) dei suoi figli, per
     * mostrare un totale sulla riga "cappello" (es. il totale annuo
     * sulla riga Agente, sommando i mesi sottostanti).
     *
     * ATTENZIONE - limite noto: l'aggregazione dei nodi intermedi è
     * sempre una SOMMA dei figli, indipendentemente dal tipo di
     * aggregazione della metrica. Per SUM e COUNT è corretto. Per AVG,
     * MIN, MAX il totale mostrato sul nodo intermedio è una somma delle
     * medie/min/max dei figli, NON la media/min/max ricalcolata sui dati
     * originali - matematicamente diverso e potenzialmente fuorviante.
     * Corretto sarebbe ricalcolare quelle metriche con una query dedicata
     * per ogni livello di aggregazione, cosa che oggi non facciamo. Da
     * segnalare in UI (es. non mostrare il totale su nodi intermedi per
     * metriche AVG/MIN/MAX finché non risolto) o da risolvere quando
     * servirà davvero un'area con quelle metriche in gerarchia.
     */
    data class PivotNode(
        val dimId: UUID?,
        val valueId: Long?,
        val label: String,
        val children: List<PivotNode> = emptyList(),
        val values: Map<String, BigDecimal> = emptyMap(),
        val sourceRow: AggregateRow? = null
    ) {
        val isLeaf: Boolean get() = children.isEmpty()
    }

    /**
     * Costruisce l'albero a partire da righe piatte (una riga = una
     * combinazione completa di "dims"). Ogni livello dell'albero
     * corrisponde a una dimensione di "dims", nell'ordine dato (primo
     * elemento = radice). Ordinamento per label a ogni livello, coerente
     * con Qlik (non per id grezzo).
     *
     * Se "dims" è vuoto, ogni riga di input diventa un nodo foglia senza
     * ulteriore raggruppamento - caso base della ricorsione.
     */
    fun buildHierarchy(
        rows: List<AggregateRow>,
        dims: List<UUID>,
        metriche: List<AreaMetrica>,
        labelFor: (UUID, Long) -> String,
        colonnaFisicaFor: (UUID) -> String? = { null }
    ): List<PivotNode> {
        if (dims.isEmpty()) {
            return rows.map { row ->
                PivotNode(dimId = null, valueId = null, label = "", values = row.values, sourceRow = row)
            }
        }

        val dimId = dims.first()
        val remainingDims = dims.drop(1)

        val byValue = rows.groupBy { it.groupKeys[dimId] }
        val colonna = colonnaFisicaFor(dimId)
        val naturalOrder = colonna != null && DimensionSortOrders.usesNaturalOrder(colonna)

        val nodes = byValue.entries
            .mapNotNull { (valueId, rowsForValue) ->
                if (valueId == null) return@mapNotNull null
                val label = labelFor(dimId, valueId)
                val children = buildHierarchy(rowsForValue, remainingDims, metriche, labelFor, colonnaFisicaFor)
                val aggregatedValues = sumChildValues(children, metriche)
                PivotNode(
                    dimId = dimId,
                    valueId = valueId,
                    label = label,
                    children = children,
                    values = aggregatedValues
                )
            }

        return if (naturalOrder) {
            nodes.sortedBy { it.valueId }
        } else {
            nodes.sortedBy { it.label }
        }
    }

    /**
     * Somma i valori delle metriche sui figli diretti, per popolare il
     * totale del nodo intermedio - ma SOLO per le metriche dove sommare
     * i figli ha senso matematico (SUM, COUNT, COUNT_DISTINCT). Per AVG,
     * MIN, MAX il totale è omesso: la media dei mesi di un agente non
     * diventa "la media dell'anno" sommandole, sarebbe un numero senza
     * significato (media di medie ≠ media dei dati originali). Chi
     * guarda la riga Agente vedrà quella colonna vuota, non un valore
     * fuorviante.
     */
    private fun sumChildValues(children: List<PivotNode>, metriche: List<AreaMetrica>): Map<String, BigDecimal> {
        if (children.isEmpty()) return emptyMap()

        val summableMetricNames = metriche
            .filter { it.tipoAggregazione in setOf(TipoAggregazione.SUM, TipoAggregazione.COUNT, TipoAggregazione.COUNT_DISTINCT) }
            .map { it.nome }
            .toSet()

        val allKeys = children.flatMap { it.values.keys }.distinct()

        return allKeys
            .filter { key -> summableMetricNames.any { metricName -> key == metricName || key.startsWith("$metricName|") } }
            .associateWith { key ->
                children.fold(BigDecimal.ZERO) { acc, child -> acc + (child.values[key] ?: BigDecimal.ZERO) }
            }
    }

    /**
     * Appiattisce l'albero in una lista di percorsi foglia (usata per
     * costruire chiavi annidate tipo "v1|v2|v3" sull'asse Colonne, o per
     * derivare le righe visibili in ordine da mostrare in TreeGrid con
     * espansione). Ogni percorso è la sequenza di label dalla radice alla
     * foglia, nell'ordine di annidamento.
     */
    fun flattenLeafPaths(nodes: List<PivotNode>, pathSoFar: List<String> = emptyList()): List<Pair<List<String>, PivotNode>> =
        nodes.flatMap { node ->
            val path = pathSoFar + node.label
            if (node.isLeaf) {
                listOf(path to node)
            } else {
                flattenLeafPaths(node.children, path)
            }
        }
}