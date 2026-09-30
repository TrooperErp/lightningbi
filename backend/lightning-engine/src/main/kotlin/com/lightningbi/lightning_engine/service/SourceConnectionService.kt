package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.SourceConnection
import com.lightningbi.lightning_engine.repository.SourceConnectionRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID
import org.springframework.dao.DataIntegrityViolationException

/**
 * Gestione delle connessioni alle sorgenti dati: crea, modifica, elimina.
 *
 * È l'UNICO punto in cui i segreti (password, token) si cifrano e si
 * decifrano: nel repository stanno sempre cifrati, e chi ne ha bisogno
 * (i connettori) li chiede a decryptSecret() al momento dell'uso.
 *
 * Non sa quali tipi di sorgente esistano: che a un codice tipo corrisponda
 * un connettore lo verifica ConnectionOrchestrator. Il servizio controlla
 * solo che il codice sia ben formato (è lo stesso usato per il prefisso
 * dei nomi delle tabelle, vedi Naming.importedTable).
 */
@Service
class SourceConnectionService(
    private val repository: SourceConnectionRepository,
    private val cryptoService: CryptoService
) {
    private val formatoTipo = Regex("^[a-z][a-z0-9_]*$")

    fun findAll(): List<SourceConnection> = repository.findAll()

    fun findById(id: UUID): SourceConnection? = repository.findById(id)

    /**
     * @param parametri valori non riservati; quelli vuoti vengono scartati
     * @param segretiInChiaro valori riservati in chiaro: si cifrano qui
     */
    fun create(
        nome: String,
        tipo: String,
        parametri: Map<String, String>,
        segretiInChiaro: Map<String, String>
    ): SourceConnection {
        val nomePulito = validaNome(nome, escludiId = null)
        val tipoPulito = validaTipo(tipo)

        val connection = SourceConnection(
            id = UUID.randomUUID(),
            nome = nomePulito,
            tipo = tipoPulito,
            parametri = pulisci(parametri),
            segreti = cifra(segretiInChiaro),
            createdAt = Instant.now()
        )
        repository.save(connection)
        return connection
    }

    /**
     * Il tipo non si cambia: parametri e segreti dipendono da lui.
     *
     * @param segretiInChiaro solo i segreti da CAMBIARE: una chiave assente
     *   o con valore vuoto lascia il segreto com'è (così in modifica non
     *   si deve riscrivere la password).
     */
    fun update(
        id: UUID,
        nome: String,
        parametri: Map<String, String>,
        segretiInChiaro: Map<String, String>
    ): SourceConnection {
        val esistente = repository.findById(id) ?: error("Connessione $id non trovata")
        val nomePulito = validaNome(nome, escludiId = id)

        val aggiornata = esistente.copy(
            nome = nomePulito,
            parametri = pulisci(parametri),
            segreti = esistente.segreti + cifra(segretiInChiaro)
        )
        repository.save(aggiornata)
        return aggiornata
    }

    fun delete(id: UUID) {
        val connessione = requireNotNull(repository.findById(id)) { "Connessione $id non trovata" }
        try {
            repository.delete(id)
        } catch (e: DataIntegrityViolationException) {
            throw IllegalStateException(
                "La connessione \"${connessione.nome}\" è in uso e non si può eliminare: " +
                        "elimina prima i dataset o le tabelle che la usano",
                e
            )
        }
    }

    /**
     * Segreto in chiaro. Da chiamare solo nel punto in cui serve davvero
     * (apertura della connessione), senza tenerlo in giro.
     */
    fun decryptSecret(connection: SourceConnection, chiave: String): String {
        val cifrato = connection.segreti[chiave]
            ?: error("Il segreto '$chiave' manca nella connessione '${connection.nome}'")
        return cryptoService.decrypt(cifrato)
    }

    // ---------- interni ----------

    private fun validaNome(nome: String, escludiId: UUID?): String {
        val pulito = nome.trim()
        require(pulito.isNotEmpty()) { "Indica il nome della connessione" }
        val altra = repository.findByNome(pulito)
        require(altra == null || altra.id == escludiId) { "Esiste già una connessione chiamata \"$pulito\"" }
        return pulito
    }

    private fun validaTipo(tipo: String): String {
        val pulito = tipo.trim()
        require(formatoTipo.matches(pulito)) {
            "Tipo di sorgente non valido: '$tipo' (solo minuscole, cifre e underscore, es. mssql)"
        }
        return pulito
    }

    private fun pulisci(parametri: Map<String, String>): Map<String, String> =
        parametri.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }

    /** Cifra i valori non vuoti; i vuoti si scartano. */
    private fun cifra(segretiInChiaro: Map<String, String>): Map<String, String> =
        segretiInChiaro.filterValues { it.isNotEmpty() }.mapValues { cryptoService.encrypt(it.value) }
}