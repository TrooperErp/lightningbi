// FILE: src/main/kotlin/com/lightningbi/lightning_engine/model/SorgenteTabella.kt
package com.lightningbi.lightning_engine.model

import java.util.UUID

/**
 * Una sorgente AGGIUNTIVA di una tabella importata (lbi_imported_table_sorgente,
 * migrazione 039): la stessa vista letta da un'altra connessione e accodata
 * nella stessa tabella, come il CONCATENATE di Qlik (es. lo stesso gestionale
 * di un'altra azienda su un altro database).
 *
 * La sorgente principale resta la connessione della tabella (ImportedTable.connectionId),
 * con numero 0. Le aggiuntive hanno [ordine] da 1 a 255. Ogni riga caricata porta
 * il numero della sua sorgente nella colonna nascosta lbi_src.
 *
 * Schema e nome della vista sono quelli della tabella: cambia solo la connessione.
 *
 * @param dittaForzata valore scritto nel campo azienda (lbi.azienda.campo) per tutte
 *   le righe di questa sorgente, al posto di quello letto; null = si legge dalla vista
 * @param prefissoChiavi testo messo davanti ai valori delle colonne chiave, per non
 *   farli collidere con quelli delle altre sorgenti; null = nessun prefisso
 */
data class SorgenteTabella(
    val id: UUID,
    val importedTableId: UUID,
    val ordine: Int,
    val connectionId: UUID,
    val dittaForzata: Int? = null,
    val prefissoChiavi: String? = null
) {
    init {
        require(ordine in 1..MAX_ORDINE) { "Il numero della sorgente deve essere tra 1 e $MAX_ORDINE" }
        require(prefissoChiavi == null || prefissoChiavi.isNotEmpty()) { "Il prefisso delle chiavi non può essere vuoto" }
        require(prefissoChiavi == null || prefissoChiavi.length <= MAX_PREFISSO) {
            "Il prefisso delle chiavi supera $MAX_PREFISSO caratteri"
        }
    }

    companion object {
        const val PRINCIPALE = 0
        const val MAX_ORDINE = 255
        const val MAX_PREFISSO = 20
    }
}