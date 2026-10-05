package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.RuoloTabella
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Costruisce il FROM/JOIN di una query di aggregazione per UN Fatti del
 * dataset. Le righe da contare sono già decise dalle selezioni (bitmap delle
 * righe vive, GrafoDataset); i JOIN servono solo a leggere gli attributi dei
 * campi di raggruppamento (alias f = il Fatti, d0, d1, ... = le altre tabelle).
 *
 * Per ogni campo di raggruppamento:
 *  1. se il Fatti ha il campo, si usa la sua colonna (nessun JOIN);
 *  2. altrimenti si cerca una tabella che lo contiene raggiungibile dal Fatti
 *     lungo le associazioni, e si fa il JOIN lungo il percorso (anche a
 *     cascata);
 *  3. se non è raggiungibile il valore è 0, "non definito": è il caso di un
 *     campo che il Fatti non può raggiungere (misura non collegata, §2).
 *
 * Non si attraversano altri Fatti: collegare due Fatti sullo stesso campo
 * moltiplicherebbe le righe. Gli attributi di un altro Fatti sono "non definiti".
 *
 * LEFT ANY JOIN: LEFT tiene le righe senza corrispondenza (valore 0), ANY
 * prende una sola riga per chiave, così una tabella con righe duplicate non
 * moltiplica le somme.
 *
 * Il JOIN usa la stessa colonna fisica nelle due tabelle (gli id della stessa
 * symbol table). Le associazioni tra colonne dal nome fisico diverso
 * ("per valore") non sono ancora attraversabili (fase E5): il campo risulta
 * non raggiungibile e se ne avvisa nel log.
 */
@Service
class StarQueryBuilder {
    private val log = LoggerFactory.getLogger(StarQueryBuilder::class.java)

    class Plan(
        val fromClause: String,
        private val colonnePerDimensione: Map<UUID, String>
    ) {
        /** Espressione SQL del campo di raggruppamento (qualificata, o la costante 0). */
        fun dimColumn(dimensioneId: UUID): String = colonnePerDimensione.getValue(dimensioneId)

        /** Colonna di una metrica: le metriche stanno sempre sul Fatti. */
        fun metricColumn(colonna: String): String = "f.$colonna"
    }

    /** Un campo di raggruppamento: la dimensione, il nome del campo e la tabella proprietaria (se nota). */
    data class DimensioneUsata(val dimensioneId: UUID, val campo: String, val tabellaProprietaria: UUID?)

    fun plan(modello: ModelloDataset, fatti: UUID, dimensioni: List<DimensioneUsata>): Plan {
        val padre = alberoDa(modello, fatti)
        val alias = mutableMapOf(fatti to "f")
        val joins = mutableListOf<String>()

        /** Alias della tabella, aggiungendo i JOIN lungo il percorso; null se non raggiungibile. */
        fun raggiungi(tabella: UUID): String? {
            alias[tabella]?.let { return it }
            val (da, collegamento) = padre[tabella] ?: return null
            val aliasDa = raggiungi(da) ?: return null
            val condizioni = mutableListOf<String>()
            val aliasA = "d${joins.size}"
            for (campo in collegamento.campi) {
                val colDa = modello.colonnaDi(da, campo)
                val colA = modello.colonnaDi(tabella, campo)
                if (colDa == null || colA == null || colDa != colA) {
                    log.warn(
                        "Dataset {}: il collegamento '{}' non è attraversabile (colonne diverse: '{}' e '{}'): " +
                                "fase E5", modello.areaId, collegamento.nome, colDa, colA
                    )
                    return null
                }
                condizioni += "$aliasDa.${Naming.requirePhysical(colDa, "colonna")} = " +
                        "$aliasA.${Naming.requirePhysical(colA, "colonna")}"
            }
            joins += "LEFT ANY JOIN ${modello.fisica(tabella)} AS $aliasA ON ${condizioni.joinToString(" AND ")}"
            alias[tabella] = aliasA
            return aliasA
        }

        val colonne = mutableMapOf<UUID, String>()
        dimensioni.forEach { d ->
            val propria = modello.colonnaDi(fatti, d.campo)
            if (propria != null) {
                colonne[d.dimensioneId] = "f.${Naming.requirePhysical(propria, "colonna")}"
                return@forEach
            }
            val candidati = (listOfNotNull(d.tabellaProprietaria) + modello.grafo.tabelleCon(d.campo))
                .distinct()
                .filter { it != fatti }
            var risolto: String? = null
            for (t in candidati) {
                val a = raggiungi(t) ?: continue
                val col = modello.colonnaDi(t, d.campo) ?: continue
                risolto = "$a.${Naming.requirePhysical(col, "colonna")}"
                break
            }
            colonne[d.dimensioneId] = risolto ?: "toUInt32(0)"
        }

        val from = (listOf("${modello.fisica(fatti)} AS f") + joins).joinToString(" ")
        return Plan(from, colonne)
    }

    /** Per ogni tabella raggiungibile dal Fatti: da quale tabella e per quale collegamento. Non entra in altri Fatti. */
    private fun alberoDa(modello: ModelloDataset, fatti: UUID): Map<UUID, Pair<UUID, Collegamento>> {
        val ruoli = modello.bozza.occorrenze.associate { it.id to it.ruolo }
        val padre = mutableMapOf<UUID, Pair<UUID, Collegamento>>()
        val visti = mutableSetOf(fatti)
        val coda = ArrayDeque<UUID>().apply { add(fatti) }
        while (coda.isNotEmpty()) {
            val t = coda.removeFirst()
            modello.grafo.collegamenti.filter { t in it.occorrenze }.forEach { c ->
                c.occorrenze.filter { it !in visti && ruoli[it] != RuoloTabella.FATTI }.forEach { u ->
                    visti += u
                    padre[u] = t to c
                    coda.add(u)
                }
            }
        }
        return padre
    }
}