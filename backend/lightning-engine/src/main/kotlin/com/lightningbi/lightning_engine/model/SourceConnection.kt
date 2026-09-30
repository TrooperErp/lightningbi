package com.lightningbi.lightning_engine.model

import java.time.Instant
import java.util.UUID

/**
 * Connessione a una sorgente dati (es. il SQL Server di TeamSystem),
 * indipendente dai dataset: una connessione può alimentare più tabelle
 * importate, e quindi più dataset.
 *
 * Il modello è generico per TIPO: cambiano i parametri, non la struttura.
 * - tipo: codice del tipo di sorgente (mssql, psql, influx, ...), lo
 *   stesso usato da Naming.importedTable per il prefisso delle tabelle.
 * - parametri: valori non riservati, in chiaro.
 * - segreti: valori riservati (password, token). I VALORI sono sempre
 *   cifrati (CryptoService): chi si connette li decifra al momento
 *   dell'uso, mai prima.
 *
 * Chiavi previste per tipo (le definisce il connettore del tipo):
 * - mssql, psql (JDBC): parametri jdbcUrl, username, schema (opzionale);
 *   segreti password
 * - influx: parametri url, org, bucket; segreti token
 *
 * Il nome è quello scelto dall'admin (univoco) e serve a riconoscerla
 * quando la si riusa.
 */
data class SourceConnection(
    val id: UUID,
    val nome: String,
    val tipo: String,
    val parametri: Map<String, String>,
    val segreti: Map<String, String>,
    val createdAt: Instant
)