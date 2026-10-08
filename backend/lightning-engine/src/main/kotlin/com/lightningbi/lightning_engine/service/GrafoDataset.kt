package com.lightningbi.lightning_engine.service

import java.util.UUID

/**
 * Un collegamento tra tabelle del dataset: un campo condiviso, oppure una
 * chiave composta (due o più campi condivisi dalle STESSE tabelle, la
 * "chiave sintetica" di Qlik). Nell'indice (ch_lbi_idx) c'è una voce con il
 * nome del collegamento; per una chiave composta il valore è l'hash a 64 bit
 * della combinazione degli id dei campi.
 */
data class Collegamento(
    val nome: String,
    val occorrenze: List<UUID>,
    val campi: List<String>
) {
    val composto: Boolean get() = campi.size > 1
}

/**
 * Grafo di un dataset (tabelle e collegamenti) e generatore delle query di
 * propagazione sull'indice per tabella, come fa la logical inference di Qlik:
 * la selezione si propaga da una tabella alle altre lungo le associazioni.
 *
 * Il modello validato non ha loop (DatasetBozza.valida), quindi è un albero e
 * la propagazione è una ricorsione sui collegamenti. Kotlin puro: produce solo
 * testo SQL, non lo esegue.
 *
 * LE SELEZIONI sono per NOME di campo: campo -> id dei valori scelti. Una
 * selezione su un campo si applica in ogni tabella che lo contiene. [omesso]
 * è il campo la cui selezione NON conta (omitSelf): i valori di un campo si
 * giudicano rispetto alle selezioni di tutti gli altri.
 *
 * RIGHE VIVE di una tabella: la bitmap (di lbi_rid) delle righe compatibili
 * con le selezioni, quelle locali e i messaggi dei collegamenti. Una tabella
 * che nessuna selezione tocca, direttamente o dai vicini, non ha vincoli: la
 * funzione restituisce null (= tutte le righe).
 *
 * STATO DI UN CAMPO ([sqlStatoCampo]): la query restituisce (valore_id, verde).
 *  - Campo-collegamento semplice: dominio = unione dei valori di tutte le
 *    tabelle che lo contengono; verde se, per ogni tabella vincolata, il valore
 *    compare nelle sue righe vive calcolate senza passare dal collegamento
 *    stesso. Nessuna tabella vincolata = tutti verdi.
 *  - Campo normale o membro di una chiave composta: si usa la prima tabella che
 *    lo contiene; verde se compare nelle sue righe vive complete. Per i membri
 *    di chiave composta il dominio è quello di quella tabella (approssimazione).
 */
class GrafoDataset private constructor(
    areaId: UUID,
    val tabelle: List<UUID>,
    val collegamenti: List<Collegamento>,
    private val campiPerTabella: Map<UUID, Set<String>>
) {
    private val area = "toUUID('$areaId')"

    companion object {
        const val INDICE = "ch_lbi_idx"
        private const val PROFONDITA_MAX = 64

        fun da(areaId: UUID, bozza: DatasetBozza): GrafoDataset {
            val ordine = bozza.occorrenze.map { it.id }
            val inclusi = bozza.campi().filter { !it.escluso }
            val perTabella = inclusi.groupBy { it.occorrenzaId }
                .mapValues { (_, v) -> v.map { it.nomeCampo }.toSet() }

            val collegamenti = bozza.associazioniAttive()
                .groupBy { it.occorrenze.toSet() }
                .map { (occorrenze, assoc) ->
                    val nomi = assoc.map { it.nomeCampo }.sorted()
                    val nome = if (nomi.size == 1) nomi.first() else "chiave__" + nomi.joinToString("__")
                    Collegamento(
                        nome = Naming.requirePhysical(nome, "collegamento"),
                        occorrenze = ordine.filter { it in occorrenze },
                        campi = nomi
                    )
                }
                .sortedBy { it.nome }

            return GrafoDataset(areaId, ordine, collegamenti, perTabella)
        }
    }

    // ---------- struttura ----------

    /** Le tabelle che contengono il campo, nell'ordine del dataset. */
    fun tabelleCon(campo: String): List<UUID> =
        tabelle.filter { campo in (campiPerTabella[it] ?: emptySet()) }

    /** Il collegamento che coincide con un campo condiviso da solo (non membro di una chiave composta). */
    fun collegamentoSemplice(campo: String): Collegamento? =
        collegamenti.firstOrNull { !it.composto && it.campi.first() == campo }

    private fun collegamentiDi(tabella: UUID): List<Collegamento> =
        collegamenti.filter { tabella in it.occorrenze }

    // ---------- righe vive ----------

    /**
     * Espressione SQL (bitmap) delle righe vive di [tabella], con tutti i
     * collegamenti, oppure null se non ha vincoli. Serve anche alle
     * aggregazioni: si usa come filtro bitmapContains(espressione, lbi_rid).
     */
    fun righeVive(tabella: UUID, selezioni: Map<String, Set<Long>>, omesso: String? = null): String? =
        vive(tabella, selezioni, omesso, null, 0)

    private fun vive(
        tabella: UUID,
        selezioni: Map<String, Set<Long>>,
        omesso: String?,
        escludi: Collegamento?,
        profondita: Int
    ): String? {
        check(profondita <= PROFONDITA_MAX) { "Il modello del dataset ha un loop tra le tabelle" }
        val termini = mutableListOf<String>()

        // Selezioni sui campi di questa tabella.
        val campiTabella = campiPerTabella[tabella] ?: emptySet()
        selezioni.forEach { (campo, valori) ->
            if (campo != omesso && valori.isNotEmpty() && campo in campiTabella) {
                termini += "(SELECT groupBitmapOrState(righe) FROM $INDICE WHERE area_id = $area AND tabella_id = ${uuid(tabella)} " +
                        "AND campo = '${nome(campo)}' AND valore_id IN (${valori.joinToString(",")}))"
            }
        }

        // Messaggi dei collegamenti (tranne quello da escludere).
        collegamentiDi(tabella).filter { it != escludi }.forEach { c ->
            val ammessi = valoriAmmessi(c, tabella, selezioni, omesso, profondita + 1) ?: return@forEach
            termini += "(SELECT groupBitmapOrState(righe) FROM $INDICE WHERE area_id = $area AND tabella_id = ${uuid(tabella)} " +
                    "AND campo = '${nome(c.nome)}' AND valore_id IN ($ammessi))"
        }
        return and(termini)
    }

    /**
     * Sottoquery (valore_id) dei valori del collegamento ammessi per [per], in
     * base alle ALTRE tabelle del collegamento. Null se nessuna di esse è
     * vincolata.
     */
    private fun valoriAmmessi(
        c: Collegamento,
        per: UUID,
        selezioni: Map<String, Set<Long>>,
        omesso: String?,
        profondita: Int
    ): String? {
        val insiemi = c.occorrenze.filter { it != per }.mapNotNull { altra ->
            insiemeDi(c, altra, selezioni, omesso, profondita)
        }
        return when (insiemi.size) {
            0 -> null
            1 -> insiemi.first()
            else -> insiemi.joinToString(" INTERSECT DISTINCT ") { "($it)" }
        }
    }

    /** Valori del collegamento presenti nelle righe vive di [tabella] (senza passare dal collegamento). Null se non vincolata. */
    private fun insiemeDi(
        c: Collegamento,
        tabella: UUID,
        selezioni: Map<String, Set<Long>>,
        omesso: String?,
        profondita: Int
    ): String? {
        val viva = vive(tabella, selezioni, omesso, c, profondita) ?: return null
        return "SELECT valore_id FROM $INDICE WHERE area_id = $area AND tabella_id = ${uuid(tabella)} " +
                "AND campo = '${nome(c.nome)}' AND bitmapAndCardinality(righe, $viva) > 0"
    }

    // ---------- stato di un campo ----------

    /**
     * Query che restituisce (valore_id, verde) per ogni valore del dominio del
     * campo. [omesso] è di solito il campo stesso, se ha una selezione.
     */
    /**
     * Query che restituisce (valore_id, verde) per ogni valore del dominio del
     * campo. [omesso] è di solito il campo stesso, se ha una selezione.
     *
     * [universo] sono le selezioni permanenti (section access): il dominio del
     * campo si riduce ai valori che compaiono nelle righe permesse, quindi i
     * valori che esistono solo fuori dall'universo non compaiono nemmeno come
     * esclusi. Vuoto = nessuna riduzione.
     */
    fun sqlStatoCampo(
        campo: String,
        selezioni: Map<String, Set<Long>>,
        omesso: String?,
        universo: Map<String, Set<Long>> = emptyMap()
    ): String {
        val semplice = collegamentoSemplice(campo)
        if (semplice != null) {
            val insiemi = semplice.occorrenze.mapNotNull { t -> insiemeDi(semplice, t, selezioni, omesso, 0) }
            val condizione = if (insiemi.isEmpty()) "1" else insiemi.joinToString(" AND ") { "valore_id IN ($it)" }
            val inUniverso = if (universo.isEmpty()) "" else {
                val perTabella = semplice.occorrenze.map { t -> insiemeDi(semplice, t, universo, null, 0) }
                if (perTabella.any { it == null }) ""
                else " AND (" + perTabella.joinToString(" OR ") { "valore_id IN ($it)" } + ")"
            }
            return "SELECT DISTINCT valore_id, toUInt8($condizione) AS verde FROM $INDICE " +
                    "WHERE area_id = $area AND campo = '${nome(campo)}'$inUniverso"
        }

        val riferimento = tabelleCon(campo).firstOrNull()
            ?: error("Il campo '$campo' non è in nessuna tabella del dataset")
        val viva = righeVive(riferimento, selezioni, omesso)
        val condizione = if (viva == null) "1" else "bitmapAndCardinality(righe, $viva) > 0"
        val vivaUniverso = if (universo.isEmpty()) null else righeVive(riferimento, universo)
        val inUniverso = if (vivaUniverso == null) "" else " AND bitmapAndCardinality(righe, $vivaUniverso) > 0"
        return "SELECT valore_id, toUInt8($condizione) AS verde FROM $INDICE " +
                "WHERE area_id = $area AND tabella_id = ${uuid(riferimento)} AND campo = '${nome(campo)}'$inUniverso"
    }

    // ---------- utilità ----------

    private fun and(termini: List<String>): String? =
        if (termini.isEmpty()) null
        else termini.drop(1).fold(termini.first()) { acc, t -> "bitmapAnd($acc, $t)" }

    private fun uuid(id: UUID): String = "toUUID('$id')"

    /** Difesa anti-injection: i nomi di campo sono già slug, qui si verifica soltanto. */
    private fun nome(valore: String): String = Naming.requirePhysical(valore, "campo")
}