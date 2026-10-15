package com.lightning.bi.motore.memoria

import com.lightning.bi.service.Naming
import org.roaringbitmap.RoaringBitmap
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * La symbol table di un campo in memoria (ch_lbi_symbol_<colonna>): per ogni id il testo e il
 * numero, e l'ordine dei valori calcolato una volta sola.
 *
 * Ordine identico a FiltriService.valori: value_number crescente (senza numero in fondo), poi
 * value_string, poi id. L'id 0 ("non definito") non è in tabella: testo vuoto, senza numero.
 *
 * I numeri sono in milionesimi (Decimal(38,6)); [NESSUNO] = senza numero.
 */
class SimboliMemoria private constructor(
    val colonna: String,
    private val testi: Array<String?>,
    private val numeri: LongArray,
    private val rango: IntArray
) {
    /** Il testo grezzo del valore (come value_string); null per 0 o un id sconosciuto. */
    fun testo(id: Int): String? = if (id > 0 && id < testi.size) testi[id] else null

    /** Il numero del valore in milionesimi, o null. */
    fun numero(id: Int): Long? =
        if (id > 0 && id < numeri.size && numeri[id] != NESSUNO) numeri[id] else null

    /** Posizione del valore nell'ordine del campo; un id sconosciuto va in fondo. */
    fun rango(id: Int): Int = if (id >= 0 && id < rango.size) rango[id] else Int.MAX_VALUE

    /** Gli id indicati, nell'ordine del campo. */
    fun inOrdine(ids: RoaringBitmap): IntArray {
        val coppie = LongArray(ids.cardinality)
        var k = 0
        val scorri = ids.intIterator
        while (scorri.hasNext()) {
            val id = scorri.next()
            coppie[k++] = (rango(id).toLong() shl 32) or (id.toLong() and 0xFFFFFFFFL)
        }
        coppie.sort()
        return IntArray(coppie.size) { (coppie[it] and 0xFFFFFFFFL).toInt() }
    }

    /** La ricerca di FiltriService: il testo contiene [minuscolo], senza maiuscole. */
    fun contiene(id: Int, minuscolo: String): Boolean =
        (testo(id) ?: "").lowercase().contains(minuscolo)

    companion object {
        const val NESSUNO = Long.MIN_VALUE
        private val MILIONE = BigDecimal(1_000_000)

        /** Legge la symbol table della colonna fisica [colonna] da ClickHouse. */
        fun carica(jdbc: JdbcTemplate, colonna: String): SimboliMemoria {
            val tabella = Naming.symbolTable(colonna)
            val max = jdbc.queryForObject("SELECT max(value_id) FROM $tabella", Long::class.java) ?: 0L
            require(max < Int.MAX_VALUE) { "Symbol table $tabella con id oltre il limite: $max" }
            val n = max.toInt() + 1

            val testi = arrayOfNulls<String>(n)
            val numeri = LongArray(n) { NESSUNO }
            jdbc.query("SELECT value_id, value_string, value_number FROM $tabella") { rs ->
                val id = rs.getLong(1).toInt()
                testi[id] = rs.getString(2)
                numeri[id] = rs.getBigDecimal(3)?.let { milionesimi(it) } ?: NESSUNO
            }
            testi[0] = ""

            // Ordine: numero (senza in fondo), testo, id.
            val ids = (0 until n).filter { it == 0 || testi[it] != null }.toTypedArray()
            ids.sortWith(
                compareBy<Int> { if (numeri[it] == NESSUNO) 1 else 0 }
                    .thenBy { numeri[it] }
                    .thenBy { testi[it] }
                    .thenBy { it }
            )
            val rango = IntArray(n) { Int.MAX_VALUE }
            ids.forEachIndexed { posizione, id -> rango[id] = posizione }
            return SimboliMemoria(colonna, testi, numeri, rango)
        }

        /** Un Decimal(38,6) in milionesimi; oltre il Long si ferma al limite (ordina comunque giusto). */
        fun milionesimi(v: BigDecimal): Long {
            val scalato = v.multiply(MILIONE).setScale(0, RoundingMode.HALF_UP)
            return when {
                scalato > BigDecimal(Long.MAX_VALUE) -> Long.MAX_VALUE
                scalato <= BigDecimal(NESSUNO + 1) -> NESSUNO + 1
                else -> scalato.toLong()
            }
        }
    }
}