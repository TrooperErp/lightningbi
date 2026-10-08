package com.lightningbi.lightning_engine.service

import java.math.BigDecimal

/**
 * Ordinamento dei valori di un campo, come in Qlik: i valori con un numero (value_number) per
 * numero, gli altri per etichetta con confronto naturale, dopo i numeri. Unico per pivot,
 * grafici e liste: nessuno ordina per conto proprio.
 */
object OrdinamentoValori {

    fun confronta(numeroA: BigDecimal?, etichettaA: String, numeroB: BigDecimal?, etichettaB: String): Int = when {
        numeroA != null && numeroB != null -> numeroA.compareTo(numeroB).let { c ->
            if (c != 0) c else DimensionSortOrders.confrontoNaturale(etichettaA, etichettaB)
        }
        numeroA != null -> -1
        numeroB != null -> 1
        else -> DimensionSortOrders.confrontoNaturale(etichettaA, etichettaB)
    }

    fun <T> ordina(elementi: List<T>, numero: (T) -> BigDecimal?, etichetta: (T) -> String): List<T> =
        elementi.sortedWith { a, b -> confronta(numero(a), etichetta(a), numero(b), etichetta(b)) }
}