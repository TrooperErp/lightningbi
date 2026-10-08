// src/test/kotlin/com/lightningbi/lightning/engine/service/PivotEngineTest.kt
// (stessa cartella e stessa riga `package` di OrdinamentoValoriTest)
package com.lightningbi.lightning.engine.service

import com.lightningbi.lightning_engine.model.AggregateRow
import com.lightningbi.lightning_engine.model.AreaMetrica
import com.lightningbi.lightning_engine.model.TipoAggregazione
import com.lightningbi.lightning_engine.service.PivotEngine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID

class PivotEngineTest {

    private val agente = UUID.randomUUID()
    private val mese = UUID.randomUUID()

    private val etichette = mapOf(
        agente to mapOf(1L to "Rossi", 2L to "Bianchi"),
        mese to mapOf(10L to "Gen", 20L to "Feb", 30L to "Mar")
    )
    private val numeroMese = mapOf(10L to BigDecimal(1), 20L to BigDecimal(2), 30L to BigDecimal(3))

    private fun label(dim: UUID, id: Long): String = etichette.getValue(dim).getValue(id)
    private fun ordine(dim: UUID, id: Long): BigDecimal? = if (dim == mese) numeroMese[id] else null

    private fun metrica(nome: String, tipo: TipoAggregazione) =
        AreaMetrica(UUID.randomUUID(), UUID.randomUUID(), nome, "col", tipo)

    private fun n(v: Long) = BigDecimal.valueOf(v)

    private fun riga(chiavi: Map<UUID, Long>, valori: Map<String, BigDecimal>) = AggregateRow(chiavi, valori)

    private val fatt = metrica("Fatt", TipoAggregazione.SUM)

    private val righe = listOf(
        riga(mapOf(agente to 1L, mese to 20L), mapOf("Fatt" to n(7))),
        riga(mapOf(agente to 1L, mese to 10L), mapOf("Fatt" to n(5))),
        riga(mapOf(agente to 2L, mese to 10L), mapOf("Fatt" to n(3)))
    )

    @Test
    fun `un livello - mesi in ordine numerico e non alfabetico`() {
        val rows = listOf(
            riga(mapOf(mese to 30L), mapOf("Fatt" to n(1))),
            riga(mapOf(mese to 10L), mapOf("Fatt" to n(2))),
            riga(mapOf(mese to 20L), mapOf("Fatt" to n(3)))
        )
        val nodi = PivotEngine.buildHierarchy(rows, listOf(mese), listOf(fatt), ::label, ordineFor = ::ordine)
        assertEquals(listOf("Gen", "Feb", "Mar"), nodi.map { it.label })
    }

    @Test
    fun `due livelli - i figli stanno sotto il padre giusto`() {
        val nodi = PivotEngine.buildHierarchy(righe, listOf(agente, mese), listOf(fatt), ::label, ordineFor = ::ordine)
        assertEquals(listOf("Bianchi", "Rossi"), nodi.map { it.label })
        val rossi = nodi.first { it.label == "Rossi" }
        assertEquals(listOf("Gen", "Feb"), rossi.children.map { it.label })
        assertEquals(1, nodi.first { it.label == "Bianchi" }.children.size)
    }

    @Test
    fun `SUM - il totale del padre e la somma dei figli`() {
        val nodi = PivotEngine.buildHierarchy(righe, listOf(agente, mese), listOf(fatt), ::label, ordineFor = ::ordine)
        assertEquals(n(12), nodi.first { it.label == "Rossi" }.values["Fatt"])
        assertEquals(n(3), nodi.first { it.label == "Bianchi" }.values["Fatt"])
    }

    @Test
    fun `COUNT - il totale del padre e la somma dei figli`() {
        val conteggio = metrica("N", TipoAggregazione.COUNT)
        val rows = listOf(
            riga(mapOf(agente to 1L, mese to 10L), mapOf("N" to n(2))),
            riga(mapOf(agente to 1L, mese to 20L), mapOf("N" to n(4)))
        )
        val nodi = PivotEngine.buildHierarchy(rows, listOf(agente, mese), listOf(conteggio), ::label, ordineFor = ::ordine)
        assertEquals(n(6), nodi.single().values["N"])
    }

    @Test
    fun `AVG MIN MAX COUNT_DISTINCT - il totale del padre non si somma`() {
        for (tipo in listOf(TipoAggregazione.AVG, TipoAggregazione.MIN, TipoAggregazione.MAX, TipoAggregazione.COUNT_DISTINCT)) {
            val m = metrica("X", tipo)
            val rows = listOf(
                riga(mapOf(agente to 1L, mese to 10L), mapOf("X" to n(2))),
                riga(mapOf(agente to 1L, mese to 20L), mapOf("X" to n(4)))
            )
            val nodi = PivotEngine.buildHierarchy(rows, listOf(agente, mese), listOf(m), ::label, ordineFor = ::ordine)
            assertTrue(nodi.single().values.isEmpty(), "tipo $tipo")
        }
    }

    @Test
    fun `il totale calcolato sui dati originali vince sulla somma dei figli`() {
        val totali = mapOf(listOf(1L) to mapOf("Fatt" to n(99)))
        val nodi = PivotEngine.buildHierarchy(righe, listOf(agente, mese), listOf(fatt), ::label, totali, ordineFor = ::ordine)
        assertEquals(n(99), nodi.first { it.label == "Rossi" }.values["Fatt"])
        assertEquals(n(3), nodi.first { it.label == "Bianchi" }.values["Fatt"])
    }

    @Test
    fun `con le colonne le chiavi composte si sommano ma Variaz non si somma`() {
        val rows = listOf(
            riga(mapOf(agente to 1L, mese to 10L), mapOf("Fatt|2025" to n(5), "Fatt|Variaz.%" to n(10))),
            riga(mapOf(agente to 1L, mese to 20L), mapOf("Fatt|2025" to n(7), "Fatt|Variaz.%" to n(20)))
        )
        val nodi = PivotEngine.buildHierarchy(rows, listOf(agente, mese), listOf(fatt), ::label, ordineFor = ::ordine)
        val valori = nodi.single().values
        assertEquals(n(12), valori["Fatt|2025"])
        assertFalse(valori.containsKey("Fatt|Variaz.%"))
    }

    @Test
    fun `senza dimensioni ogni riga diventa una foglia`() {
        val nodi = PivotEngine.buildHierarchy(righe, emptyList(), listOf(fatt), ::label)
        assertEquals(3, nodi.size)
        assertTrue(nodi.all { it.isLeaf })
    }

    @Test
    fun `le righe senza valore per la dimensione sono scartate`() {
        val rows = righe + riga(mapOf(mese to 10L), mapOf("Fatt" to n(100)))
        val nodi = PivotEngine.buildHierarchy(rows, listOf(agente), listOf(fatt), ::label)
        assertEquals(2, nodi.size)
    }

    @Test
    fun `flattenLeafPaths restituisce i percorsi foglia in ordine`() {
        val nodi = PivotEngine.buildHierarchy(righe, listOf(agente, mese), listOf(fatt), ::label, ordineFor = ::ordine)
        val percorsi = PivotEngine.flattenLeafPaths(nodi).map { it.first }
        // l'ultima voce "" è la foglia che porta la riga sorgente
        assertEquals(
            listOf(listOf("Bianchi", "Gen", ""), listOf("Rossi", "Gen", ""), listOf("Rossi", "Feb", "")),
            percorsi
        )
    }
    // PivotEngineTest.kt — aggiungere dentro la classe
    @Test
    fun `tre livelli - totali corretti a ogni livello`() {
        val anno = UUID.randomUUID()
        val anni = mapOf(2025L to "2025", 2026L to "2026")
        val lbl: (UUID, Long) -> String = { d, id -> if (d == anno) anni.getValue(id) else label(d, id) }
        val rows = listOf(
            riga(mapOf(agente to 1L, anno to 2025L, mese to 10L), mapOf("Fatt" to n(1))),
            riga(mapOf(agente to 1L, anno to 2025L, mese to 20L), mapOf("Fatt" to n(2))),
            riga(mapOf(agente to 1L, anno to 2026L, mese to 10L), mapOf("Fatt" to n(4)))
        )
        val nodi = PivotEngine.buildHierarchy(rows, listOf(agente, anno, mese), listOf(fatt), lbl, ordineFor = ::ordine)
        val rossi = nodi.single()
        assertEquals(n(7), rossi.values["Fatt"])
        assertEquals(listOf("2025", "2026"), rossi.children.map { it.label })
        assertEquals(n(3), rossi.children[0].values["Fatt"])
        assertEquals(listOf("Gen", "Feb"), rossi.children[0].children.map { it.label })
    }

    @Test
    fun `SUM e AVG insieme - si somma solo la SUM`() {
        val media = metrica("Media", TipoAggregazione.AVG)
        val rows = listOf(
            riga(mapOf(agente to 1L, mese to 10L), mapOf("Fatt" to n(5), "Media" to n(10))),
            riga(mapOf(agente to 1L, mese to 20L), mapOf("Fatt" to n(7), "Media" to n(20)))
        )
        val valori = PivotEngine.buildHierarchy(rows, listOf(agente, mese), listOf(fatt, media), ::label, ordineFor = ::ordine)
            .single().values
        assertEquals(n(12), valori["Fatt"])
        assertFalse(valori.containsKey("Media"))
    }

    @Test
    fun `metrica mancante in un figlio vale zero nella somma`() {
        val rows = listOf(
            riga(mapOf(agente to 1L, mese to 10L), mapOf("Fatt" to n(5))),
            riga(mapOf(agente to 1L, mese to 20L), emptyMap())
        )
        val nodi = PivotEngine.buildHierarchy(rows, listOf(agente, mese), listOf(fatt), ::label, ordineFor = ::ordine)
        assertEquals(n(5), nodi.single().values["Fatt"])
        assertEquals(2, nodi.single().children.size)
    }

    @Test
    fun `colonne diverse si sommano separatamente`() {
        val rows = listOf(
            riga(mapOf(agente to 1L, mese to 10L), mapOf("Fatt|2025" to n(1), "Fatt|2026" to n(10))),
            riga(mapOf(agente to 1L, mese to 20L), mapOf("Fatt|2025" to n(2), "Fatt|2026" to n(20)))
        )
        val valori = PivotEngine.buildHierarchy(rows, listOf(agente, mese), listOf(fatt), ::label, ordineFor = ::ordine)
            .single().values
        assertEquals(n(3), valori["Fatt|2025"])
        assertEquals(n(30), valori["Fatt|2026"])
    }

    @Test
    fun `totale originale al secondo livello e il padre lo somma`() {
        val totali = mapOf(listOf(1L, 10L) to mapOf("Fatt" to n(50)))
        val nodi = PivotEngine.buildHierarchy(righe, listOf(agente, mese), listOf(fatt), ::label, totali, ordineFor = ::ordine)
        val rossi = nodi.first { it.label == "Rossi" }
        assertEquals(n(50), rossi.children.first { it.label == "Gen" }.values["Fatt"])
        assertEquals(n(57), rossi.values["Fatt"])
        // Bianchi ha lo stesso mese (id 10) ma un percorso diverso: non deve prendere il 50
        assertEquals(n(3), nodi.first { it.label == "Bianchi" }.values["Fatt"])
    }

    @Test
    fun `metrica con nome che inizia come un'altra non viene confusa`() {
        val fatturatoMedio = metrica("Fatturato", TipoAggregazione.AVG)
        val rows = listOf(
            riga(mapOf(agente to 1L, mese to 10L), mapOf("Fatt" to n(1), "Fatturato" to n(100))),
            riga(mapOf(agente to 1L, mese to 20L), mapOf("Fatt" to n(2), "Fatturato" to n(200)))
        )
        val valori = PivotEngine.buildHierarchy(rows, listOf(agente, mese), listOf(fatt, fatturatoMedio), ::label, ordineFor = ::ordine)
            .single().values
        assertEquals(n(3), valori["Fatt"])
        assertFalse(valori.containsKey("Fatturato"))
    }
}