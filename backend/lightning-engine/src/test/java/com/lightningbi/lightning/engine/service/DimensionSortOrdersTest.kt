// src/test/kotlin/com/lightningbi/lightning/engine/service/DimensionSortOrdersTest.kt
package com.lightningbi.lightning.engine.service

import com.lightningbi.lightning_engine.service.DimensionSortOrders.confrontoNaturale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DimensionSortOrdersTest {

    private fun ordina(vararg s: String) = s.sortedWith { a, b -> confrontoNaturale(a, b) }

    @Test
    fun `numeri dentro il testo confrontati come numeri`() {
        assertEquals(listOf("Art 2", "Art 9", "Art 10", "Art 100"), ordina("Art 100", "Art 10", "Art 2", "Art 9"))
    }

    @Test
    fun `maiuscole e minuscole uguali`() {
        assertEquals(0, confrontoNaturale("rossi", "ROSSI"))
        assertEquals(listOf("alfa", "Beta", "gamma"), ordina("gamma", "Beta", "alfa"))
    }

    @Test
    fun `zeri iniziali ignorati nel confronto`() {
        assertEquals(0, confrontoNaturale("002", "2"))
        assertTrue(confrontoNaturale("009", "10") < 0)
    }

    @Test
    fun `solo zeri`() {
        assertEquals(0, confrontoNaturale("0", "000"))
        assertTrue(confrontoNaturale("0", "1") < 0)
    }

    @Test
    fun `numeri piu lunghi di un Long non vanno in overflow`() {
        assertTrue(confrontoNaturale("99999999999999999999", "100000000000000000000") < 0)
    }

    @Test
    fun `prefisso piu corto viene prima`() {
        assertEquals(listOf("", "A", "AB", "AB1"), ordina("AB1", "AB", "", "A"))
    }

    @Test
    fun `piu gruppi di cifre`() {
        assertEquals(listOf("v1.2.9", "v1.2.10", "v1.10.0"), ordina("v1.10.0", "v1.2.10", "v1.2.9"))
    }

    @Test
    fun `cifra prima della lettera`() {
        assertTrue(confrontoNaturale("1", "a") < 0)
    }

    @Test
    fun `antisimmetria`() {
        val campioni = listOf("", "a", "A1", "a10", "a2", "002", "2", "Z", "x y", "x10y", "x9z")
        for (a in campioni) for (b in campioni) {
            assertEquals(Integer.signum(confrontoNaturale(a, b)), -Integer.signum(confrontoNaturale(b, a)), "$a / $b")
        }
    }

    @Test
    fun `usesNaturalOrder solo per mese_numero`() {
        assertTrue(DimensionSortOrders_usesNatural("mese_numero"))
        assertTrue(!DimensionSortOrders_usesNatural("anno"))
    }

    private fun DimensionSortOrders_usesNatural(c: String) =
        com.lightningbi.lightning_engine.service.DimensionSortOrders.usesNaturalOrder(c)
}