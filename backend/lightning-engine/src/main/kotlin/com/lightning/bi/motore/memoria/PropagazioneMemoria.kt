package com.lightning.bi.motore.memoria

import com.lightning.bi.service.Collegamento
import org.roaringbitmap.FastAggregation
import org.roaringbitmap.RoaringBitmap
import org.roaringbitmap.RoaringBitmapWriter
import java.util.UUID

/** Condizione su un campo (set analysis): quali id di valore sono ammessi. */
typealias CondizioneValori = (Int) -> Boolean

/**
 * La propagazione delle selezioni in memoria, con la stessa logica di GrafoDataset (righeVive,
 * valoriAmmessi, insiemeDi):
 *
 * - selezione su un campo: OR delle bitmap dei valori scelti, dall'indice della tabella;
 * - messaggio lungo un collegamento: i valori della chiave nelle righe vive delle ALTRE tabelle
 *   (calcolate senza passare dal collegamento), in AND tra loro; righe della tabella con la
 *   chiave tra quei valori;
 * - righe vive = AND di tutti i termini; nessun termine = null (tutte le righe).
 *
 * Le selezioni sono per nome di campo (campo -> id dei valori). [omesso] è il campo la cui
 * selezione non conta. Le bitmap restituite sono nuove: chi le riceve le può modificare.
 */
class PropagazioneMemoria(private val dataset: DatasetMemoria) {

    private val grafo = dataset.grafo

    fun righeVive(
        tabella: UUID,
        selezioni: Map<String, Set<Long>>,
        omesso: String? = null,
        condizioni: Map<String, CondizioneValori> = emptyMap()
    ): RoaringBitmap? = Calcolo(selezioni, condizioni, omesso).vive(tabella, null, 0)

    /** I valori della chiave del collegamento nelle righe vive di [tabella], senza passare dal collegamento; null se non vincolata. */
    fun insieme(
        c: Collegamento,
        tabella: UUID,
        selezioni: Map<String, Set<Long>>,
        omesso: String? = null
    ): RoaringBitmap? = Calcolo(selezioni, emptyMap(), omesso).insiemeDi(c, tabella, 0)

    /** Le righe presenti di [tabella] con la chiave del collegamento tra [valori]. */
    fun righeConChiave(tabella: UUID, c: Collegamento, valori: RoaringBitmap): RoaringBitmap {
        val t = dataset.tabella(tabella)
        val chiave = dataset.chiave(c, tabella) ?: error("Chiave '${c.nome}' non caricata nella tabella $tabella")
        val scrittore = RoaringBitmapWriter.writer().get()
        val scorri = t.presenti.intIterator
        while (scorri.hasNext()) {
            val rid = scorri.next()
            if (valori.contains(chiave[rid])) scrittore.add(rid)
        }
        return scrittore.get()
    }

    /** I valori (id) della chiave del collegamento nelle righe indicate di [tabella]. */
    fun valoriChiave(tabella: UUID, c: Collegamento, righe: RoaringBitmap): RoaringBitmap {
        val chiave = dataset.chiave(c, tabella) ?: error("Chiave '${c.nome}' non caricata nella tabella $tabella")
        val valori = IntArray(righe.cardinality)
        var k = 0
        val scorri = righe.intIterator
        while (scorri.hasNext()) valori[k++] = chiave[scorri.next()]
        return RoaringBitmap.bitmapOfUnordered(*valori)
    }

    /** Un calcolo con selezioni fisse: ricorda i risultati per (tabella, collegamento escluso). */
    private inner class Calcolo(
        private val selezioni: Map<String, Set<Long>>,
        private val condizioni: Map<String, CondizioneValori>,
        private val omesso: String?
    ) {
        private val memo = HashMap<Pair<UUID, String?>, RoaringBitmap?>()

        fun vive(tabella: UUID, escludi: Collegamento?, profondita: Int): RoaringBitmap? {
            check(profondita <= PROFONDITA_MAX) { "Il modello del dataset ha un loop tra le tabelle" }
            val chiave = tabella to escludi?.nome
            if (memo.containsKey(chiave)) return memo[chiave]

            val termini = ArrayList<RoaringBitmap>()

            // Selezioni sui campi di questa tabella: OR delle bitmap dei valori scelti.
            selezioni.forEach { (campo, valori) ->
                if (campo != omesso && valori.isNotEmpty() && dataset.modello.colonnaDi(tabella, campo) != null) {
                    val indice = dataset.indice(tabella, campo)
                    val bitmap = valori.mapNotNull { indice?.righeDi(it.toInt()) }
                    termini += or(bitmap)
                }
            }
            // Condizioni (set analysis): come le selezioni, con un filtro al posto della lista.
            condizioni.forEach { (campo, ammesso) ->
                if (campo != omesso && dataset.modello.colonnaDi(tabella, campo) != null) {
                    val indice = dataset.indice(tabella, campo)
                    val bitmap = ArrayList<RoaringBitmap>()
                    if (indice != null) {
                        for (i in 0 until indice.numeroValori) if (ammesso(indice.valore(i))) bitmap += indice.righe(i)
                    }
                    termini += or(bitmap)
                }
            }
            // Messaggi dei collegamenti, tranne quello da cui si arriva.
            grafo.collegamenti.filter { tabella in it.occorrenze && it != escludi }.forEach { c ->
                val ammessi = ammessi(c, tabella, profondita + 1) ?: return@forEach
                termini += righeConChiave(tabella, c, ammessi)
            }

            val risultato = and(termini)
            memo[chiave] = risultato
            return risultato
        }

        /** I valori della chiave ammessi per [per] dalle altre tabelle del collegamento; null se nessuna è vincolata. */
        private fun ammessi(c: Collegamento, per: UUID, profondita: Int): RoaringBitmap? {
            val insiemi = c.occorrenze.filter { it != per }.mapNotNull { insiemeDi(c, it, profondita) }
            return and(insiemi)
        }

        fun insiemeDi(c: Collegamento, tabella: UUID, profondita: Int): RoaringBitmap? {
            val viva = vive(tabella, c, profondita) ?: return null
            return valoriChiave(tabella, c, viva)
        }
    }

    private fun or(bitmap: List<RoaringBitmap>): RoaringBitmap = when (bitmap.size) {
        0 -> RoaringBitmap()
        1 -> bitmap[0].clone()
        else -> FastAggregation.or(*bitmap.toTypedArray())
    }

    /** AND dei termini, partendo dal più piccolo; null se non ce ne sono. */
    private fun and(termini: List<RoaringBitmap>): RoaringBitmap? {
        if (termini.isEmpty()) return null
        val ordinati = termini.sortedBy { it.cardinality }
        var r = ordinati[0].clone()
        for (i in 1 until ordinati.size) {
            if (r.isEmpty) break
            r = RoaringBitmap.and(r, ordinati[i])
        }
        return r
    }

    private companion object {
        const val PROFONDITA_MAX = 64
    }
}