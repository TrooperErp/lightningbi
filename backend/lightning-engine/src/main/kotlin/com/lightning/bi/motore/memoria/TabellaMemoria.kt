package com.lightning.bi.motore.memoria

import org.roaringbitmap.RoaringBitmap
import org.roaringbitmap.RoaringBitmapWriter
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool

import java.util.stream.IntStream

/**
 * Indice di un campo: per ogni valore (id della symbol table) la bitmap delle righe in cui compare.
 * [valori] è ordinato in modo crescente; [righe] ha lo stesso indice.
 */
class IndiceValori internal constructor(
    private val valori: IntArray,
    private val righe: Array<RoaringBitmap>
) {
    val numeroValori: Int get() = valori.size

    /** L'i-esimo valore (in ordine di id) e le sue righe. */
    fun valore(i: Int): Int = valori[i]
    fun righe(i: Int): RoaringBitmap = righe[i]

    /** Le righe del valore, o null se il valore non compare nella tabella. */
    fun righeDi(valore: Int): RoaringBitmap? {
        val i = valori.binarySearch(valore)
        return if (i >= 0) righe[i] else null
    }

    internal fun byte(): Long = valori.size * 4L + righe.sumOf { it.sizeInBytes.toLong() }
}

/**
 * Una colonna caricata: l'id del valore per riga (indice = lbi_rid; 0 = non definito) e,
 * solo per i campi usati come dimensione, l'[indice].
 */
class CampoMemoria internal constructor(
    val colonna: String,
    val ids: IntArray,
    val indice: IndiceValori?
)

/** Una misura: il valore per riga in milionesimi (come Decimal(38,6)), con le righe senza valore. */
class MisuraMemoria internal constructor(
    val colonna: String,
    val valori: LongArray,
    val mancanti: RoaringBitmap
)

/**
 * Una tabella del dataset (occorrenza) in memoria, immutabile dopo la costruzione.
 * Il numero di riga è lbi_rid, lo stesso delle bitmap di ClickHouse; dopo un'incrementale ci
 * possono essere buchi: le righe vere sono in [presenti].
 *
 * Le colonne sono per nome FISICO (ImportedColumn.nomeCampo normalizzato): il passaggio
 * campo del dataset -> colonna lo fa DatasetMemoria.
 */
class TabellaMemoria internal constructor(
    val id: UUID,
    val fisica: String,
    val presenti: RoaringBitmap,
    private val campi: Map<String, CampoMemoria>,
    private val misure: Map<String, MisuraMemoria>
) {
    val numeroRighe: Int get() = presenti.cardinality

    fun campo(colonna: String): CampoMemoria? = campi[colonna]
    fun misura(colonna: String): MisuraMemoria? = misure[colonna]

    /** Memoria occupata, per il log del caricamento. */
    fun byte(): Long =
        presenti.sizeInBytes +
                campi.values.sumOf { it.ids.size * 4L + (it.indice?.byte() ?: 0L) } +
                misure.values.sumOf { it.valori.size * 8L + it.mancanti.sizeInBytes }

    /**
     * Costruisce una tabella. Il caricatore scrive negli array per lbi_rid (un thread solo per
     * tabella), poi [costruisci] fa gli indici in parallelo.
     *
     * @param maxRid il lbi_rid più alto della tabella
     * @param colonneCampi colonne caricate come id
     * @param indicizzate tra le colonne campi, quelle che hanno l'indice (le dimensioni)
     * @param colonneMisure colonne caricate come numero (copia numerica `<colonna>__n`)
     */
    class Costruttore(
        private val id: UUID,
        private val fisica: String,
        maxRid: Int,
        colonneCampi: Collection<String>,
        private val indicizzate: Set<String>,
        colonneMisure: Collection<String>
    ) {
        init {
            require(maxRid >= -1) { "lbi_rid massimo non valido: $maxRid" }
            require(indicizzate.all { it in colonneCampi }) { "Una colonna indicizzata non è tra le colonne caricate" }
        }

        val presenti = RoaringBitmap()
        private val ids: Map<String, IntArray> = colonneCampi.associateWith { IntArray(maxRid + 1) }
        private val numeri: Map<String, LongArray> = colonneMisure.associateWith { LongArray(maxRid + 1) }
        private val mancanti: Map<String, RoaringBitmap> = colonneMisure.associateWith { RoaringBitmap() }

        fun ids(colonna: String): IntArray = ids.getValue(colonna)
        fun numeri(colonna: String): LongArray = numeri.getValue(colonna)
        fun mancanti(colonna: String): RoaringBitmap = mancanti.getValue(colonna)

        fun costruisci(pool: ForkJoinPool): TabellaMemoria {
            presenti.runOptimize()
            val colonne = ids.keys.toList()
            val indici = inParallelo(pool, colonne.size) { i ->
                colonne[i].takeIf { it in indicizzate }?.let { indicizza(ids.getValue(it)) }
            }
            val campi = colonne.mapIndexed { i, c -> c to CampoMemoria(c, ids.getValue(c), indici[i]) }.toMap()
            val misure = numeri.keys.associateWith { c ->
                MisuraMemoria(c, numeri.getValue(c), mancanti.getValue(c).also { it.runOptimize() })
            }
            return TabellaMemoria(id, fisica, presenti, campi, misure)
        }

        /** Raggruppa le righe presenti per valore: coppie (valore, riga) ordinate, poi una bitmap per valore. */
        private fun indicizza(colonna: IntArray): IndiceValori {
            val n = presenti.cardinality
            val coppie = LongArray(n)
            var k = 0
            val scorri = presenti.intIterator
            while (scorri.hasNext()) {
                val rid = scorri.next()
                coppie[k++] = (colonna[rid].toLong() shl 32) or (rid.toLong() and 0xFFFFFFFFL)
            }
            coppie.sort()

            val valori = ArrayList<Int>()
            val righe = ArrayList<RoaringBitmap>()
            var i = 0
            while (i < n) {
                val valore = (coppie[i] ushr 32).toInt()
                val scrittore = RoaringBitmapWriter.writer().get()
                while (i < n && (coppie[i] ushr 32).toInt() == valore) {
                    scrittore.add((coppie[i] and 0xFFFFFFFFL).toInt())
                    i++
                }
                valori += valore
                righe += scrittore.get().also { it.runOptimize() }
            }
            return IndiceValori(valori.toIntArray(), righe.toTypedArray())
        }

        private fun <T> inParallelo(pool: ForkJoinPool, n: Int, f: (Int) -> T): List<T> {
            if (n == 0) return emptyList()
            try {
                return pool.submit(Callable { IntStream.range(0, n).parallel().mapToObj { f(it) }.toList() }).get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }
}