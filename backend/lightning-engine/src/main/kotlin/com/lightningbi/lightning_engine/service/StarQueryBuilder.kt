package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.AreaDimensione
import com.lightningbi.lightning_engine.model.RuoloTabella
import com.lightningbi.lightning_engine.repository.ImportedTableRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Costruisce la parte FROM/JOIN delle query su un dataset a schema a stella
 * e risolve il nome (qualificato) delle colonne: tabella Fatti (alias "f")
 * + una tabella per ogni Dimensione realmente usata dalla richiesta
 * (alias d0, d1, ...). Le Dimensioni non usate NON entrano nella query.
 *
 * Classe condivisa da AggregateService e BitmapIndexBuilder, così la
 * logica dei JOIN vive in un posto solo.
 *
 * REGOLE DEL JOIN
 * - LEFT ANY JOIN: LEFT tiene le righe dei Fatti senza corrispondenza
 *   (chiave 0 = "non definito"), come oggi una dimensione nulla vale 0;
 *   ANY prende una sola riga della Dimensione per chiave, così una
 *   Dimensione con righe duplicate non moltiplica le somme dei Fatti.
 * - Condizione: chiave scelta nel wizard, PIÙ ogni altra colonna chiave
 *   della Dimensione (isChiave, es. CODICE_DITTA) che esiste anche sui
 *   Fatti come dimensione. Serve perché _KEYCLIENTE da sola non è
 *   univoca fra ditte diverse. Questo presuppone che il nome della
 *   dimensione sui Fatti coincida con il nome della colonna (stessa
 *   symbol table): lo garantisce il wizard.
 */
@Service
class StarQueryBuilder(
    private val importedTableRepository: ImportedTableRepository
) {
    private val identificatoreFisico = Regex("^[a-z][a-z0-9_]*$")

    /**
     * @param fromClause testo da mettere dopo FROM (Fatti + JOIN sulle Dimensioni usate)
     */
    class Plan(
        val fromClause: String,
        private val colonnePerDimensione: Map<UUID, String>,
        private val aliasFatti: String
    ) {
        /** Colonna della dimensione, qualificata (es. "d0.descrizione") nello schema a stella. */
        fun dimColumn(dimensioneId: UUID): String? = colonnePerDimensione[dimensioneId]

        /** Colonna di una metrica: le metriche stanno sempre sui Fatti. */
        fun metricColumn(colonna: String): String = "$aliasFatti.$colonna"
    }

    /**
     * @param dimensioniUsate dimensioni presenti in group by, colonne o selezioni
     * @param dimensioni tutte le AreaDimensione dell'area
     */
    fun plan(
        areaId: UUID,
        dimensioniUsate: Set<UUID>,
        dimensioni: List<AreaDimensione>
    ): Plan {
        val tabelle = importedTableRepository.findByArea(areaId)
        val fatti = tabelle.singleOrNull { it.ruolo == RuoloTabella.FATTI }
            ?: error("L'area $areaId deve avere esattamente una tabella Fatti importata")
        val tabellePerId = tabelle.associateBy { it.id }
        val aliasFatti = "f"

        val colonneFattiComeDimensione = dimensioni
            .filter { it.importedTableId == null || it.importedTableId == fatti.id }
            .map { it.colonnaFisica }
            .toSet()

        val usate = dimensioni.filter { it.dimensioneId in dimensioniUsate }
        val idTabelleDimensione = usate
            .mapNotNull { it.importedTableId }
            .filter { it != fatti.id }
            .distinct()
        val aliasPerTabella = idTabelleDimensione.withIndex().associate { (i, id) -> id to "d$i" }

        val joins = idTabelleDimensione.map { id ->
            val t = tabellePerId[id] ?: error("Tabella importata $id non trovata per l'area $areaId")
            val alias = aliasPerTabella.getValue(id)
            val chiave = t.colonnaChiave?.let { Naming.column(it) }
                ?: error("La tabella '${t.nomeLogico}' non ha una chiave di JOIN")

            val chiaviExtra = importedTableRepository.findColumnsByTable(id)
                .filter { it.isChiave }
                .map { Naming.column(it.nome) }
                .filter { it != chiave && it in colonneFattiComeDimensione }

            val condizioni = (listOf(chiave) + chiaviExtra).map { col ->
                fisico(col, "colonna di JOIN")
                "$aliasFatti.$col = $alias.$col"
            }
            "LEFT ANY JOIN ${fisico(t.tabellaFisica, "tabella")} AS $alias ON ${condizioni.joinToString(" AND ")}"
        }

        val colonne = usate.associate { ad ->
            val alias = ad.importedTableId
                ?.takeIf { it != fatti.id }
                ?.let { aliasPerTabella.getValue(it) }
                ?: aliasFatti
            ad.dimensioneId to "$alias.${fisico(ad.colonnaFisica, "colonna")}"
        }

        val from = (listOf("${fisico(fatti.tabellaFisica, "tabella")} AS $aliasFatti") + joins).joinToString(" ")
        return Plan(from, colonne, aliasFatti)
    }

    /**
     * Verifica che l'identificatore sia sicuro da inserire in SQL. Non usa
     * Naming.slug: i nomi delle tabelle importate contengono un doppio
     * underscore (<motore>_<db>__<nome>) che slug() collasserebbe.
     */
    private fun fisico(valore: String, cosa: String): String {
        require(identificatoreFisico.matches(valore)) { "Identificatore $cosa non valido: '$valore'" }
        return valore
    }
}