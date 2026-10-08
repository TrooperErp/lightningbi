// src/main/kotlin/com/lightningbi/lightning_engine/service/ValoriSimbolo.kt
package com.lightningbi.lightning_engine.service

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Regole pure sui valori delle symbol table (nessun database): chiave
 * d'identità del valore, sua forma numerica, divisione in blocchi per le query.
 * Estratte da SymbolLookupService per poterle testare; il comportamento è identico.
 */
object ValoriSimbolo {

    /** Numero massimo di valori per blocco. */
    const val CHUNK_SIZE = 5000

    /** Limite di byte per una query con la lista dei valori: ClickHouse rifiuta oltre 256 KiB (max_query_size). */
    const val MAX_BYTE_QUERY = 100_000

    /**
     * Chiave d'identità del valore, come in Qlik: i testi che si leggono come
     * lo stesso numero (2, 002, 2.0) hanno la stessa chiave. Con [testo]
     * (equivalente di text()) vale il testo esatto.
     */
    fun chiave(valore: String, testo: Boolean): String {
        if (!testo) {
            val n = toNumero(valore)
            if (n != null) return "n:" + n.stripTrailingZeros().toPlainString()
        }
        return "t:$valore"
    }

    /**
     * Il valore come numero, se lo è e se sta in Decimal(38,6); altrimenti
     * null (resta solo il testo). Un numero fuori scala sarebbe rifiutato da
     * ClickHouse e fermerebbe l'ETL per un campo che nessuno ordina in modo
     * numerico.
     */
    fun toNumero(valore: String): BigDecimal? {
        val numero = valore.trim().toBigDecimalOrNull() ?: return null
        val scalato = numero.setScale(6, RoundingMode.HALF_UP)
        return if (scalato.precision() <= 38) scalato else null
    }

    /**
     * Divide i valori in blocchi per numero (al massimo [chunkSize]) E per dimensione: i valori finiscono
     * nel testo della query, e con testi lunghi 5.000 valori superano il limite di ClickHouse.
     * Per ogni valore si contano il doppio dei byte (apici e backslash raddoppiati) più virgole e apici.
     */
    fun blocchiPerDimensione(
        values: Collection<String>,
        chunkSize: Int = CHUNK_SIZE,
        maxByte: Int = MAX_BYTE_QUERY
    ): List<List<String>> {
        val blocchi = mutableListOf<List<String>>()
        var corrente = mutableListOf<String>()
        var byte = 0
        for (v in values) {
            val costo = v.toByteArray(Charsets.UTF_8).size * 2 + 3
            if (corrente.isNotEmpty() && (corrente.size >= chunkSize || byte + costo > maxByte)) {
                blocchi += corrente
                corrente = mutableListOf()
                byte = 0
            }
            corrente += v
            byte += costo
        }
        if (corrente.isNotEmpty()) blocchi += corrente
        return blocchi
    }
}