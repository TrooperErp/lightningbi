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

    /** Tabella dei fatti di un'area (modello legacy a view singola). */
    fun areaTable(nomeArea: String): String = "ch_lbi_" + slug(nomeArea)

    /** Symbol table di una dimensione. */
    fun symbolTable(nomeDimensione: String): String = "ch_lbi_symbol_" + slug(nomeDimensione)

    /** Nome della view generata sul DB locale (modello legacy a view singola). */
    fun viewName(nomeArea: String): String = "vw_lbi_" + slug(nomeArea)

    /**
     * Motori sorgente riconosciuti per il naming delle tabelle importate.
     * "altro" è il fallback per driver non ancora mappati esplicitamente:
     * non blocca l'importazione, produce solo un prefisso meno leggibile.
     */
    private val motoriPerDriver = mapOf(
        "com.microsoft.sqlserver.jdbc.SQLServerDriver" to "mssql",
        "org.postgresql.Driver" to "psql",
        "org.influxdb.InfluxDB" to "influx"
    )

    /**
     * Ricava il prefisso motore (mssql, psql, influx, ...) dal driver JDBC
     * della sorgente, per comporre il nome fisico di una tabella importata.
     * Nessuna sorgente deve passare un motore a mano: si desincronizzerebbe
     * dal driver realmente usato per connettersi.
     */
    fun motoreFromDriver(driverClassName: String): String =
        motoriPerDriver[driverClassName] ?: "altro"

    /**
     * Tabella importata nel modello multi-tabella (TBS): <motore>_<db>__<nome>,
     * es. mssql_sem__documenti, mssql_sem__clienti.
     *
     * @param motore vedi motoreFromDriver
     * @param nomeDb nome logico del database/schema sorgente (es. "sem")
     * @param nomeTabella nome logico della tabella importata (es. "Documenti")
     */
    fun importedTable(motore: String, nomeDb: String, nomeTabella: String): String =
        "${slug(motore)}_${slug(nomeDb)}__${slug(nomeTabella)}"
}