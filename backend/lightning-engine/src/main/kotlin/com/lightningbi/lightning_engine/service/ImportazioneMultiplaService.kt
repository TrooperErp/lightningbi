package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.connector.ConnectionOrchestrator
import com.lightningbi.lightning_engine.model.RuoloTabella
import com.lightningbi.lightning_engine.repository.ImportedTableRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/** Una tabella o view della sorgente, con l'indicazione se è già stata importata. */
data class VistaSorgente(val nomeOrigine: String, val giaImportata: Boolean)

/** Cosa importare: la tabella e il ruolo che avrà (Dimensione o Fatti). */
data class RichiestaImport(val nomeOrigine: String, val ruolo: RuoloTabella)

enum class EsitoImport { IMPORTATA, SALTATA, ERRORE }

/** Il risultato per una tabella: il nome logico dato (se importata) e il motivo di una eventuale mancata importazione. */
data class RisultatoImport(
    val nomeOrigine: String,
    val nomeLogico: String?,
    val esito: EsitoImport,
    val dettaglio: String? = null
)

/**
 * Importazione di MOLTE tabelle o view di una sorgente in un colpo solo, come quando
 * si importano più tabelle nel Data manager di Qlik: si registra la loro definizione,
 * senza caricare dati (i dati arrivano con la sincronizzazione).
 *
 * Per ogni tabella propone da sola, dalle proprietà della connessione:
 * - il nome logico, togliendo i prefissi ([ColumnProposal.PREFISSI_DA_TOGLIERE]);
 * - le colonne chiave, quelle con un prefisso tecnico ([ColumnProposal.PREFISSI_COLONNA_CHIAVE]).
 *
 * Tutte le colonne si importano. Una tabella che non si può importare (nome già
 * usato non risolvibile, colonne che collidono, errore della sorgente) non ferma le
 * altre: finisce nel rapporto con il motivo.
 *
 * Non controlla l'admin: lo fa la pagina una volta, nel suo thread, prima di avviare
 * l'importazione in background (il controllo legge la sessione del browser).
 */
@Service
class ImportazioneMultiplaService(
    private val connectionOrchestrator: ConnectionOrchestrator,
    private val importedTableRepository: ImportedTableRepository,
    private val tableImportService: TableImportService
) {
    private val log = LoggerFactory.getLogger(ImportazioneMultiplaService::class.java)

    /** Le tabelle e view dello schema, in ordine di nome, con l'indicazione di quelle già importate. */
    fun elenco(connectionId: UUID, schema: String?): List<VistaSorgente> {
        val importate = importedTableRepository.findAll()
            .filter { it.connectionId == connectionId && it.schemaOrigine == schema }
            .map { it.nomeOrigine?.lowercase() }
            .toSet()
        return connectionOrchestrator.listTables(connectionId, schema)
            .map { it.name }
            .distinct()
            .sorted()
            .map { VistaSorgente(it, it.lowercase() in importate) }
    }

    /**
     * Importa le tabelle richieste, una dopo l'altra. [progresso] riceve una frase per ogni
     * tabella ("12 di 78 · CLIENTI"). Restituisce un risultato per ogni richiesta.
     */
    fun importa(
        connectionId: UUID,
        schema: String?,
        richieste: List<RichiestaImport>,
        progresso: (String) -> Unit = {}
    ): List<RisultatoImport> {
        val connessione = connectionOrchestrator.findById(connectionId)
            ?: throw IllegalArgumentException("Connessione non trovata")
        val prefissiDaTogliere = ColumnProposal.lista(connessione.parametri, ColumnProposal.PREFISSI_DA_TOGLIERE)
        val prefissiChiave = ColumnProposal.lista(connessione.parametri, ColumnProposal.PREFISSI_COLONNA_CHIAVE)
        log.info(
            "Importazione di {} tabelle da '{}' (prefissi da togliere: {}, prefissi chiave: {})",
            richieste.size, connessione.nome, prefissiDaTogliere, prefissiChiave
        )

        val esistenti = importedTableRepository.findAll()
        val nomiUsati = esistenti.map { it.nomeLogico.lowercase() }.toMutableSet()
        val fisiciUsati = importedTableRepository.findTabelleFisiche().toMutableSet()
        val giaImportate = esistenti
            .filter { it.connectionId == connectionId && it.schemaOrigine == schema }
            .mapNotNull { it.nomeOrigine?.lowercase() }
            .toSet()

        val risultati = mutableListOf<RisultatoImport>()
        richieste.forEachIndexed { i, richiesta ->
            progresso("${i + 1} di ${richieste.size} · ${richiesta.nomeOrigine}")

            if (richiesta.nomeOrigine.lowercase() in giaImportate) {
                risultati += RisultatoImport(richiesta.nomeOrigine, null, EsitoImport.SALTATA, "Già importata")
                return@forEachIndexed
            }

            try {
                val colonneSorgente = connectionOrchestrator.listColumns(connectionId, schema, richiesta.nomeOrigine)
                require(colonneSorgente.isNotEmpty()) { "La sorgente non restituisce nessuna colonna" }

                val nomiChiave = colonneSorgente.map { it.name }
                    .filter { nome -> prefissiChiave.any { nome.startsWith(it, ignoreCase = true) } }
                // Una Dimensione ha una sola colonna chiave (la prima); un Fatti le tiene tutte.
                val chiaviMarcate = when (richiesta.ruolo) {
                    RuoloTabella.DIMENSIONE -> nomiChiave.take(1).toSet()
                    RuoloTabella.FATTI -> nomiChiave.toSet()
                }
                val colonne = colonneSorgente.map { ColonnaImport(it.name, it.typeName, it.name in chiaviMarcate) }
                val colonnaChiave = if (richiesta.ruolo == RuoloTabella.DIMENSIONE) chiaviMarcate.firstOrNull() else null

                val nomeLogico = nomeLibero(
                    connessione, schema, ColumnProposal.nomeLogico(richiesta.nomeOrigine, prefissiDaTogliere),
                    nomiUsati, fisiciUsati
                )
                tableImportService.aggiungiSenzaControllo(
                    connectionId = connectionId,
                    schema = schema,
                    nomeOrigine = richiesta.nomeOrigine,
                    nomeLogico = nomeLogico,
                    ruolo = richiesta.ruolo,
                    colonnaChiave = colonnaChiave,
                    colonne = colonne
                )
                nomiUsati += nomeLogico.lowercase()
                fisiciUsati += tableImportService.nomeFisico(connessione, schema, nomeLogico)
                risultati += RisultatoImport(richiesta.nomeOrigine, nomeLogico, EsitoImport.IMPORTATA)
            } catch (e: Exception) {
                log.warn("Importazione di '{}' fallita", richiesta.nomeOrigine, e)
                risultati += RisultatoImport(
                    richiesta.nomeOrigine, null, EsitoImport.ERRORE, e.message ?: e::class.simpleName
                )
            }
        }
        return risultati
    }

    /**
     * Un nome logico non ancora usato (né come nome né come nome fisico): se quello proposto
     * esiste già, prende un suffisso (_2, _3...).
     */
    private fun nomeLibero(
        connessione: com.lightningbi.lightning_engine.model.SourceConnection,
        schema: String?,
        proposto: String,
        nomiUsati: Set<String>,
        fisiciUsati: Set<String>
    ): String {
        var candidato = proposto
        var n = 2
        while (candidato.lowercase() in nomiUsati ||
            tableImportService.nomeFisico(connessione, schema, candidato) in fisiciUsati
        ) {
            candidato = "${proposto}_$n"
            n++
        }
        return candidato
    }
}