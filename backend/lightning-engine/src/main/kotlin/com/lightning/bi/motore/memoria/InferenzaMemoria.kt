package com.lightning.bi.motore.memoria

import com.lightning.bi.service.Collegamento
import java.util.BitSet
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

/**
 * L'inferenza logica in memoria, come Qlik: dalle selezioni, le righe possibili di ogni tabella
 * e i valori possibili delle chiavi. Stessa logica di GrafoDataset (righeVive, valoriAmmessi,
 * insiemeDi), con strutture dense al posto delle sottoquery:
 *
 * - un vincolo su una tabella è (colonna di codici, codici ammessi in un BitSet): selezioni,
 *   condizioni della set analysis e messaggi dei collegamenti sono tutti vincoli;
 * - le righe vive di una tabella = un giro sulle righe presenti, controllando tutti i vincoli;
 *   nessun vincolo = null (tutte le righe), come in GrafoDataset;
 * - il messaggio di una tabella lungo un collegamento = i codici della chiave nelle sue righe vive
 *   calcolate senza quel collegamento; null se la tabella non ha vincoli.
 *
 * I giri sulle righe vanno a parole da 64 righe e, con [pool], in blocchi paralleli.
 * Uno [Stato] vale per un insieme di selezioni e ricorda ogni (tabella, collegamento escluso):
 * ogni pezzo si calcola una volta sola. È thread-safe.
 */
class InferenzaMemoria(
    private val dataset: DatasetMemoria,
    private val pool: PoolMotore? = null
) {

    private val grafo = dataset.grafo

    /** Lo stato per queste selezioni (campo -> codici); [omesso] = campo la cui selezione non conta. */
    fun calcola(
        selezioni: Map<String, Set<Long>>,
        omesso: String? = null,
        condizioni: Map<String, CondizioneValori> = emptyMap()
    ): Stato = Stato(selezioni, omesso, condizioni)

    /** I codici di [colonna] nelle righe indicate di [tabella] (null = tutte le righe presenti). */
    fun codici(tabella: UUID, colonna: IntArray, righe: BitSet?): BitSet {
        val parole = righe?.toLongArray() ?: parolePresenti(dataset.tabella(tabella))
        val parziali = inBlocchi(parole.size * 64) { da, a ->
            val b = BitSet()
            for (w in da / 64 until (a + 63) / 64) {
                var x = parole[w]
                val base = w * 64
                while (x != 0L) {
                    b.set(colonna[base + java.lang.Long.numberOfTrailingZeros(x)])
                    x = x and (x - 1)
                }
            }
            b
        }
        val r = parziali[0]
        for (i in 1 until parziali.size) r.or(parziali[i])
        return r
    }

    /** Un vincolo: la riga passa se il suo codice in [codici] è tra gli [ammessi]. */
    private class Vincolo(val codici: IntArray, val ammessi: BitSet)

    inner class Stato internal constructor(
        private val selezioni: Map<String, Set<Long>>,
        private val omesso: String?,
        private val condizioni: Map<String, CondizioneValori>
    ) {
        private val memo = HashMap<Pair<UUID, String?>, BitSet?>()

        /** Le righe vive della tabella, o null se nessun vincolo la tocca. Non modificare il risultato. */
        @Synchronized
        fun vive(tabella: UUID): BitSet? = vive(tabella, null, 0)

        /** Il messaggio di [tabella] lungo [c]: codici della chiave nelle sue righe vive senza [c]; null se senza vincoli. */
        @Synchronized
        fun insieme(c: Collegamento, tabella: UUID): BitSet? = insiemeDi(c, tabella, 0)

        private fun vive(tabella: UUID, escludi: Collegamento?, profondita: Int): BitSet? {
            check(profondita <= PROFONDITA_MAX) { "Il modello del dataset ha un loop tra le tabelle" }
            val chiave = tabella to escludi?.nome
            if (memo.containsKey(chiave)) return memo[chiave]

            val vincoli = ArrayList<Vincolo>()
            var vuoto = false

            // Selezioni sui campi della tabella.
            selezioni.forEach { (campo, valori) ->
                if (campo != omesso && valori.isNotEmpty() && dataset.modello.colonnaDi(tabella, campo) != null) {
                    val colonna = dataset.colonna(tabella, campo)
                    if (colonna == null) vuoto = true
                    else vincoli += Vincolo(colonna.ids, BitSet().apply { valori.forEach { set(it.toInt()) } })
                }
            }
            // Condizioni (set analysis): codici ammessi tra i valori del campo nella tabella.
            condizioni.forEach { (campo, ammesso) ->
                if (campo != omesso && dataset.modello.colonnaDi(tabella, campo) != null) {
                    val colonna = dataset.colonna(tabella, campo)
                    val indice = dataset.indice(tabella, campo)
                    if (colonna == null || indice == null) vuoto = true
                    else {
                        val b = BitSet()
                        for (i in 0 until indice.numeroValori) {
                            val v = indice.valore(i)
                            if (ammesso(v)) b.set(v)
                        }
                        vincoli += Vincolo(colonna.ids, b)
                    }
                }
            }
            // Messaggi dei collegamenti, tranne quello da cui si arriva.
            grafo.collegamenti.filter { tabella in it.occorrenze && it != escludi }.forEach { c ->
                val ammessi = ammessi(c, tabella, profondita + 1) ?: return@forEach
                val codici = dataset.chiave(c, tabella)
                    ?: error("Chiave '${c.nome}' non caricata nella tabella $tabella")
                vincoli += Vincolo(codici, ammessi)
            }

            val risultato = when {
                vuoto -> BitSet()
                vincoli.isEmpty() -> null
                else -> scorri(tabella, vincoli)
            }
            memo[chiave] = risultato
            return risultato
        }

        /** I codici della chiave ammessi per [per] dalle altre tabelle del collegamento (AND); null se nessuna è vincolata. */
        private fun ammessi(c: Collegamento, per: UUID, profondita: Int): BitSet? {
            var r: BitSet? = null
            for (altra in c.occorrenze) {
                if (altra == per) continue
                val insieme = insiemeDi(c, altra, profondita) ?: continue
                if (r == null) r = insieme.clone() as BitSet else r.and(insieme)
            }
            return r
        }

        private fun insiemeDi(c: Collegamento, tabella: UUID, profondita: Int): BitSet? {
            val viva = vive(tabella, c, profondita) ?: return null
            val codici = dataset.chiave(c, tabella) ?: error("Chiave '${c.nome}' non caricata nella tabella $tabella")
            return codici(tabella, codici, viva)
        }
    }

    /** Un giro sulle righe presenti, a parole da 64: passano quelle che soddisfano tutti i vincoli. */
    private fun scorri(tabella: UUID, vincoli: List<Vincolo>): BitSet {
        val parole = parolePresenti(dataset.tabella(tabella))
        val n = vincoli.size
        val codici = Array(n) { vincoli[it].codici }
        val ammessi = Array(n) { vincoli[it].ammessi }
        val uscita = LongArray(parole.size)
        // Ogni blocco scrive solo le sue parole: nessuna sovrapposizione tra thread.
        inBlocchi(parole.size * 64) { da, a ->
            for (w in da / 64 until (a + 63) / 64) {
                var x = parole[w]
                var r = 0L
                val base = w * 64
                while (x != 0L) {
                    val bit = java.lang.Long.numberOfTrailingZeros(x)
                    val rid = base + bit
                    var ok = true
                    var j = 0
                    while (j < n) {
                        if (!ammessi[j].get(codici[j][rid])) {
                            ok = false
                            break
                        }
                        j++
                    }
                    if (ok) r = r or (1L shl bit)
                    x = x and (x - 1)
                }
                uscita[w] = r
            }
        }
        return BitSet.valueOf(uscita)
    }

    private fun <T> inBlocchi(righe: Int, f: (Int, Int) -> T): List<T> =
        pool?.inBlocchi(righe, f) ?: listOf(f(0, righe))

    private companion object {
        const val PROFONDITA_MAX = 64

        /** Le righe presenti di ogni tabella come parole da 64, calcolate una volta per tabella caricata. Non modificare. */
        private val parole: MutableMap<TabellaMemoria, LongArray> = Collections.synchronizedMap(WeakHashMap())

        fun parolePresenti(t: TabellaMemoria): LongArray = parole.getOrPut(t) {
            val b = BitSet(t.numeroRighe)
            val scorri = t.presenti.intIterator
            while (scorri.hasNext()) b.set(scorri.next())
            b.toLongArray()
        }
    }
}