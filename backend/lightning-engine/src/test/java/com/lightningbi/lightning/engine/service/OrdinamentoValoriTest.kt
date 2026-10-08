package com.lightningbi.lightning.engine.service

import com.lightningbi.lightning_engine.service.OrdinamentoValori
import java.math.BigDecimal
import java.util.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class OrdinamentoValoriTest {

    private data class V(val numero: BigDecimal?, val etichetta: String)

    private fun n(x: Int) = BigDecimal(x)

    private fun ordina(vararg valori: V): List<String> =
        OrdinamentoValori.ordina(valori.toList(), { it.numero }, { it.etichetta }).map { it.etichetta }

    @Test
    fun `i mesi si ordinano per numero e non per nome`() {
        val mesi = listOf("Gen", "Feb", "Mar", "Apr", "Mag", "Giu", "Lug", "Ago", "Set", "Ott", "Nov", "Dic")
        val mescolati = mesi.mapIndexed { i, m -> V(n(i + 1), m) }.shuffled(Random(1))
        assertEquals(mesi, ordina(*mescolati.toTypedArray()))
    }

    @Test
    fun `i numeri si ordinano per valore, non come testo`() {
        assertEquals(
            listOf("9", "10", "100"),
            ordina(V(n(100), "100"), V(n(9), "9"), V(n(10), "10"))
        )
    }

    @Test
    fun `i valori con numero vengono prima di quelli senza`() {
        assertEquals(
            listOf("1", "5", "abc"),
            ordina(V(n(5), "5"), V(null, "abc"), V(n(1), "1"))
        )
    }

    @Test
    fun `i testi senza numero si ordinano in modo naturale, senza distinguere maiuscole`() {
        assertEquals(
            listOf("art 2", "Art 9", "Art 10"),
            ordina(V(null, "Art 10"), V(null, "art 2"), V(null, "Art 9"))
        )
    }

    @Test
    fun `a parità di numero decide l'etichetta`() {
        assertEquals(listOf("a", "b"), ordina(V(n(1), "b"), V(n(1), "a")))
    }

    @Test
    fun `una lista vuota resta vuota`() {
        assertEquals(emptyList(), ordina())
    }

    @Test
    fun `il confronto è antisimmetrico`() {
        val a = OrdinamentoValori.confronta(n(1), "x", null, "a")
        val b = OrdinamentoValori.confronta(null, "a", n(1), "x")
        assertEquals(-1, Integer.signum(a))
        assertEquals(1, Integer.signum(b))
    }

    // OrdinamentoValoriTest.kt — aggiungere dentro la classe
    @Test
    fun `negativi e decimali in ordine numerico`() {
        assertEquals(
            listOf("-1", "0", "1,5", "2"),
            ordina(V(n(2), "2"), V(BigDecimal("1.5"), "1,5"), V(n(-1), "-1"), V(n(0), "0"))
        )
    }

    @Test
    fun `2 e 2,0 sono lo stesso numero - decide l'etichetta`() {
        // se il confronto usasse equals di BigDecimal (che guarda la scala) fallirebbe
        assertEquals(listOf("a", "b"), ordina(V(BigDecimal("2.0"), "b"), V(n(2), "a")))
    }

    @Test
    fun `002 02 e 2 restano insieme tra 1 e 3`() {
        val r = ordina(V(n(3), "3"), V(n(2), "002"), V(n(1), "1"), V(n(2), "2"), V(n(2), "02"))
        assertEquals("1", r.first())
        assertEquals("3", r.last())
        assertEquals(setOf("002", "02", "2"), r.subList(1, 4).toSet())
    }

    @Test
    fun `etichetta vuota prima del testo`() {
        assertEquals(listOf("", "A"), ordina(V(null, "A"), V(null, "")))
    }

    @Test
    fun `testi con spazi e numeri lunghi`() {
        assertEquals(
            listOf("Cliente 2", "Cliente 10", "Cliente 100"),
            ordina(V(null, "Cliente 100"), V(null, "Cliente 2"), V(null, "Cliente 10"))
        )
    }
}