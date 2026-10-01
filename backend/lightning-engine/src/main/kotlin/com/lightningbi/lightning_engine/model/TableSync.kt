package com.lightningbi.lightning_engine.model

import java.time.LocalDateTime
import java.util.UUID

/**
 * Come si tiene aggiornata una tabella importata.
 * COMPLETA: si svuota e si ricarica tutto.
 * INCREMENTALE: si rileggono solo le unità cambiate (vedi [TableSync]).
 */
enum class ModalitaSync {
    COMPLETA,
    INCREMENTALE
}

/**
 * Configurazione di sincronizzazione di UNA tabella importata (una riga di
 * lbi_table_sync). Generica per qualunque sorgente: nessun nome di colonna o
 * di tabella di un gestionale specifico sta nel codice, tutto è
 * configurazione.
 *
 * L'unità è ciò che si sostituisce per intero a ogni aggiornamento (per
 * esempio un documento): si cancellano da ClickHouse tutte le righe con
 * quella chiave e si reinseriscono quelle riletta dalla sorgente.
 *
 * Le query sono scritte nel linguaggio della sorgente ed eseguite dal suo
 * connettore. Devono restituire esattamente le colonne di [colonneUnita],
 * con gli stessi nomi. `:ultima_sync` è un parametro legato, mai sostituito
 * nel testo della query.
 *
 * Ora di riferimento: [ultimaSyncInizio] è l'ora di INIZIO dell'ultima
 * sincronizzazione riuscita, presa dalla sorgente stessa e salvata senza
 * conversioni di fuso. Il valore passato come `:ultima_sync` è
 * [ultimaSyncInizio] meno [margineSecondi].
 *
 * Avvertenze:
 * - Cambio dell'ora legale: un'ora locale senza fuso, quando l'orologio torna
 *   indietro, può far perdere una modifica fatta nell'ora ripetuta. Il margine
 *   di default (60 minuti) la copre; ridurlo è un limite accettato.
 * - Il metodo presuppone che il datastamp sia scritto con l'orologio del
 *   database sorgente, non con quello di un'applicazione.
 *
 * @param importedTableId tabella importata a cui si riferisce (chiave)
 * @param colonneUnita nomi delle colonne che identificano l'unità
 * @param queryCambiati chiavi delle unità cambiate dopo `:ultima_sync`
 * @param querySempre chiavi delle unità da rileggere sempre a ogni giro
 *   (opzionale: per esempio i documenti il cui stato dipende da altri)
 * @param confrontaCancellazioni se true, a ogni giro si confrontano le chiavi
 *   complete e si eliminano le unità sparite dalla sorgente
 * @param margineSecondi margine sottratto a [ultimaSyncInizio], in secondi
 * @param ultimaSyncInizio inizio dell'ultima sincronizzazione riuscita, ora
 *   della sorgente; null se la tabella non è mai stata sincronizzata
 */
data class TableSync(
    val importedTableId: UUID,
    val modalita: ModalitaSync = ModalitaSync.COMPLETA,
    val colonneUnita: List<String> = emptyList(),
    val queryCambiati: String? = null,
    val querySempre: String? = null,
    val confrontaCancellazioni: Boolean = false,
    val margineSecondi: Int = 3600,
    val ultimaSyncInizio: LocalDateTime? = null
)