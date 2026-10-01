package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.RuoloTabella

/** Ruolo proposto per una colonna di una tabella importata. */
enum class RuoloColonna { CHIAVE, CONDIVISA, DIMENSIONE, METRICA, IGNORA }

/**
 * Proposte di default per l'importazione di una tabella: ruolo delle colonne,
 * nome logico, chiavi di JOIN, collisioni di nome.
 *
 * Nessun nome di colonna, di tabella o di prefisso di un gestionale specifico
 * sta qui: tutto ciò che dipende dalla sorgente arriva dalla configurazione
 * della connessione (vedi [PREFISSI_DA_TOGLIERE] e [PREFISSI_COLONNA_CHIAVE]).
 * Le funzioni sono pure e usate sia dal wizard sia dalla pagina "Tabelle
 * importate". Sono solo PROPOSTE: l'admin può cambiarle.
 */
object ColumnProposal {

    /**
     * Proprietà della connessione (in `parametri`): prefissi da togliere dal
     * nome di una tabella per proporre il nome logico, separati da virgola
     * (es. "PREFISSO_A,PREFISSO_"). Vuota = nessun prefisso.
     */
    const val PREFISSI_DA_TOGLIERE = "prefissiDaTogliere"

    /**
     * Proprietà della connessione (in `parametri`): prefissi dei nomi di
     * colonna che identificano chiavi tecniche, separati da virgola. Una
     * colonna con questo prefisso è proposta come "Ignora" (se non è una
     * chiave scelta) e come prima candidata per il JOIN. Vuota = nessuna regola.
     */
    const val PREFISSI_COLONNA_CHIAVE = "prefissiColonnaChiave"

    /** Legge una proprietà lista (separata da virgola) dai parametri della connessione. */
    fun lista(parametri: Map<String, String>, proprieta: String): List<String> =
        parametri[proprieta].orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    // ---------- ruolo di default ----------

    private val tipiSempreMetrica = listOf("MONEY", "FLOAT", "REAL", "DOUBLE")
    private val tipiConScala = listOf("DECIMAL", "NUMERIC")

    /**
     * Una colonna numerica è proposta come Metrica se ha decimali o è in
     * virgola mobile. I DECIMAL/NUMERIC con scala 0 sono numeri interi (spesso
     * codici) e partono come Dimensione. Scala sconosciuta: si comporta come
     * prima, cioè Metrica. L'intero (INT, BIGINT...) non è mai una Metrica.
     */
    fun isMetricaPerTipo(typeName: String, scale: Int?): Boolean {
        val t = typeName.uppercase()
        if (tipiSempreMetrica.any { t.contains(it) }) return true
        if (tipiConScala.any { t.contains(it) }) return (scale ?: 1) > 0
        return false
    }

    /**
     * Ruolo di default di una colonna.
     *
     * @param ruoloTabella ruolo della tabella a cui appartiene la colonna
     * @param colonneChiaveFatti (solo Fatti) nomi delle colonne scelte come chiave di JOIN, minuscolo
     * @param colonnaChiaveTabella (solo Dimensione) colonna chiave di questa tabella
     * @param nomiFatti (solo Dimensione) nomi delle colonne dei Fatti, minuscolo: un nome uguale è condiviso
     * @param prefissiColonnaChiave vedi [PREFISSI_COLONNA_CHIAVE]
     */
    fun ruoloDefault(
        nome: String,
        typeName: String,
        scale: Int?,
        ruoloTabella: RuoloTabella,
        colonneChiaveFatti: Set<String>,
        colonnaChiaveTabella: String?,
        nomiFatti: Set<String>,
        prefissiColonnaChiave: List<String>
    ): RuoloColonna {
        val n = nome.lowercase()
        val tecnica = prefissiColonnaChiave.any { n.startsWith(it.lowercase()) }
        return if (ruoloTabella == RuoloTabella.FATTI) {
            when {
                n in colonneChiaveFatti -> RuoloColonna.CHIAVE
                tecnica -> RuoloColonna.IGNORA
                isMetricaPerTipo(typeName, scale) -> RuoloColonna.METRICA
                else -> RuoloColonna.DIMENSIONE
            }
        } else {
            when {
                n == colonnaChiaveTabella?.lowercase() -> RuoloColonna.CHIAVE
                n in nomiFatti -> RuoloColonna.CONDIVISA
                tecnica -> RuoloColonna.IGNORA
                else -> RuoloColonna.DIMENSIONE
            }
        }
    }

    // ---------- collisioni di nome fisico ----------

    /**
     * Colonne che dopo la normalizzazione ([Naming.column]) diventano lo stesso
     * nome fisico. Restituisce, per ogni nome fisico in collisione, i nomi
     * ORIGINALI delle colonne che lo producono.
     */
    fun collisioni(nomiOriginali: List<String>): Map<String, List<String>> =
        nomiOriginali
            .groupBy { Naming.column(it) }
            .filterValues { it.size > 1 }

    /** Messaggio di collisione che nomina le colonne coinvolte. */
    fun messaggioCollisioni(nomeTabella: String, collisioni: Map<String, List<String>>): String {
        val dettaglio = collisioni.entries.joinToString("; ") { (fisico, originali) ->
            originali.joinToString(" e ") { "'$it'" } + " diventano entrambe '$fisico'"
        }
        return "In $nomeTabella ci sono colonne che collidono dopo la normalizzazione: $dettaglio. " +
                "Impostane una su Ignora."
    }

    // ---------- nome logico ----------

    /**
     * Nome logico proposto: toglie il primo prefisso che combacia (il più
     * lungo per primo, senza distinguere maiuscole) e mette la maiuscola
     * iniziale. Se il nome resterebbe vuoto, parte dal nome originale.
     */
    fun nomeLogico(nomeOrigine: String, prefissiDaTogliere: List<String>): String {
        val prefisso = prefissiDaTogliere
            .sortedByDescending { it.length }
            .firstOrNull { nomeOrigine.startsWith(it, ignoreCase = true) }
        val senza = (if (prefisso != null) nomeOrigine.substring(prefisso.length) else nomeOrigine)
            .ifBlank { nomeOrigine }
        return senza.lowercase().replaceFirstChar { it.uppercase() }
    }

    // ---------- chiavi di JOIN ----------

    /**
     * Colonne candidate per il JOIN Fatti <-> Dimensione: stesso nome in
     * entrambe le tabelle (senza distinguere maiuscole), come l'engine
     * associativo Qlik. Prima quelle con un prefisso di colonna chiave
     * ([PREFISSI_COLONNA_CHIAVE]), poi le altre in ordine alfabetico.
     * Nessun risultato = tabelle non collegabili.
     */
    fun chiaviProposte(
        colonneFatti: List<String>,
        colonneDimensione: List<String>,
        prefissiColonnaChiave: List<String>
    ): List<String> {
        val dim = colonneDimensione.map { it.lowercase() }.toSet()
        fun tecnica(nome: String) = prefissiColonnaChiave.any { nome.startsWith(it, ignoreCase = true) }
        return colonneFatti
            .filter { it.lowercase() in dim }
            .sortedWith(compareByDescending<String> { tecnica(it) }.thenBy { it.lowercase() })
    }
}