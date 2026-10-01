package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.connector.ConnectionOrchestrator
import com.lightningbi.lightning_engine.model.ModalitaSync
import com.lightningbi.lightning_engine.model.TableSync
import com.lightningbi.lightning_engine.repository.ImportedTableRepository
import com.lightningbi.lightning_engine.repository.TableSyncRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Configurazione della sincronizzazione di una tabella importata. Solo admin,
 * anche lato server.
 *
 * L'incrementale si può attivare solo se la configurazione è completa e le
 * query sono PROVATE sulla sorgente: la prova controlla la forma (le colonne
 * restituite sono quelle dichiarate come unità), non legge mai un insieme di
 * chiavi. Un timeout della prova non vuol dire che la query sia sbagliata:
 * viene segnalato a parte, ma l'incrementale resta comunque bloccato.
 *
 * L'esecuzione vera delle query (con il massimo di righe e l'errore bloccante
 * se superato) è della sincronizzazione, non di questa classe.
 */
@Service
class TableSyncService(
    private val tableSyncRepository: TableSyncRepository,
    private val importedTableRepository: ImportedTableRepository,
    private val connectionOrchestrator: ConnectionOrchestrator,
    private val adminGuard: AdminGuard,
    @Value("\${lbi.sync.probe-timeout-seconds:30}") private val probeTimeoutSeconds: Int
) {

    /** Configurazione della tabella; se non ne ha una, quella di partenza (completa). */
    fun leggi(importedTableId: UUID): TableSync {
        adminGuard.requireAdmin()
        return tableSyncRepository.findByTable(importedTableId) ?: TableSync(importedTableId = importedTableId)
    }

    /**
     * Prova una query di chiavi per controllarne la forma. Usata dal pulsante
     * "Prova" della pagina e da [salva].
     *
     * @param etichetta come chiamare la query nei messaggi ("query dei cambiati")
     * @throws QueryScadutaException se la sorgente non risponde entro il tempo concesso
     * @throws QueryNonValidaException se la sorgente la rifiuta o le colonne non coincidono con l'unità
     */
    fun provaQuery(
        importedTableId: UUID,
        query: String,
        colonneUnita: List<String>,
        margineSecondi: Int,
        etichetta: String
    ): KeyQueryProbe {
        adminGuard.requireAdmin()
        val tabella = importedTableRepository.findById(importedTableId)
            ?: throw IllegalArgumentException("Tabella importata non trovata")
        val connectionId = tabella.connectionId
            ?: throw IllegalStateException("La tabella '${tabella.nomeLogico}' non ha una connessione: non si può provare la query")

        require(query.isNotBlank()) { "La $etichetta è vuota" }
        require(query.length <= MAX_QUERY) { "La $etichetta è troppo lunga (massimo $MAX_QUERY caratteri)" }

        // Stesso riferimento che userà la sincronizzazione: ora della sorgente meno il margine.
        val riferimento = connectionOrchestrator.sourceNow(connectionId).minusSeconds(margineSecondi.toLong())

        val esito = try {
            connectionOrchestrator.probeKeyQuery(connectionId, query, riferimento, probeTimeoutSeconds)
        } catch (e: QueryScadutaException) {
            throw QueryScadutaException(
                "La $etichetta non ha risposto entro $probeTimeoutSeconds secondi. " +
                        "Può essere corretta ma lenta: finché non risponde non si può attivare l'incrementale", e
            )
        } catch (e: QueryNonValidaException) {
            throw QueryNonValidaException("La $etichetta non è valida: ${e.message}", e)
        }

        val attese = colonneUnita.map { it.lowercase() }.toSet()
        val reali = esito.colonne.map { it.lowercase() }.toSet()
        if (attese != reali || esito.colonne.size != colonneUnita.size) {
            throw QueryNonValidaException(
                "La $etichetta restituisce le colonne [${esito.colonne.joinToString(", ")}] ma l'unità è " +
                        "[${colonneUnita.joinToString(", ")}]: devono coincidere (stessi nomi)"
            )
        }
        return esito
    }

    /**
     * Salva la configurazione. Per l'INCREMENTALE controlla tutto e prova le
     * query; per la COMPLETA salva senza provare. Salvare non tocca
     * `ultimaSyncInizio`.
     */
    fun salva(sync: TableSync): TableSync {
        adminGuard.requireAdmin()
        val tabella = importedTableRepository.findById(sync.importedTableId)
            ?: throw IllegalArgumentException("Tabella importata non trovata")

        require(sync.margineSecondi in 0..MAX_MARGINE_SECONDI) {
            "Il margine deve essere tra 0 e $MAX_MARGINE_SECONDI secondi"
        }

        if (sync.modalita == ModalitaSync.INCREMENTALE) {
            require(sync.colonneUnita.isNotEmpty()) {
                "Indica le colonne che identificano l'unità da sostituire"
            }
            val doppie = sync.colonneUnita.groupingBy { it.lowercase() }.eachCount().filterValues { it > 1 }.keys
            require(doppie.isEmpty()) { "Colonne dell'unità ripetute: ${doppie.joinToString(", ")}" }

            val importate = importedTableRepository.findColumnsByTable(tabella.id).map { it.nome.lowercase() }.toSet()
            val mancanti = sync.colonneUnita.filter { it.lowercase() !in importate }
            require(mancanti.isEmpty()) {
                "Le colonne dell'unità non sono tra quelle importate: ${mancanti.joinToString(", ")}. " +
                        "Includile nella tabella: servono per sostituire le righe"
            }

            require(!sync.queryCambiati.isNullOrBlank()) {
                "Indica la query che restituisce le unità cambiate"
            }
            require(sync.datastampVerificato) {
                "Per attivare l'incrementale conferma che il datastamp è scritto dall'orologio del database " +
                        "sorgente e che lo hai verificato con una modifica reale"
            }

            provaQuery(tabella.id, sync.queryCambiati, sync.colonneUnita, sync.margineSecondi, "query dei cambiati")
            if (!sync.querySempre.isNullOrBlank()) {
                provaQuery(tabella.id, sync.querySempre, sync.colonneUnita, sync.margineSecondi, "query delle unità da rileggere sempre")
            }
        }
        val precedente = tableSyncRepository.findByTable(sync.importedTableId)
        val salvata = tableSyncRepository.save(sync)
        // Se cambiano le colonne che identificano l'unità, le righe già caricate
        // seguono la vecchia regola e le cancellazioni agirebbero sulle chiavi
        // sbagliate: il giro dopo deve essere completo.
        val unitaCambiata = precedente != null && precedente.ultimaSyncInizio != null &&
                precedente.colonneUnita.map { it.lowercase() }.toSet() != sync.colonneUnita.map { it.lowercase() }.toSet()
        if (unitaCambiata) tableSyncRepository.resetUltimaSync(sync.importedTableId)
        return salvata
    }

    companion object {
        private const val MAX_QUERY = 10_000
        private const val MAX_MARGINE_SECONDI = 7 * 24 * 3600
    }
}