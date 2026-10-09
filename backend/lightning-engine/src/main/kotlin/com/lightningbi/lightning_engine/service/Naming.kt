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
 *
 * I nomi dei CAMPI conservano le maiuscole, come in Qlik: "Cliente" e
 * "cliente" sono due campi diversi e non si associano. I nomi delle TABELLE
 * sono sempre in minuscolo.
 */
object Naming {

    private val valid = Regex("^[A-Za-z][A-Za-z0-9_]*$")

    /** Converte un nome qualsiasi in identificatore SQL sicuro, conservando le maiuscole. */
    fun slug(nome: String): String {
        val s = nome
            .trim()
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        // Un identificatore non può iniziare per cifra.
        val safe = if (s.isNotEmpty() && s.first().isDigit()) "c_$s" else s
        require(safe.isNotEmpty()) { "Nome non convertibile in identificatore: '$nome'" }
        require(valid.matches(safe)) { "Identificatore non valido dopo normalizzazione: '$safe' (da '$nome')" }
        return safe
    }

    /** Come [slug], ma sempre in minuscolo: per i nomi delle tabelle. */
    fun slugTabella(nome: String): String = slug(nome).lowercase()

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

    /**
     * Numero della sorgente da cui viene la riga (UInt8): 0 = la connessione della
     * tabella, 1..255 = le sorgenti aggiuntive (SorgenteTabella). Nome riservato.
     */
    const val SRC_COLUMN = "lbi_src"

    /** Verifica che un identificatore fisico sia sicuro da inserire in SQL (accetta il doppio underscore delle tabelle importate). */
    fun requirePhysical(value: String, what: String): String {
        require(valid.matches(value)) { "Identificatore $what non valido: '$value'" }
        return value
    }

    /**
     * Tabella importata nel modello multi-tabella (TBS): <motore>_<db>__<nome>,
     * es. mssql_sem__documenti, mssql_sem__clienti. Sempre in minuscolo.
     *
     * @param motore tipo della connessione (mssql, psql, ...)
     * @param nomeDb nome logico del database/schema sorgente (es. "sem")
     * @param nomeTabella nome logico della tabella importata (es. "Documenti")
     */
    fun importedTable(motore: String, nomeDb: String, nomeTabella: String): String =
        "${slugTabella(motore)}_${slugTabella(nomeDb)}__${slugTabella(nomeTabella)}"
}