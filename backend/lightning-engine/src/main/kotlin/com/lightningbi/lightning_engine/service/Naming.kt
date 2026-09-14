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

    /** Tabella dei fatti di un'area. */
    fun areaTable(nomeArea: String): String = "ch_lbi_" + slug(nomeArea)

    /** Symbol table di una dimensione. */
    fun symbolTable(nomeDimensione: String): String = "ch_lbi_symbol_" + slug(nomeDimensione)

    /** Nome della view generata sul DB locale. */
    fun viewName(nomeArea: String): String = "vw_lbi_" + slug(nomeArea)
}