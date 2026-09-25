package com.lightningbi.lightning_engine.model

/**
 * Le aziende del gruppo, mappate sul codice_ditta usato nelle view
 * sorgente ERP. Stesso enum usato sia per la formattazione display
 * (DimensionFormatters, dove "1" diventa "SEM" in una card/griglia) sia
 * per il vincolo di accesso monotenant su User.codiceDittaAssegnata
 * (dove "1" vincola l'utente a vedere solo i dati SEM) - un'unica fonte
 * di verità per il significato del codice, non due mapping paralleli
 * che potrebbero disallinearsi.
 */
enum class Azienda(val codice: Int, val label: String) {
    SEM(1, "SEM"),
    UNIVERSAL_PAINT(2, "Universal Paint"),
    SEI_S(3, "6S"),
    LDT(4, "LDT"),
    IMET(5, "IMET");

    companion object {
        fun labelFor(codice: Long): String =
            entries.find { it.codice == codice.toInt() }?.label ?: "#$codice"

        fun fromCodice(codice: Int): Azienda? =
            entries.find { it.codice == codice }
    }
}