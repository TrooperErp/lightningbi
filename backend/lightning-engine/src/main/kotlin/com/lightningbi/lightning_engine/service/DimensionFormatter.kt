package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.MeseItaliano

/**
 * Traduzioni speciali per dimensioni i cui valori grezzi (numerici o
 * codificati) non sono leggibili di per sé, e non passano dalla symbol
 * table standard per la loro etichetta finale - solo per la
 * presentazione, il dato in ClickHouse resta il valore grezzo (es.
 * mese_numero=3, non "Marzo": ordinabile, riusabile, indipendente dalla
 * lingua). Stesso principio delle mapping table di Qlik (Mapping Load +
 * ApplyMap), applicato a lettura invece che a caricamento per non
 * duplicare il dato su disco.
 *
 * Registrato per nome fisico di colonna (colonnaFisica), non per nome
 * dimensione: due aree diverse potrebbero chiamare la stessa colonna con
 * nomi dimensione diversi, ma la colonna "mese_numero" ha sempre lo
 * stesso significato ovunque compaia.
 */
object DimensionFormatters {

    private val formatters: Map<String, (Long) -> String> = mapOf(
        "mese_numero" to { v -> MeseItaliano.labelFor(v) }
        // futuri: "trimestre_numero" to { v -> ... }, "giorno_settimana_numero" to { v -> ... }
    )

    /**
     * Se la colonna ha un formatter dedicato, lo usa. Altrimenti torna
     * null: il chiamante deve procedere con la risoluzione standard via
     * SymbolLookupService.
     */
    fun formatOrNull(colonnaFisica: String, valueId: Long): String? =
        formatters[colonnaFisica]?.invoke(valueId)
}