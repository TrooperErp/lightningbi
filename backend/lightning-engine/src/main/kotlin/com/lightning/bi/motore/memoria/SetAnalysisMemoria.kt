package com.lightning.bi.motore.memoria

import com.lightning.bi.service.setanalysis.Elemento
import com.lightning.bi.service.setanalysis.EspressioneSet
import com.lightning.bi.service.setanalysis.Identificatore
import com.lightning.bi.service.setanalysis.Operatore
import com.lightning.bi.service.setanalysis.chiaveCampo

/** La selezione con cui si calcola una misura con condizione, in memoria. */
class SelezioneMemoria(
    val selezioni: Map<String, Set<Long>>,
    val condizioni: Map<String, CondizioneValori>
)

/**
 * Traduzione di una EspressioneSet per il motore in memoria, con le stesse regole di
 * SetAnalysisClickHouse: le condizioni sono filtri sui codici dei valori, letti dalle symbol table
 * in memoria (testo esatto, numero esatto, ricerca con * e ? senza maiuscole, confronti numerici).
 * Un codice che non è nella symbol table (0, "non definito") non è mai nell'insieme.
 */
object SetAnalysisMemoria {

    /**
     * @param selezioniUtente selezioni correnti per nome di campo, compresa quella forzata del section access
     * @param forzato il campo del section access: nessun modificatore lo può cambiare
     */
    fun traduci(
        espressione: EspressioneSet,
        selezioniUtente: Map<String, Set<Long>>,
        d: DatasetMemoria,
        forzato: String?
    ): SelezioneMemoria {
        val campi = d.campiDimensione.mapNotNull { (dimId, campo) ->
            d.simboli(dimId)?.let { chiaveCampo(campo) to (campo to it) }
        }.toMap()

        val selezioni: MutableMap<String, Set<Long>> = when (espressione.identificatore) {
            Identificatore.CORRENTI -> selezioniUtente.toMutableMap()
            // {1}: nessuna selezione, ma il section access resta.
            Identificatore.TUTTO -> selezioniUtente.filterKeys { it == forzato }.toMutableMap()
        }
        val condizioni = mutableMapOf<String, CondizioneValori>()

        espressione.modificatori.forEach { m ->
            val (campo, simboli) = campi[chiaveCampo(m.campo)]
                ?: throw IllegalArgumentException("Il campo '${m.campo}' della condizione non è nel dataset")
            if (campo == forzato) return@forEach // il section access non si tocca
            val inInsieme = insieme(simboli, m.elementi)
            val attuale = selezioni[campo]
            when (m.operatore) {
                Operatore.SOSTITUISCI -> {
                    selezioni.remove(campo)
                    condizioni[campo] = inInsieme
                }
                Operatore.INTERSECA -> condizioni[campo] = inInsieme
                Operatore.ESCLUDI -> condizioni[campo] = { id -> !inInsieme(id) }
                Operatore.AGGIUNGI -> if (attuale != null) {
                    selezioni.remove(campo)
                    condizioni[campo] = { id -> id.toLong() in attuale || inInsieme(id) }
                }
            }
        }
        return SelezioneMemoria(selezioni, condizioni)
    }

    /** I codici dell'insieme {…}: vuoto = nessuno. */
    private fun insieme(simboli: SimboliMemoria, elementi: List<Elemento>): CondizioneValori {
        if (elementi.isEmpty()) return { false }
        val testi = elementi.filterIsInstance<Elemento.Letterale>().map { it.valore }.toSet()
        val numeri = elementi.filterIsInstance<Elemento.Numero>().map { SimboliMemoria.milionesimi(it.valore) }.toSet()
        val ricerche = elementi.filterIsInstance<Elemento.Ricerca>().map { modello(it.modello) }
        val confronti = elementi.filterIsInstance<Elemento.Confronto>()
        return { id ->
            val t = simboli.testo(id)
            val n = simboli.numero(id)
            (t != null && t in testi) ||
                    (n != null && n in numeri) ||
                    (t != null && ricerche.any { it.matches(t) }) ||
                    (n != null && confronti.any { dentro(it, n) })
        }
    }

    /** Jolly di Qlik come ILIKE: * = qualsiasi sequenza, ? = un carattere, senza maiuscole, su tutto il testo. */
    private fun modello(m: String): Regex {
        val sb = StringBuilder()
        m.forEach { c ->
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> sb.append(Regex.escape(c.toString()))
            }
        }
        return Regex(sb.toString(), setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    }

    private fun dentro(c: Elemento.Confronto, n: Long): Boolean {
        c.minimo?.let { min ->
            val m = SimboliMemoria.milionesimi(min)
            if (if (c.minimoIncluso) n < m else n <= m) return false
        }
        c.massimo?.let { max ->
            val m = SimboliMemoria.milionesimi(max)
            if (if (c.massimoIncluso) n > m else n >= m) return false
        }
        return true
    }
}