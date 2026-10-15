package com.lightning.bi.service.setanalysis

/*
 * Traduzione di una EspressioneSet per il motore ClickHouse di oggi.
 *
 * Il risultato non è SQL completo: è la selezione con cui calcolare la misura, nella forma che
 * GrafoDataset.righeVive capisce (selezioni a liste + condizioni SQL per campo). Le ricerche e i
 * valori diventano SOTTOQUERY sulla symbol table del campo, mai liste di id: la query resta
 * piccola qualunque sia il numero di valori.
 *
 * Il motore in memoria avrà la sua traduzione della stessa EspressioneSet.
 */

/** La selezione con cui si calcola una misura con condizione. */
data class SelezioneMisura(
    /** Selezioni a liste di id, per nome del campo nell'indice. */
    val selezioni: Map<String, Set<Long>>,
    /** Condizioni SQL sulla colonna valore_id dell'indice, per nome del campo. */
    val condizioni: Map<String, String>
)

/** Un campo del dataset come serve alla traduzione. */
data class CampoSet(
    /** Nome del campo nell'indice (Naming.column del nome). */
    val nome: String,
    /** Symbol table del campo (Naming.symbolTable della colonna fisica). */
    val tabellaSimboli: String
)

object SetAnalysisClickHouse {

    /**
     * @param selezioniUtente le selezioni correnti per nome di campo, compresa quella forzata del section access
     * @param campi i campi del dataset per [chiaveCampo]
     * @param forzato il campo del section access: nessun modificatore lo può cambiare
     * @throws IllegalArgumentException se un campo non è nel dataset
     */
    fun traduci(
        espressione: EspressioneSet,
        selezioniUtente: Map<String, Set<Long>>,
        campi: Map<String, CampoSet>,
        forzato: String?
    ): SelezioneMisura {
        val selezioni: MutableMap<String, Set<Long>> = when (espressione.identificatore) {
            Identificatore.CORRENTI -> selezioniUtente.toMutableMap()
            // {1}: nessuna selezione, ma il section access resta.
            Identificatore.TUTTO -> selezioniUtente.filterKeys { it == forzato }.toMutableMap()
        }
        val condizioni = mutableMapOf<String, String>()

        espressione.modificatori.forEach { m ->
            val campo = campi[chiaveCampo(m.campo)]
                ?: throw IllegalArgumentException("Il campo '${m.campo}' della condizione non è nel dataset")
            if (campo.nome == forzato) return@forEach  // il section access non si tocca
            val inInsieme = condizioneInsieme(campo, m.elementi)
            val attuale = selezioni[campo.nome]
            when (m.operatore) {
                // F = {X}: la selezione dell'utente su F si ignora.
                Operatore.SOSTITUISCI -> {
                    selezioni.remove(campo.nome)
                    condizioni[campo.nome] = inInsieme
                }
                // F *= {X}: selezione dell'utente (se c'è) E X.
                Operatore.INTERSECA -> condizioni[campo.nome] = inInsieme
                // F -= {X}: selezione dell'utente (se c'è, altrimenti tutto) meno X.
                Operatore.ESCLUDI -> condizioni[campo.nome] = "NOT ($inInsieme)"
                // F += {X}: selezione dell'utente O X. Senza selezione su F: tutto (più X) = nessun vincolo.
                Operatore.AGGIUNGI -> if (attuale != null) {
                    selezioni.remove(campo.nome)
                    condizioni[campo.nome] = "(valore_id IN (${attuale.joinToString(",")}) OR $inInsieme)"
                }
            }
        }
        return SelezioneMisura(selezioni, condizioni)
    }

    /** "valore_id IN (SELECT value_id FROM <symbol table> WHERE …)"; insieme vuoto = falso. */
    private fun condizioneInsieme(campo: CampoSet, elementi: List<Elemento>): String {
        if (elementi.isEmpty()) return "0"
        val dove = elementi.joinToString(" OR ") { "(${condizioneElemento(it)})" }
        return "valore_id IN (SELECT value_id FROM ${campo.tabellaSimboli} WHERE $dove)"
    }

    private fun condizioneElemento(e: Elemento): String = when (e) {
        is Elemento.Letterale -> "value_string = '${letterale(e.valore)}'"
        is Elemento.Numero -> "value_number = ${e.valore.toPlainString()}"
        is Elemento.Ricerca -> "value_string ILIKE '${letterale(modelloLike(e.modello))}'"
        is Elemento.Confronto -> buildList {
            e.minimo?.let { add("value_number ${if (e.minimoIncluso) ">=" else ">"} ${it.toPlainString()}") }
            e.massimo?.let { add("value_number ${if (e.massimoIncluso) "<=" else "<"} ${it.toPlainString()}") }
        }.joinToString(" AND ").ifEmpty { "1" }
    }

    /** Jolly di Qlik → LIKE: * = %, ? = _; i % e _ veri si proteggono. */
    private fun modelloLike(modello: String): String = buildString {
        modello.forEach { c ->
            when (c) {
                '*' -> append('%')
                '?' -> append('_')
                '%', '_', '\\' -> append('\\').append(c)
                else -> append(c)
            }
        }
    }

    /** Testo sicuro dentro '…' per ClickHouse. */
    private fun letterale(testo: String): String = testo.replace("\\", "\\\\").replace("'", "\\'")
}