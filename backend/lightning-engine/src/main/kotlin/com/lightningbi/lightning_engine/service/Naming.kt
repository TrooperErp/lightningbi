package com.lightningbi.lightning_engine.service

/**
 * Unica fonte di verità per la conversione nome logico -> identificatore
 * fisico (ClickHouse / Postgres).
 *
 * REGOLA: nessuna classe deve costruirsi da sola un nome di tabella o di
 * colonna. Chiunque abbia bisogno di un identificatore fisico passa da qui.
 * Se due punti del codice normalizzano in modo anche solo leggermente
 * diverso, si desincronizzano in modo silenzioso (scrive su una tabella,
 * legge da un'altra).
 *
 * Il nome logico (quello che l'utente vede, es. "Ordini" o "CODICE_CLIENTE")
 * resta invariato nel registry; qui si ricava solo la sua forma fisica.
 */
object Naming {

    private val valid = Regex("^[a-z][a-z0-9_]*$")

    /** Converte un nome qualsiasi in identificatore SQL sicuro. */
    fun slug(nome: String): String {
        val s = nome
            .lowercase()
            .trim()
            .replace(Regex("[^a-z0-9]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        // Un identificatore non può iniziare per cifra.
        val safe = if (s.isNotEmpty() && s.first().isDigit()) "c_$s" else s
        require(safe.isNotEmpty()) { "Nome non convertibile in identificatore: '$nome'" }
        require(valid.matches(safe)) { "Identificatore non valido dopo normalizzazione: '$safe' (da '$nome')" }
        return safe
    }

    /** Nome colonna fisica su ClickHouse. */
    fun column(nome: String): String = slug(nome)

    /**
     * Symbol table di un CAMPO, stile Qlik: si chiama come la colonna fisica,
     * quindi due colonne con lo stesso nome (in tabelle diverse) condividono
     * la stessa symbol table e gli stessi id. È ciò che rende possibile
     * l'associazione per nome.
     */
    fun symbolTable(colonna: String): String = "ch_lbi_symbol_" + slug(colonna)

    /**
     * Copia numerica di una colonna di tipo numerico: l'id (UInt32) resta
     * nella colonna con il nome fisico, il valore (Decimal) sta qui. Lo slug
     * non produce mai un doppio underscore, quindi il suffisso "__n" non può
     * collidere con una colonna della sorgente.
     */
    fun numericColumn(colonna: String): String = column(colonna) + "__n"

    /**
     * Numero di riga persistente delle tabelle importate (UInt32): identifica
     * la riga nelle bitmap dell'indice associativo. Nome riservato: una colonna
     * della sorgente con lo stesso nome non è ammessa.
     */
    const val RID_COLUMN = "lbi_rid"

    /** Verifica che un identificatore fisico sia sicuro da inserire in SQL (accetta il doppio underscore delle tabelle importate). */
    fun requirePhysical(value: String, what: String): String {
        require(valid.matches(value)) { "Identificatore $what non valido: '$value'" }
        return value
    }

    /**
     * Tabella importata nel modello multi-tabella (TBS): <motore>_<db>__<nome>,
     * es. mssql_sem__documenti, mssql_sem__clienti.
     *
     * @param motore tipo della connessione (mssql, psql, ...)
     * @param nomeDb nome logico del database/schema sorgente (es. "sem")
     * @param nomeTabella nome logico della tabella importata (es. "Documenti")
     */
    fun importedTable(motore: String, nomeDb: String, nomeTabella: String): String =
        "${slug(motore)}_${slug(nomeDb)}__${slug(nomeTabella)}"
}