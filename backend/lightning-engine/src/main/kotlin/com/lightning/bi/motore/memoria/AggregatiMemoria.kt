package com.lightning.bi.motore.memoria

import com.lightning.bi.model.AggregateOrder
import com.lightning.bi.model.AggregateRow
import com.lightning.bi.model.AreaMetrica
import com.lightning.bi.model.RuoloTabella
import com.lightning.bi.model.TipoAggregazione
import com.lightning.bi.service.Collegamento
import com.lightning.bi.service.StarQueryBuilder
import com.lightning.bi.service.setanalysis.SetAnalysisParser
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.BitSet
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/** Un aggregato che il motore in memoria non sa ancora calcolare: risponde ClickHouse. */
class AggregatoNonSupportato(messaggio: String) : RuntimeException(messaggio)

/**
 * Gli aggregati di UN Fatti in memoria, come il motore di calcolo di Qlik: si scorrono le righe
 * possibili del Fatti, si leggono i codici delle dimensioni e si accumulano le misure per gruppo.
 * Stesso risultato di AggregateService.aggregaFatti:
 *
 * - dimensioni con le regole di StarQueryBuilder: colonna del Fatti; altrimenti una tabella
 *   raggiungibile senza passare da altri Fatti, lungo i collegamenti, prendendo per ogni chiave la
 *   prima riga (LEFT ANY JOIN); altrimenti 0. Un campo raggiungibile solo attraverso un altro Fatti
 *   non è ancora supportato;
 * - misure con condizione (set analysis): ognuna conta solo le righe della sua condizione
 *   (un'inferenza per condizione diversa), come sumIf/countIf;
 * - SUM, AVG, MIN, MAX sulle copie numeriche (i valori mancanti non contano); COUNT sulle righe o
 *   sugli id diversi da 0; COUNT_DISTINCT sugli id diversi da 0;
 * - ordine per misura e limite + 1 righe, come la query di oggi.
 */
@Service
class AggregatiMemoria(private val poolMotore: PoolMotore) {

    private val log = LoggerFactory.getLogger(AggregatiMemoria::class.java)

    fun aggregaFatti(
        d: DatasetMemoria,
        fatti: UUID,
        metriche: List<AreaMetrica>,
        dimensioni: List<StarQueryBuilder.DimensioneUsata>,
        selezioni: Map<String, Set<Long>>,
        ordine: AggregateOrder?,
        metricaOrdine: AreaMetrica?,
        limite: Int,
        forzato: String?
    ): List<AggregateRow> {
        val tabella = d.tabella(fatti)
        val risolutori = dimensioni.map { risolutore(d, fatti, it) }
        val misure = metriche.map { preparaMisura(tabella, it) }

        // Le righe di ogni misura: quelle delle selezioni, oppure quelle della sua condizione
        // (una inferenza per condizione diversa). Null = tutte le righe presenti.
        val inferenza = InferenzaMemoria(d, poolMotore)
        val viva = inferenza.calcola(selezioni).vive(fatti)
        val perCondizione = HashMap<String, BitSet?>()
        val righeMisura: Array<BitSet?> = Array(metriche.size) { j ->
            val testo = metriche[j].condizione
            if (testo == null) viva
            else if (perCondizione.containsKey(testo)) perCondizione[testo]
            else {
                val s = SetAnalysisMemoria.traduci(SetAnalysisParser.analizza(testo), selezioni, d, forzato)
                inferenza.calcola(s.selezioni, null, s.condizioni).vive(fatti).also { perCondizione[testo] = it }
            }
        }
        // Si scorre l'unione delle righe di tutte le misure; ogni misura conta solo le sue.
        val tutteUguali = righeMisura.all { it === viva }
        val parole: LongArray = when {
            tutteUguali -> viva?.toLongArray() ?: parolePresenti(tabella)
            righeMisura.any { it == null } -> parolePresenti(tabella)
            else -> {
                val unione = BitSet()
                righeMisura.forEach { unione.or(it!!) }
                unione.toLongArray()
            }
        }
        val filtri: Array<BitSet?> = Array(metriche.size) { j -> if (tutteUguali) null else righeMisura[j] }

        val parziali = poolMotore.inBlocchi(parole.size * 64) { da, a ->
            val p = Parziale(risolutori.size, misure, filtri)
            for (w in da / 64 until (a + 63) / 64) {
                var x = parole[w]
                val base = w * 64
                while (x != 0L) {
                    p.aggiungi(base + java.lang.Long.numberOfTrailingZeros(x), risolutori)
                    x = x and (x - 1)
                }
            }
            p
        }

        // Unione dei blocchi: i gruppi si riconoscono dai codici.
        val totale = Parziale(risolutori.size, misure, filtri)
        parziali.forEach { totale.unisci(it) }

        var righe = (0 until totale.numeroGruppi).map { g ->
            val codici = totale.codici(g)
            AggregateRow(
                groupKeys = dimensioni.indices.associate { i -> dimensioni[i].dimensioneId to codici[i].toLong() },
                values = metriche.indices.associate { j -> metriche[j].nome to totale.valore(g, j) }
            )
        }
        if (metricaOrdine != null) {
            val nome = metricaOrdine.nome
            righe = when (ordine) {
                AggregateOrder.METRIC_DESC -> righe.sortedByDescending { it.values[nome] ?: BigDecimal.ZERO }
                AggregateOrder.METRIC_ASC -> righe.sortedBy { it.values[nome] ?: BigDecimal.ZERO }
                else -> righe
            }
        }
        return righe.take(limite + 1)
    }

    // ================= Dimensioni =================

    /** Come si legge il codice di una dimensione da una riga del Fatti. */
    private class Risolutore(
        /** Colonna del Fatti (caso 1), oppure chiave del primo passaggio (caso 2). */
        val colonna: IntArray?,
        /** Caso 2: codice della chiave del Fatti -> codice del valore (0 = nessuna riga). */
        val mappa: IntArray?
    ) {
        fun codice(rid: Int): Int {
            val c = colonna ?: return 0
            val m = mappa ?: return c[rid]
            val k = c[rid]
            return if (k < m.size) m[k] else 0
        }
    }

    private val risolutori: MutableMap<DatasetMemoria, ConcurrentHashMap<String, Risolutore>> =
        Collections.synchronizedMap(WeakHashMap())

    private fun risolutore(d: DatasetMemoria, fatti: UUID, dim: StarQueryBuilder.DimensioneUsata): Risolutore {
        val cache = risolutori.getOrPut(d) { ConcurrentHashMap() }
        return cache.getOrPut("$fatti|${dim.campo}|${dim.tabellaProprietaria}") { costruisciRisolutore(d, fatti, dim) }
    }

    private fun costruisciRisolutore(d: DatasetMemoria, fatti: UUID, dim: StarQueryBuilder.DimensioneUsata): Risolutore {
        val modello = d.modello
        modello.colonnaDi(fatti, dim.campo)?.let { col ->
            val ids = d.tabella(fatti).campo(col)?.ids ?: throw AggregatoNonSupportato("colonna '$col' non caricata")
            return Risolutore(ids, null)
        }
        val candidati = (listOfNotNull(dim.tabellaProprietaria) + d.grafo.tabelleCon(dim.campo))
            .distinct().filter { it != fatti }
        val padre = albero(d, fatti, consentiFatti = false)
        for (t in candidati) {
            val passaggi = percorso(padre, fatti, t) ?: continue
            if (!attraversabile(d, passaggi)) continue
            val col = modello.colonnaDi(t, dim.campo) ?: continue
            val valori = d.tabella(t).campo(col)?.ids ?: throw AggregatoNonSupportato("colonna '$col' non caricata")
            return componi(d, passaggi, valori)
        }
        val completo = albero(d, fatti, consentiFatti = true)
        if (candidati.any { it in completo }) {
            throw AggregatoNonSupportato("il campo '${dim.campo}' si raggiunge solo attraverso un altro Fatti")
        }
        return Risolutore(null, null)
    }

    private class Passaggio(val da: UUID, val a: UUID, val c: Collegamento)

    /** Come Costruttore.albero di StarQueryBuilder: visita in ampiezza dal Fatti lungo i collegamenti. */
    private fun albero(d: DatasetMemoria, radice: UUID, consentiFatti: Boolean): Map<UUID, Pair<UUID, Collegamento>> {
        val ruoli = d.modello.bozza.occorrenze.associate { it.id to it.ruolo }
        val risultato = mutableMapOf<UUID, Pair<UUID, Collegamento>>()
        val visti = mutableSetOf(radice)
        val coda = ArrayDeque<UUID>().apply { add(radice) }
        while (coda.isNotEmpty()) {
            val t = coda.removeFirst()
            d.grafo.collegamenti.filter { t in it.occorrenze }.forEach { c ->
                c.occorrenze
                    .filter { it !in visti && (consentiFatti || ruoli[it] != RuoloTabella.FATTI) }
                    .forEach { u ->
                        visti += u
                        risultato[u] = t to c
                        coda.add(u)
                    }
            }
        }
        return risultato
    }

    private fun percorso(padre: Map<UUID, Pair<UUID, Collegamento>>, radice: UUID, t: UUID): List<Passaggio>? {
        if (t !in padre) return null
        val passaggi = ArrayDeque<Passaggio>()
        var x = t
        while (x != radice) {
            val (da, c) = padre.getValue(x)
            passaggi.addFirst(Passaggio(da, x, c))
            x = da
        }
        return passaggi.toList()
    }

    /** Come StarQueryBuilder: un collegamento si attraversa solo se la colonna fisica è la stessa nelle due tabelle. */
    private fun attraversabile(d: DatasetMemoria, passaggi: List<Passaggio>): Boolean =
        passaggi.all { p ->
            p.c.campi.all { campo ->
                val da = d.modello.colonnaDi(p.da, campo)
                val a = d.modello.colonnaDi(p.a, campo)
                (da != null && da == a).also { ok ->
                    if (!ok) log.warn("Dataset {}: il collegamento '{}' non è attraversabile (colonne '{}' e '{}')", d.areaId, p.c.nome, da, a)
                }
            }
        }

    /** La catena di passaggi compressa in un array: codice della chiave del Fatti -> codice del valore. */
    private fun componi(d: DatasetMemoria, passaggi: List<Passaggio>, valori: IntArray): Risolutore {
        // Per ogni passaggio: codice della chiave -> prima riga presente della tabella di arrivo (LEFT ANY JOIN).
        val righe = passaggi.map { p ->
            val chiave = d.chiave(p.c, p.a) ?: throw AggregatoNonSupportato("chiave '${p.c.nome}' non caricata")
            primaRiga(d.tabella(p.a), chiave)
        }
        val chiaviPartenza = passaggi.map { p ->
            d.chiave(p.c, p.da) ?: throw AggregatoNonSupportato("chiave '${p.c.nome}' non caricata")
        }
        val primo = righe[0]
        val mappa = IntArray(primo.size) { k ->
            var rid = primo[k]
            var i = 1
            while (rid >= 0 && i < passaggi.size) {
                val codice = chiaviPartenza[i][rid]
                rid = if (codice < righe[i].size) righe[i][codice] else -1
                i++
            }
            if (rid < 0) 0 else valori[rid]
        }
        return Risolutore(chiaviPartenza[0], mappa)
    }

    /** Codice della chiave -> prima riga presente con quel codice (-1 = nessuna). */
    private fun primaRiga(t: TabellaMemoria, chiave: IntArray): IntArray {
        var max = 0
        val scorri = t.presenti.intIterator
        while (scorri.hasNext()) max = maxOf(max, chiave[scorri.next()])
        val r = IntArray(max + 1) { -1 }
        val ancora = t.presenti.intIterator
        while (ancora.hasNext()) {
            val rid = ancora.next()
            if (r[chiave[rid]] < 0) r[chiave[rid]] = rid
        }
        return r
    }

    // ================= Misure =================

    private class Misura(
        val tipo: TipoAggregazione,
        /** Copia numerica in milionesimi (SUM, AVG, MIN, MAX). */
        val numeri: LongArray?,
        val mancanti: BitSet?,
        /** Id del campo (COUNT con colonna, COUNT_DISTINCT). */
        val ids: IntArray?
    )

    private fun preparaMisura(t: TabellaMemoria, m: AreaMetrica): Misura {
        val col = m.colonnaFisica
        return when (m.tipoAggregazione) {
            TipoAggregazione.SUM, TipoAggregazione.AVG, TipoAggregazione.MIN, TipoAggregazione.MAX -> {
                val misura = col?.let { t.misura(it) }
                    ?: throw AggregatoNonSupportato("misura '${m.nome}': copia numerica non caricata")
                Misura(m.tipoAggregazione, misura.valori, mancanti(misura), null)
            }
            TipoAggregazione.COUNT -> Misura(m.tipoAggregazione, null, null, col?.let {
                t.campo(it)?.ids ?: throw AggregatoNonSupportato("misura '${m.nome}': colonna non caricata")
            })
            TipoAggregazione.COUNT_DISTINCT -> Misura(
                m.tipoAggregazione, null, null,
                col?.let { t.campo(it)?.ids } ?: throw AggregatoNonSupportato("misura '${m.nome}': colonna non caricata")
            )
        }
    }

    // ================= Gruppi e accumulatori =================

    /** I gruppi e gli accumulatori di un blocco di righe. [filtri]: righe di ogni misura (null = tutte). */
    private class Parziale(
        private val k: Int,
        private val misure: List<Misura>,
        private val filtri: Array<BitSet?>
    ) {
        private val m = misure.size
        private val livelli = Array(maxOf(0, k - 1)) { MappaLongInt() }
        private val finale = MappaLongInt()
        private val codici = ArrayList<IntArray>()
        private var somma = Array(m) { LongArray(16) }
        private var conta = Array(m) { LongArray(16) }
        private var minimo = Array(m) { LongArray(16) }
        private var massimo = Array(m) { LongArray(16) }
        private val distinti = Array(m) { ArrayList<BitSet?>() }
        private val riga = IntArray(k)

        val numeroGruppi: Int get() = codici.size
        fun codici(g: Int): IntArray = codici[g]

        /** Il gruppo dei codici in [riga] (creato se manca). */
        private fun gruppo(): Int {
            if (k == 0) return if (codici.isEmpty()) nuovo() else 0
            var g = riga[0].toLong()
            for (j in 1 until k) g = livelli[j - 1].prendi((g shl 32) or riga[j].toLong()).toLong()
            val n = codici.size
            val id = finale.prendi(g)
            return if (id == n) nuovo() else id
        }

        private fun nuovo(): Int {
            val g = codici.size
            codici.add(riga.copyOf())
            if (m > 0 && g >= somma[0].size) cresci(g)
            for (j in 0 until m) {
                minimo[j][g] = Long.MAX_VALUE
                massimo[j][g] = Long.MIN_VALUE
                distinti[j].add(null)
            }
            return g
        }

        private fun cresci(g: Int) {
            val n = maxOf(g + 1, somma[0].size * 2)
            somma = Array(m) { somma[it].copyOf(n) }
            conta = Array(m) { conta[it].copyOf(n) }
            minimo = Array(m) { minimo[it].copyOf(n) }
            massimo = Array(m) { massimo[it].copyOf(n) }
        }

        fun aggiungi(rid: Int, risolutori: List<Risolutore>) {
            for (i in 0 until k) riga[i] = risolutori[i].codice(rid)
            val g = gruppo()
            for (j in 0 until m) {
                val f = filtri[j]
                if (f != null && !f.get(rid)) continue
                val mi = misure[j]
                when (mi.tipo) {
                    TipoAggregazione.COUNT -> {
                        val ids = mi.ids
                        if (ids == null || ids[rid] != 0) conta[j][g]++
                    }
                    TipoAggregazione.COUNT_DISTINCT -> {
                        val v = mi.ids!![rid]
                        if (v != 0) {
                            val b = distinti[j][g] ?: BitSet().also { distinti[j][g] = it }
                            b.set(v)
                        }
                    }
                    else -> {
                        if (!mi.mancanti!!.get(rid)) {
                            val v = mi.numeri!![rid]
                            somma[j][g] += v
                            conta[j][g]++
                            if (v < minimo[j][g]) minimo[j][g] = v
                            if (v > massimo[j][g]) massimo[j][g] = v
                        }
                    }
                }
            }
        }

        fun unisci(altro: Parziale) {
            for (h in 0 until altro.numeroGruppi) {
                altro.codici[h].copyInto(riga)
                val g = gruppo()
                for (j in 0 until m) {
                    somma[j][g] += altro.somma[j][h]
                    conta[j][g] += altro.conta[j][h]
                    minimo[j][g] = minOf(minimo[j][g], altro.minimo[j][h])
                    massimo[j][g] = maxOf(massimo[j][g], altro.massimo[j][h])
                    altro.distinti[j][h]?.let { b -> (distinti[j][g] ?: BitSet().also { distinti[j][g] = it }).or(b) }
                }
            }
        }

        /** Il valore della misura [j] nel gruppo [g], come lo restituisce ClickHouse (mancante = 0). */
        fun valore(g: Int, j: Int): BigDecimal = when (misure[j].tipo) {
            TipoAggregazione.COUNT -> BigDecimal.valueOf(conta[j][g])
            TipoAggregazione.COUNT_DISTINCT -> BigDecimal.valueOf((distinti[j][g]?.cardinality() ?: 0).toLong())
            TipoAggregazione.SUM -> if (conta[j][g] == 0L) BigDecimal.ZERO else BigDecimal.valueOf(somma[j][g], 6)
            TipoAggregazione.MIN -> if (conta[j][g] == 0L) BigDecimal.ZERO else BigDecimal.valueOf(minimo[j][g], 6)
            TipoAggregazione.MAX -> if (conta[j][g] == 0L) BigDecimal.ZERO else BigDecimal.valueOf(massimo[j][g], 6)
            // toDecimal64(AVG(x), 4): media troncata a 4 decimali.
            TipoAggregazione.AVG -> if (conta[j][g] == 0L) BigDecimal.ZERO
            else BigDecimal.valueOf(somma[j][g], 6).divide(BigDecimal.valueOf(conta[j][g]), 4, RoundingMode.DOWN)
        }
    }

    /** Mappa long -> int a indirizzamento aperto, senza oggetti: [prendi] assegna id consecutivi. */
    private class MappaLongInt {
        private var chiavi = LongArray(64) { VUOTA }
        private var valori = IntArray(64)
        private var n = 0

        fun prendi(chiave: Long): Int {
            if (n * 2 >= chiavi.size) ingrandisci()
            var i = indice(chiave, chiavi.size)
            while (true) {
                val c = chiavi[i]
                if (c == chiave) return valori[i]
                if (c == VUOTA) {
                    chiavi[i] = chiave
                    valori[i] = n
                    return n++
                }
                i = (i + 1) and (chiavi.size - 1)
            }
        }

        private fun ingrandisci() {
            val vecchie = chiavi
            val vecchi = valori
            chiavi = LongArray(vecchie.size * 2) { VUOTA }
            valori = IntArray(vecchie.size * 2)
            for (i in vecchie.indices) {
                if (vecchie[i] == VUOTA) continue
                var j = indice(vecchie[i], chiavi.size)
                while (chiavi[j] != VUOTA) j = (j + 1) and (chiavi.size - 1)
                chiavi[j] = vecchie[i]
                valori[j] = vecchi[i]
            }
        }

        private fun indice(chiave: Long, dimensione: Int): Int {
            var h = chiave * -0x61c8864680b583ebL
            h = h xor (h ushr 32)
            return (h.toInt()) and (dimensione - 1)
        }

        private companion object {
            const val VUOTA = Long.MIN_VALUE
        }
    }

    // ================= Cache per tabella caricata =================

    private val parole: MutableMap<TabellaMemoria, LongArray> = Collections.synchronizedMap(WeakHashMap())
    private val vuoti: MutableMap<MisuraMemoria, BitSet> = Collections.synchronizedMap(WeakHashMap())

    private fun parolePresenti(t: TabellaMemoria): LongArray = parole.getOrPut(t) {
        val b = BitSet(t.numeroRighe)
        val scorri = t.presenti.intIterator
        while (scorri.hasNext()) b.set(scorri.next())
        b.toLongArray()
    }

    private fun mancanti(m: MisuraMemoria): BitSet = vuoti.getOrPut(m) {
        val b = BitSet()
        val scorri = m.mancanti.intIterator
        while (scorri.hasNext()) b.set(scorri.next())
        b
    }
}