// FILE: src/main/kotlin/com/lightningbi/lightning_engine/service/GrafoDataset.kt
package com.lightningbi.lightning_engine.service

import java.util.UUID

/**
 * Un collegamento tra tabelle del dataset: un campo condiviso, oppure una
 * chiave composta (due o più campi condivisi dalle STESSE tabelle, la
 * "chiave sintetica" di Qlik).
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
 * propagazione, come la logical inference di Qlik: la selezione si propaga da
 * una tabella alle altre lungo le associazioni.
 *
 * Il modello validato non ha loop (le tabelle che li creerebbero sono
 * disconnesse), quindi è un albero e la propagazione è una ricorsione sui
 * collegamenti. Kotlin puro: produce solo testo SQL, non lo esegue.
 *
 * DUE STRUMENTI, come in Qlik:
 *  - SELEZIONI LOCALI su un campo (dimensione): le bitmap dell'indice
 *    (ch_lbi_idx), una per valore.
 *  - MESSAGGI tra tabelle lungo un collegamento: dalla COLONNA delle tabelle
 *    importate, non dall'indice. Valori ammessi da T = la colonna della chiave
 *    nelle righe vive di T; righe di U = le righe con la chiave tra quei
 *    valori. Il costo dipende dalle righe, non dai valori distinti: una chiave
 *    di riga (un valore per riga) costa come una chiave piccola.
 *    Per una chiave composta la "colonna" è cityHash64 delle colonne dei campi,
 *    nello stesso ordine in tutte le tabelle.
 *
 * LE SELEZIONI sono per NOME di campo: campo -> id dei valori scelti. [omesso]
 * è il campo la cui selezione NON conta (omitSelf).
 *
 * RIGHE VIVE di una tabella: la bitmap (di lbi_rid) delle righe compatibili con
 * le selezioni. Una tabella che nessuna selezione tocca, direttamente o dai
 * vicini, non ha vincoli: null (= tutte le righe).
 *
 * STATO DI UN CAMPO ([sqlStatoCampo]): la query restituisce (valore_id, verde).
 *  - Campo-collegamento semplice: dominio = unione dei valori del campo
 *    nell'indice (tutte le tabelle che lo contengono); verde se, per ogni
 *    tabella vincolata, il valore compare nelle sue righe vive calcolate senza
 *    passare dal collegamento stesso.
 *  - Campo normale o membro di una chiave composta: la prima tabella che lo
 *    contiene; verde se compare nelle sue righe vive complete.
 *  Il campo deve essere nell'indice: lo stato si chiede solo per le dimensioni.
 *
 * Limite noto: un'associazione tra colonne dal nome fisico diverso ("per
 * valore") confronta id di symbol table diverse; gli id non sono unificati.
 */
class GrafoDataset private constructor(
    areaId: UUID,
    val tabelle: List<UUID>,
    val collegamenti: List<Collegamento>,
    private val campiPerTabella: Map<UUID, Set<String>>,
    /** (tabella, campo) -> colonna fisica. */
    private val colonne: Map<Pair<UUID, String>, String>,
    /** Tabella -> nome fisico su ClickHouse. */
    private val fisiche: Map<UUID, String>
) {
    private val area = "toUUID('$areaId')"

    companion object {
        const val INDICE = "ch_lbi_idx"
        private const val PROFONDITA_MAX = 64

        /** @param tabellaFisica occorrenza -> nome fisico della tabella importata */
        fun da(areaId: UUID, bozza: DatasetBozza, tabellaFisica: Map<UUID, String>): GrafoDataset {
            val ordine = bozza.occorrenze.map { it.id }
            val inclusi = bozza.campi().filter { !it.escluso }
            val perTabella = inclusi.groupBy { it.occorrenzaId }
                .mapValues { (_, v) -> v.map { it.nomeCampo }.toSet() }
            val colonne = inclusi.associate { (it.occorrenzaId to it.nomeCampo) to it.colonna }

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

            return GrafoDataset(areaId, ordine, collegamenti, perTabella, colonne, tabellaFisica)
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

        // Selezioni sui campi di questa tabella: bitmap dell'indice.
        val campiTabella = campiPerTabella[tabella] ?: emptySet()
        selezioni.forEach { (campo, valori) ->
            if (campo != omesso && valori.isNotEmpty() && campo in campiTabella) {
                termini += "(SELECT groupBitmapOrState(righe) FROM $INDICE WHERE area_id = $area AND tabella_id = ${uuid(tabella)} " +
                        "AND campo = '${nome(campo)}' AND valore_id IN (${valori.joinToString(",")}))"
            }
        }

        // Messaggi dei collegamenti (tranne quello da escludere): dalla colonna della tabella.
        collegamentiDi(tabella).filter { it != escludi }.forEach { c ->
            val ammessi = valoriAmmessi(c, tabella, selezioni, omesso, profondita + 1) ?: return@forEach
            termini += "(SELECT groupBitmapState(${Naming.RID_COLUMN}) FROM ${fisica(tabella)} " +
                    "WHERE ${chiave(c, tabella)} IN ($ammessi))"
        }
        return and(termini)
    }

    /**
     * Sottoquery dei valori della chiave del collegamento ammessi per [per], in
     * base alle ALTRE tabelle del collegamento. Null se nessuna è vincolata.
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

    /** Valori della chiave del collegamento nelle righe vive di [tabella] (senza passare dal collegamento). Null se non vincolata. */
    private fun insiemeDi(
        c: Collegamento,
        tabella: UUID,
        selezioni: Map<String, Set<Long>>,
        omesso: String?,
        profondita: Int
    ): String? {
        val viva = vive(tabella, selezioni, omesso, c, profondita) ?: return null
        return "SELECT ${chiave(c, tabella)} FROM ${fisica(tabella)} " +
                "WHERE bitmapContains($viva, ${Naming.RID_COLUMN})"
    }

    // ---------- stato di un campo ----------

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

    /** Espressione della chiave del collegamento nella tabella: la colonna, o cityHash64 delle colonne per una chiave composta. */
    private fun chiave(c: Collegamento, tabella: UUID): String {
        val cols = c.campi.map { colonna(tabella, it) }
        return if (cols.size == 1) cols.first() else "cityHash64(${cols.joinToString(", ")})"
    }

    private fun colonna(tabella: UUID, campo: String): String =
        Naming.requirePhysical(
            colonne[tabella to campo] ?: error("Il campo '$campo' non è nella tabella $tabella"),
            "colonna"
        )

    private fun fisica(tabella: UUID): String =
        Naming.requirePhysical(
            fisiche[tabella] ?: error("Tabella $tabella senza nome fisico"),
            "tabella"
        )

    private fun and(termini: List<String>): String? =
        if (termini.isEmpty()) null
        else termini.drop(1).fold(termini.first()) { acc, t -> "bitmapAnd($acc, $t)" }

    private fun uuid(id: UUID): String = "toUUID('$id')"

    /** Difesa anti-injection: i nomi di campo sono già slug, qui si verifica soltanto. */
    private fun nome(valore: String): String = Naming.requirePhysical(valore, "campo")
}