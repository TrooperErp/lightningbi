package com.lightningbi.lightning_engine.connector

import com.lightningbi.lightning_engine.model.SourceConnection
import com.lightningbi.lightning_engine.service.ColumnInfo
import com.lightningbi.lightning_engine.service.SourceConnectionService
import com.lightningbi.lightning_engine.service.TableInfo
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Punto unico verso le sorgenti dati: le pagine admin e l'ETL parlano solo
 * con lui, mai con un connettore o con il repository.
 *
 * Sceglie il connettore giusto dal tipo della connessione (mssql, psql,
 * influx, ...). Aggiungere un tipo di sorgente = scrivere un connettore
 * (@Component che implementa SourceConnector): non serve toccare né questa
 * classe, né l'ETL, né le pagine.
 *
 * Le operazioni di lettura (test, schemi, tabelle, colonne, estrazione)
 * lavorano su una connessione GIÀ SALVATA, indicata per id: i segreti
 * restano dentro il servizio e i connettori, senza passare in chiaro da
 * qui.
 */
@Service
class ConnectionOrchestrator(
    private val connectionService: SourceConnectionService,
    connectors: List<SourceConnector>
) {
    private val connettoriPerTipo: Map<String, SourceConnector> = buildMap {
        connectors.forEach { connettore ->
            connettore.tipiSupportati.forEach { tipo ->
                check(!containsKey(tipo)) {
                    "Il tipo di sorgente '$tipo' è gestito da più di un connettore"
                }
                put(tipo, connettore)
            }
        }
    }

    /** Tipi di sorgente disponibili, per le scelte nelle pagine. */
    fun tipiDisponibili(): List<String> = connettoriPerTipo.keys.sorted()

    // ---------- gestione delle connessioni ----------

    fun findAll(): List<SourceConnection> = connectionService.findAll()

    fun findById(id: UUID): SourceConnection? = connectionService.findById(id)

    fun create(
        nome: String,
        tipo: String,
        parametri: Map<String, String>,
        segretiInChiaro: Map<String, String>
    ): SourceConnection {
        connettoreDi(tipo.trim())
        return connectionService.create(nome, tipo, parametri, segretiInChiaro)
    }

    fun update(
        id: UUID,
        nome: String,
        parametri: Map<String, String>,
        segretiInChiaro: Map<String, String>
    ): SourceConnection = connectionService.update(id, nome, parametri, segretiInChiaro)

    fun delete(id: UUID) = connectionService.delete(id)

    // ---------- lettura dalla sorgente ----------

    /** Lancia un'eccezione con la causa se la connessione non funziona. */
    fun testConnection(connectionId: UUID) {
        val connessione = connessione(connectionId)
        connettoreDi(connessione.tipo).testConnection(connessione)
    }

    fun listSchemas(connectionId: UUID): List<String> {
        val connessione = connessione(connectionId)
        return connettoreDi(connessione.tipo).listSchemas(connessione)
    }

    fun listTables(connectionId: UUID, schema: String?): List<TableInfo> {
        val connessione = connessione(connectionId)
        return connettoreDi(connessione.tipo).listTables(connessione, schema)
    }

    fun listColumns(connectionId: UUID, schema: String?, tabella: String): List<ColumnInfo> {
        val connessione = connessione(connectionId)
        return connettoreDi(connessione.tipo).listColumns(connessione, schema, tabella)
    }

    fun sampleRows(
        connectionId: UUID,
        schema: String?,
        tabella: String,
        limit: Int = 3
    ): List<Map<String, Any?>> {
        val connessione = connessione(connectionId)
        return connettoreDi(connessione.tipo).sampleRows(connessione, schema, tabella, limit)
    }

    /**
     * Lettura a streaming di una tabella intera: le righe vanno a `consumatore`
     * e la connessione si chiude sempre al suo ritorno, anche in caso di
     * errore (vedi SourceConnector.extract).
     */
    fun extract(
        connectionId: UUID,
        schema: String?,
        tabella: String,
        consumatore: (Sequence<Map<String, Any?>>) -> Unit
    ) {
        val connessione = connessione(connectionId)
        connettoreDi(connessione.tipo).extract(connessione, schema, tabella, consumatore)
    }

    // ---------- interni ----------

    private fun connessione(id: UUID): SourceConnection =
        connectionService.findById(id) ?: error("Connessione $id non trovata")

    private fun connettoreDi(tipo: String): SourceConnector =
        connettoriPerTipo[tipo]
            ?: error("Nessun connettore per il tipo '$tipo'. Tipi disponibili: ${tipiDisponibili().joinToString(", ")}")
}