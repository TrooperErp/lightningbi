package com.lightningbi.lightning_engine.connector

import com.lightningbi.lightning_engine.model.SourceConnection
import com.lightningbi.lightning_engine.service.ColumnInfo
import com.lightningbi.lightning_engine.service.KeyQueryProbe
import com.lightningbi.lightning_engine.service.TableInfo
import java.time.LocalDateTime

/**
 * Contratto di un connettore verso un tipo di sorgente dati.
 *
 * Un connettore sa parlare con UN tipo di sistema (o una famiglia: mssql e
 * psql sono entrambi JDBC). Non tiene connessioni aperte tra una chiamata e
 * l'altra: ogni operazione apre la sua e la chiude, così lo stesso
 * contratto vale per sorgenti con modelli di connessione diversi (JDBC,
 * Influx, ...).
 *
 * Chi usa i connettori non li chiama direttamente: passa da
 * ConnectionOrchestrator, che sceglie quello giusto dal tipo della
 * connessione.
 *
 * I segreti della connessione sono cifrati: il connettore li decifra solo
 * al momento di aprire la connessione (SourceConnectionService.decryptSecret).
 */
interface SourceConnector {

    /** Codici tipo gestiti da questo connettore (gli stessi di SourceConnection.tipo). */
    val tipiSupportati: Set<String>

    /** Apre la connessione e la richiude. Lancia un'eccezione con la causa se non riesce. */
    fun testConnection(connection: SourceConnection)

    fun listSchemas(connection: SourceConnection): List<String>

    /** @param schema null = nessuno schema (o quello di default della sorgente) */
    fun listTables(connection: SourceConnection, schema: String?): List<TableInfo>

    fun listColumns(connection: SourceConnection, schema: String?, tabella: String): List<ColumnInfo>

    /** Poche righe di esempio, per mostrare all'admin cosa contiene davvero una colonna. */
    fun sampleRows(
        connection: SourceConnection,
        schema: String?,
        tabella: String,
        limit: Int = 3
    ): List<Map<String, Any?>>

    /**
     * Legge per intero una tabella, riga per riga (streaming), e passa le righe
     * a `consumatore`. Le chiavi di ogni riga sono i nomi colonna in minuscolo.
     *
     * La connessione si apre prima di chiamare `consumatore` e si chiude
     * sempre al suo ritorno, anche se lancia un'eccezione: chi la usa non deve
     * chiudere niente. La sequenza vale solo dentro `consumatore` e si può
     * percorrere una volta sola: non va restituita né conservata.
     */
    fun extract(
        connection: SourceConnection,
        schema: String?,
        tabella: String,
        consumatore: (Sequence<Map<String, Any?>>) -> Unit
    )


    /**
     * Ora corrente della sorgente, nell'ora della sorgente (senza fuso): è il
     * riferimento per `ultima_sync_inizio` e per `:ultima_sync`.
     */
    fun sourceNow(connection: SourceConnection): LocalDateTime

    /**
     * Prova una query di chiavi per controllarne la forma, senza leggere un
     * insieme di chiavi: la esegue con `:ultima_sync` legato come parametro
     * (mai sostituito nel testo), entro `timeoutSecondi`, e restituisce i nomi
     * delle colonne e al massimo una riga di esempio.
     *
     * @throws QueryScadutaException se non risponde entro il tempo concesso
     * @throws QueryNonValidaException se la sorgente la rifiuta
     */
    fun probeKeyQuery(
        connection: SourceConnection,
        query: String,
        ultimaSync: LocalDateTime,
        timeoutSecondi: Int
    ): KeyQueryProbe
}