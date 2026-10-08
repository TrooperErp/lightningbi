package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.RuoloTabella
import org.slf4j.Logger
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
 *     lungo le associazioni SENZA passare da altri Fatti, e si fa il JOIN lungo
 *     il percorso (anche a cascata);
 *  3. altrimenti, come in Qlik, si attraversano anche gli altri Fatti: ogni riga
 *     del Fatti è associata a TUTTI i valori del campo raggiungibili attraverso
 *     la chiave di collegamento. Si unisce una sottoquery DISTINCT (chiave,
 *     valore), limitata alle righe vive del Fatti attraversato: così ogni riga
 *     conta una sola volta per valore, senza moltiplicare le somme. Una riga può
 *     comparire sotto più valori; i totali non sono la somma delle righe (si
 *     calcolano con query separate);
 *  4. se il campo non è raggiungibile in nessun modo il valore è 0, "non
 *     definito".
 *
 * LEFT ANY JOIN (tabelle senza altri Fatti): LEFT tiene le righe senza
 * corrispondenza (valore 0), ANY prende una sola riga per chiave, così una
 * tabella con righe duplicate non moltiplica le somme.
 *
 * Il JOIN usa la stessa colonna fisica nelle due tabelle (gli id della stessa
 * symbol table). Un collegamento tra colonne dal nome fisico diverso non è
 * attraversabile: il campo risulta non raggiungibile e se ne avvisa nel log.
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

    /**
     * @param selezioni selezioni per nome di campo: servono a limitare alle righe vive i Fatti
     *   attraversati per raggiungere un campo di un altro Fatti
     */
    fun plan(
        modello: ModelloDataset,
        fatti: UUID,
        dimensioni: List<DimensioneUsata>,
        selezioni: Map<String, Set<Long>> = emptyMap()
    ): Plan {
        val costruttore = Costruttore(log, modello, fatti, setOf(fatti), selezioni)
        val colonne = dimensioni.associate { d ->
            d.dimensioneId to (costruttore.colonna(d.campo, d.tabellaProprietaria) ?: "toUInt32(0)")
        }
        return Plan(costruttore.from(), colonne)
    }
}

/**
 * Costruisce il FROM di una query con radice in un Fatti. Per i campi di altri
 * Fatti crea una sottoquery per ogni Fatti attraversato, con un altro
 * Costruttore che ha per radice quel Fatti.
 */
private class Costruttore(
    private val log: Logger,
    private val modello: ModelloDataset,
    private val radice: UUID,
    /** I Fatti già nella catena (compresa la radice): non si rientra. */
    private val escluse: Set<UUID>,
    private val selezioni: Map<String, Set<Long>>
) {
    /** Un Fatti attraversato: la sottoquery (chiave, valori) e le espressioni che porta. */
    private class Gruppo(
        val alias: String,
        val aliasDa: String,
        val fatti: UUID,
        val chiavi: List<String>,
        val interno: Costruttore
    ) {
        val espressioni = mutableListOf<String>()
    }

    private val ruoli = modello.bozza.occorrenze.associate { it.id to it.ruolo }

    /** Per ogni tabella raggiungibile senza passare da altri Fatti: da quale tabella e per quale collegamento. */
    private val padre: Map<UUID, Pair<UUID, Collegamento>> = albero(consentiFatti = false)

    /** Come [padre], ma attraversando anche gli altri Fatti. */
    private val completo: Map<UUID, Pair<UUID, Collegamento>> by lazy { albero(consentiFatti = true) }

    private val alias = mutableMapOf(radice to "f")
    private val joins = mutableListOf<String>()
    private val gruppi = LinkedHashMap<UUID, Gruppo>()

    /** Espressione SQL del campo, o null se non è raggiungibile. */
    fun colonna(campo: String, proprietaria: UUID?): String? {
        modello.colonnaDi(radice, campo)?.let { return "f.${Naming.requirePhysical(it, "colonna")}" }

        val candidati = (listOfNotNull(proprietaria) + modello.grafo.tabelleCon(campo))
            .distinct()
            .filter { it != radice }

        for (t in candidati) {
            val a = raggiungi(t) ?: continue
            val col = modello.colonnaDi(t, campo) ?: continue
            return "$a.${Naming.requirePhysical(col, "colonna")}"
        }
        for (t in candidati) {
            coppiaVia(t, campo)?.let { return it }
        }
        return null
    }

    /** Il FROM completo: va chiamato dopo aver chiesto tutte le colonne. */
    fun from(): String {
        val parti = mutableListOf("${modello.fisica(radice)} AS f")
        parti += joins
        gruppi.values.filter { it.espressioni.isNotEmpty() }.forEach { parti += render(it) }
        return parti.joinToString(" ")
    }

    private fun albero(consentiFatti: Boolean): Map<UUID, Pair<UUID, Collegamento>> {
        val risultato = mutableMapOf<UUID, Pair<UUID, Collegamento>>()
        val visti = mutableSetOf(radice)
        val coda = ArrayDeque<UUID>().apply { add(radice) }
        while (coda.isNotEmpty()) {
            val t = coda.removeFirst()
            modello.grafo.collegamenti.filter { t in it.occorrenze }.forEach { c ->
                c.occorrenze
                    .filter { it !in visti && it !in escluse && (consentiFatti || ruoli[it] != RuoloTabella.FATTI) }
                    .forEach { u ->
                        visti += u
                        risultato[u] = t to c
                        coda.add(u)
                    }
            }
        }
        return risultato
    }

    /** Alias della tabella, aggiungendo i JOIN lungo il percorso; null se non raggiungibile. */
    private fun raggiungi(tabella: UUID): String? {
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
                    "Dataset {}: il collegamento '{}' non è attraversabile (colonne diverse: '{}' e '{}')",
                    modello.areaId, collegamento.nome, colDa, colA
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

    /** Il campo di [tabella] raggiunto attraversando un altro Fatti: espressione sulla sottoquery, o null. */
    private fun coppiaVia(tabella: UUID, campo: String): String? {
        if (tabella !in completo) return null
        // La catena dalla radice alla tabella; il primo Fatti che si incontra è quello da attraversare.
        val catena = ArrayDeque<UUID>()
        var x = tabella
        while (x != radice) {
            catena.addFirst(x)
            x = completo.getValue(x).first
        }
        val g = catena.firstOrNull { ruoli[it] == RuoloTabella.FATTI } ?: return null
        val gruppo = gruppi[g] ?: creaGruppo(g) ?: return null
        val espressione = gruppo.interno.colonna(campo, tabella) ?: return null
        var indice = gruppo.espressioni.indexOf(espressione)
        if (indice < 0) {
            gruppo.espressioni += espressione
            indice = gruppo.espressioni.size - 1
        }
        return "${gruppo.alias}.dv$indice"
    }

    private fun creaGruppo(g: UUID): Gruppo? {
        val (da, collegamento) = completo.getValue(g)
        val aliasDa = raggiungi(da) ?: return null
        val chiavi = mutableListOf<String>()
        for (campo in collegamento.campi) {
            val colDa = modello.colonnaDi(da, campo)
            val colG = modello.colonnaDi(g, campo)
            if (colDa == null || colG == null || colDa != colG) {
                log.warn(
                    "Dataset {}: il collegamento '{}' verso un altro Fatti non è attraversabile (colonne diverse: '{}' e '{}')",
                    modello.areaId, collegamento.nome, colDa, colG
                )
                return null
            }
            chiavi += Naming.requirePhysical(colDa, "colonna")
        }
        val gruppo = Gruppo(
            alias = "p${gruppi.size}",
            aliasDa = aliasDa,
            fatti = g,
            chiavi = chiavi,
            interno = Costruttore(log, modello, g, escluse + g, selezioni)
        )
        gruppi[g] = gruppo
        return gruppo
    }

    private fun render(g: Gruppo): String {
        val chiaviSql = g.chiavi.mapIndexed { i, c -> "f.$c AS k$i" }
        val valoriSql = g.espressioni.mapIndexed { i, e -> "$e AS dv$i" }
        val viva = modello.grafo.righeVive(g.fatti, selezioni)
        val filtro = if (viva == null) "" else " WHERE bitmapContains($viva, f.${Naming.RID_COLUMN})"
        val on = g.chiavi.mapIndexed { i, c -> "${g.aliasDa}.$c = ${g.alias}.k$i" }.joinToString(" AND ")
        return "LEFT JOIN (SELECT DISTINCT ${(chiaviSql + valoriSql).joinToString(", ")} " +
                "FROM ${g.interno.from()}$filtro) AS ${g.alias} ON $on"
    }
}