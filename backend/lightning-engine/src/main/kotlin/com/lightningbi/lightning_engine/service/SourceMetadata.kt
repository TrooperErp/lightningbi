package com.lightningbi.lightning_engine.service

/** Una tabella o view di una sorgente, come la restituisce un connettore. */
data class TableInfo(val schema: String, val name: String)

/**
 * Una colonna di una tabella sorgente: nome e tipo così come li dichiara la
 * sorgente. [scale] sono le cifre decimali (0 = numero intero), null se la
 * sorgente non la dichiara o il tipo non è numerico.
 */
data class ColumnInfo(val name: String, val typeName: String, val scale: Int? = null)

/**
 * Esito della prova di una query di chiavi: i nomi delle colonne che la query
 * restituisce (come li dichiara la sorgente) e, se esiste, una riga di esempio.
 * Non contiene mai un insieme di chiavi: la prova serve solo a controllare la forma.
 */
data class KeyQueryProbe(val colonne: List<String>, val esempio: Map<String, Any?>?)

/** La query non ha risposto entro il tempo concesso. Non vuol dire che sia sbagliata. */
class QueryScadutaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** La sorgente ha rifiutato la query (sintassi, oggetto inesistente, permessi). */
class QueryNonValidaException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)