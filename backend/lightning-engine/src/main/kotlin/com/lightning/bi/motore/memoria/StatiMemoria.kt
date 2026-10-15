package com.lightning.bi.motore.memoria

import com.lightning.bi.service.CalendarioService
import com.lightning.bi.service.ContatoreCampo
import com.lightning.bi.service.SezioneAccessoService
import com.lightning.bi.service.StatoValore
import com.lightning.bi.service.ValoreFiltro
import org.springframework.stereotype.Service
import java.util.BitSet
import java.util.Collections
import java.util.UUID
import java.util.WeakHashMap

/**
 * Stati dei valori in memoria (verde / bianco / grigio), contatori, elenchi, possibili e
 * conflitti: la stessa logica di FiltriService e di GrafoDataset.sqlStatoCampo, calcolata sopra
 * InferenzaMemoria (come Qlik: si scorrono le righe vive e si segnano i codici).
 *
 * Stato di un campo:
 * - campo-collegamento semplice: dominio = valori del campo in tutte le tabelle che lo hanno;
 *   possibile se sta nel messaggio di OGNI tabella vincolata del collegamento;
 * - altro campo: la prima tabella che lo contiene; possibile se compare nelle sue righe vive.
 * Un campo selezionato si calcola senza la propria selezione. L'universo del section access
 * riduce il dominio ai valori delle righe permesse.
 */
@Service
class StatiMemoria(
    private val calendarioService: CalendarioService,
    private val poolMotore: PoolMotore
) {

    /** Dominio (tutti i valori) e verdi (i possibili) di un campo, come codici. */
    private class Stato(val dominio: BitSet, val verdi: BitSet)

    /** Le selezioni di un clic, con il section access applicato, e le inferenze riusate tra i campi. */
    private inner class Contesto(
        val d: DatasetMemoria,
        selections: Map<UUID, Set<Long>>,
        vincolo: SezioneAccessoService.Vincolo?
    ) {
        val inferenza = InferenzaMemoria(d, poolMotore)
        val forzato: String? = vincolo?.let {
            d.campiDimensione[it.dimensioneId] ?: throw SecurityException("Il campo azienda non è nell'indice del dataset")
        }
        val selezioni: Map<String, Set<Long>>
        val universo: Map<String, Set<Long>>

        init {
            val effettive = if (vincolo == null) selections else selections + (vincolo.dimensioneId to vincolo.ids)
            selezioni = effettive.filterValues { it.isNotEmpty() }
                .mapNotNull { (id, valori) -> d.campiDimensione[id]?.let { it to valori } }
                .toMap()
            universo = if (vincolo == null || forzato == null) emptyMap() else mapOf(forzato to vincolo.ids)
        }

        /** Un'inferenza per ogni campo omesso (null = nessuno), con le selezioni del clic. */
        private val perOmesso = HashMap<String?, InferenzaMemoria.Stato>()
        private val statoUniverso: InferenzaMemoria.Stato? by lazy {
            if (universo.isEmpty()) null else inferenza.calcola(universo)
        }

        private fun inferenza(sel: Map<String, Set<Long>>, omesso: String?): InferenzaMemoria.Stato =
            if (sel === selezioni) perOmesso.getOrPut(omesso) { inferenza.calcola(sel, omesso) }
            else inferenza.calcola(sel, omesso)

        fun stato(campo: String, sel: Map<String, Set<Long>>, omesso: String?): Stato {
            val grafo = d.grafo
            val s = inferenza(sel, omesso)
            val u = statoUniverso

            val semplice = grafo.collegamentoSemplice(campo)
            if (semplice != null) {
                val dominio = BitSet()
                grafo.tabelleCon(campo).forEach { t -> d.indice(t, campo)?.let { dominio.or(dominioDi(it)) } }
                if (u != null) {
                    val perTabella = semplice.occorrenze.map { t -> u.insieme(semplice, t) }
                    if (perTabella.none { it == null }) {
                        val ammessi = BitSet()
                        perTabella.forEach { ammessi.or(it!!) }
                        dominio.and(ammessi)
                    }
                }
                val verdi = dominio.clone() as BitSet
                semplice.occorrenze.forEach { t -> s.insieme(semplice, t)?.let { verdi.and(it) } }
                return Stato(dominio, verdi)
            }

            val riferimento = grafo.tabelleCon(campo).firstOrNull()
                ?: error("Il campo '$campo' non è in nessuna tabella del dataset")
            val indice = d.indice(riferimento, campo) ?: return Stato(BitSet(), BitSet())
            val colonna = d.colonna(riferimento, campo)?.ids ?: return Stato(BitSet(), BitSet())

            val dominio = dominioDi(indice).clone() as BitSet
            u?.vive(riferimento)?.let { vivaU -> dominio.and(inferenza.codici(riferimento, colonna, vivaU)) }
            val viva = s.vive(riferimento)
            val verdi = if (viva == null) dominio.clone() as BitSet
            else inferenza.codici(riferimento, colonna, viva).apply { and(dominio) }
            return Stato(dominio, verdi)
        }

        /** Lo stato del campo con le selezioni del clic (senza la sua, se ne ha una). */
        fun stato(campo: String): Stato =
            stato(campo, selezioni, if (selezioni[campo].orEmpty().isNotEmpty()) campo else null)
    }

    // ================= API =================

    fun contatori(
        d: DatasetMemoria,
        selections: Map<UUID, Set<Long>>,
        dimensioni: Collection<UUID>?,
        vincolo: SezioneAccessoService.Vincolo?
    ): Map<UUID, ContatoreCampo> {
        val c = Contesto(d, selections, vincolo)
        val richieste = (dimensioni ?: d.campiDimensione.keys).filter { it in d.campiDimensione }
        return richieste.associateWith { dimId ->
            val campo = d.campiDimensione.getValue(dimId)
            val s = c.stato(campo)
            val scelti = c.selezioni[campo].orEmpty()
            val selezionati = scelti.count { it <= Int.MAX_VALUE && s.dominio.get(it.toInt()) }.toLong()
            ContatoreCampo(s.verdi.cardinality().toLong(), s.dominio.cardinality().toLong(), selezionati)
        }
    }

    fun valori(
        d: DatasetMemoria,
        selections: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        ricerca: String?,
        offset: Int,
        limite: Int,
        vincolo: SezioneAccessoService.Vincolo?
    ): List<ValoreFiltro> {
        val c = Contesto(d, selections, vincolo)
        val campo = d.campiDimensione[dimensioneId] ?: return emptyList()
        val simboli = d.simboli(dimensioneId) ?: return emptyList()
        val s = c.stato(campo)
        val scelti = c.selezioni[campo].orEmpty()
        val ordinati = inOrdine(filtra(s.dominio, simboli, ricerca), simboli)
        val fine = minOf(ordinati.size.toLong(), offset.toLong() + limite).toInt()
        if (offset >= fine) return emptyList()
        return (offset until fine).map { i ->
            val id = ordinati[i]
            val testo = simboli.testo(id)
            ValoreFiltro(
                id = id.toLong(),
                etichetta = if (testo == null) "(non definito)" else calendarioService.formatta(testo),
                stato = when {
                    id.toLong() in scelti -> StatoValore.SELEZIONATO
                    s.verdi.get(id) -> StatoValore.POSSIBILE
                    else -> StatoValore.ESCLUSO
                }
            )
        }
    }

    fun numeroValori(
        d: DatasetMemoria,
        selections: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        ricerca: String?,
        vincolo: SezioneAccessoService.Vincolo?
    ): Int {
        val c = Contesto(d, selections, vincolo)
        val campo = d.campiDimensione[dimensioneId] ?: return 0
        val simboli = d.simboli(dimensioneId) ?: return 0
        return filtra(c.stato(campo).dominio, simboli, ricerca).cardinality()
    }

    fun idPossibili(
        d: DatasetMemoria,
        selections: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        vincolo: SezioneAccessoService.Vincolo?
    ): Set<Long> {
        val c = Contesto(d, selections, vincolo)
        val campo = d.campiDimensione[dimensioneId] ?: return emptySet()
        val verdi = c.stato(campo).verdi
        val r = HashSet<Long>(verdi.cardinality())
        var i = verdi.nextSetBit(0)
        while (i >= 0) {
            r.add(i.toLong())
            i = verdi.nextSetBit(i + 1)
        }
        return r
    }

    /** Come FiltriService.conflitti: il minimo di selezioni da togliere (una, due, tre; altrimenti tutte). */
    fun conflitti(
        d: DatasetMemoria,
        selections: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        valoreId: Long,
        vincolo: SezioneAccessoService.Vincolo?
    ): Set<UUID> {
        val c = Contesto(d, selections, vincolo)
        val campo = d.campiDimensione[dimensioneId] ?: return emptySet()
        val altri = c.selezioni.keys.filter { it != campo && it != c.forzato }.sorted()
        if (altri.isEmpty()) return emptySet()

        fun possibile(sel: Map<String, Set<Long>>): Boolean = c.stato(campo, sel, campo).verdi.get(valoreId.toInt())
        val dimensionePerCampo = d.campiDimensione.entries.associate { it.value to it.key }
        fun comeDimensioni(nomi: Collection<String>): Set<UUID> = nomi.mapNotNull { dimensionePerCampo[it] }.toSet()

        if (possibile(c.selezioni)) return emptySet()
        for (taglia in 1..minOf(3, altri.size)) {
            for (combinazione in combinazioni(altri, taglia)) {
                if (possibile(c.selezioni - combinazione.toSet())) return comeDimensioni(combinazione)
            }
        }
        return comeDimensioni(altri)
    }

    // ================= interni =================

    /** I codici presenti nell'indice, calcolati una volta per indice caricato. Non modificare il risultato. */
    private val domini: MutableMap<IndiceValori, BitSet> = Collections.synchronizedMap(WeakHashMap())

    private fun dominioDi(indice: IndiceValori): BitSet = domini.getOrPut(indice) {
        val b = BitSet()
        for (i in 0 until indice.numeroValori) b.set(indice.valore(i))
        b
    }

    /** I codici nell'ordine del campo (SimboliMemoria.rango). */
    private fun inOrdine(codici: BitSet, simboli: SimboliMemoria): IntArray {
        val coppie = LongArray(codici.cardinality())
        var k = 0
        var id = codici.nextSetBit(0)
        while (id >= 0) {
            coppie[k++] = (simboli.rango(id).toLong() shl 32) or (id.toLong() and 0xFFFFFFFFL)
            id = codici.nextSetBit(id + 1)
        }
        coppie.sort()
        return IntArray(coppie.size) { (coppie[it] and 0xFFFFFFFFL).toInt() }
    }

    /** La ricerca di FiltriService: testo grezzo, senza maiuscole; vuota = nessun filtro. */
    private fun filtra(dominio: BitSet, simboli: SimboliMemoria, ricerca: String?): BitSet {
        val testo = ricerca?.trim().orEmpty()
        if (testo.isEmpty()) return dominio
        val minuscolo = testo.lowercase()
        val r = BitSet()
        var id = dominio.nextSetBit(0)
        while (id >= 0) {
            if (simboli.contiene(id, minuscolo)) r.set(id)
            id = dominio.nextSetBit(id + 1)
        }
        return r
    }

    private fun combinazioni(elementi: List<String>, taglia: Int): Sequence<List<String>> = sequence {
        if (taglia == 0) {
            yield(emptyList())
            return@sequence
        }
        for (i in elementi.indices) {
            for (resto in combinazioni(elementi.drop(i + 1), taglia - 1)) yield(listOf(elementi[i]) + resto)
        }
    }
}