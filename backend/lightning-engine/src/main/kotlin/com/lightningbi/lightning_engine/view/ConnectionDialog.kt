package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.connector.ConnectionOrchestrator
import com.lightningbi.lightning_engine.connector.JdbcSourceConnector
import com.lightningbi.lightning_engine.model.SourceConnection
import com.lightningbi.lightning_engine.service.ColumnProposal
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.PasswordField
import com.vaadin.flow.component.textfield.TextField

/**
 * Creazione e modifica di una connessione a una sorgente dati, con le due
 * proprietà di configurazione che sostituiscono i nomi fissi di un gestionale:
 * i prefissi da togliere dai nomi delle tabelle e i prefissi dei nomi di
 * colonna che indicano chiavi tecniche (vedi [ColumnProposal]).
 *
 * Una connessione nuova che non funziona non resta salvata. In modifica la
 * password lasciata vuota non cambia; gli altri parametri della connessione
 * si conservano.
 */
class ConnectionDialog(
    private val connectionOrchestrator: ConnectionOrchestrator,
    private val esistente: SourceConnection?,
    private val onSaved: () -> Unit
) : Dialog() {

    private val nomeField = TextField("Nome della connessione")
    private val tipoCombo = ComboBox<String>("Tipo database")
    private val jdbcUrlField = TextField("Indirizzo database (JDBC URL)")
    private val databaseField = TextField("Nome database")
    private val usernameField = TextField("Utente")
    private val passwordField = PasswordField("Password")
    private val prefissiTabelleField = TextField("Prefissi da togliere dai nomi delle tabelle")
    private val prefissiChiaveField = TextField("Prefissi delle colonne chiave")

    init {
        headerTitle = if (esistente == null) "Nuova connessione" else "Modifica connessione"
        width = "560px"

        nomeField.apply {
            value = esistente?.nome ?: ""
            setWidthFull()
        }
        tipoCombo.apply {
            setItems(connectionOrchestrator.tipiDisponibili())
            value = esistente?.tipo
            // Il tipo non si cambia in modifica: i parametri dipendono dal connettore.
            isEnabled = esistente == null
            setWidthFull()
        }
        jdbcUrlField.apply {
            placeholder = "jdbc:sqlserver://;serverName=host;databaseName=nome;trustServerCertificate=true"
            value = esistente?.parametri?.get(JdbcSourceConnector.PARAM_JDBC_URL) ?: ""
            setWidthFull()
        }
        databaseField.apply {
            helperText = "Usato nel nome delle tabelle importate"
            value = esistente?.parametri?.get(JdbcSourceConnector.PARAM_DATABASE) ?: ""
            setWidthFull()
        }
        usernameField.apply {
            value = esistente?.parametri?.get(JdbcSourceConnector.PARAM_USERNAME) ?: ""
            setWidthFull()
        }
        passwordField.apply {
            if (esistente != null) helperText = "Lascia vuoto per non cambiarla"
            setWidthFull()
        }
        prefissiTabelleField.apply {
            helperText = "Separati da virgola. Si tolgono dal nome della tabella per proporre il nome logico"
            value = esistente?.let { ColumnProposal.lista(it.parametri, ColumnProposal.PREFISSI_DA_TOGLIERE).joinToString(", ") } ?: ""
            setWidthFull()
        }
        prefissiChiaveField.apply {
            helperText = "Separati da virgola. Le colonne con questo prefisso sono chiavi tecniche: " +
                    "proposte come \"Ignora\" e prime candidate per il collegamento"
            value = esistente?.let { ColumnProposal.lista(it.parametri, ColumnProposal.PREFISSI_COLONNA_CHIAVE).joinToString(", ") } ?: ""
            setWidthFull()
        }

        // Il nome database si propone dall'indirizzo, finché non lo si scrive a mano.
        var ultimoDerivato = deriveDatabaseName(jdbcUrlField.value ?: "")
        jdbcUrlField.addValueChangeListener { ev ->
            if (!ev.isFromClient) return@addValueChangeListener
            val derivato = deriveDatabaseName(ev.value ?: "")
            if (databaseField.value.isNullOrBlank() || databaseField.value == ultimoDerivato) {
                databaseField.value = derivato
            }
            ultimoDerivato = derivato
        }

        val layout = VerticalLayout(
            nomeField, tipoCombo, jdbcUrlField, databaseField, usernameField, passwordField,
            prefissiTabelleField, prefissiChiaveField
        ).apply { isPadding = false }
        add(layout)

        val salva = Button(if (esistente == null) "Salva e prova" else "Aggiorna e prova") { salvaEProva() }
            .apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        footer.add(Button("Annulla") { close() }, salva)
    }

    private fun salvaEProva() {
        val nome = nomeField.value?.trim().orEmpty()
        val tipo = tipoCombo.value
        val url = jdbcUrlField.value?.trim().orEmpty()
        val database = databaseField.value?.trim().orEmpty()
        val utente = usernameField.value?.trim().orEmpty()
        val password = passwordField.value.orEmpty()

        if (nome.isBlank() || tipo == null || url.isBlank() || database.isBlank() || utente.isBlank() ||
            (esistente == null && password.isEmpty())
        ) {
            Notification.show("Compila nome, tipo, indirizzo, database, utente e password")
            return
        }

        // Si parte dai parametri esistenti: quelli che questa finestra non gestisce si conservano.
        val parametri = (esistente?.parametri ?: emptyMap()).toMutableMap().apply {
            put(JdbcSourceConnector.PARAM_JDBC_URL, url)
            put(JdbcSourceConnector.PARAM_USERNAME, utente)
            put(JdbcSourceConnector.PARAM_DATABASE, database)
            impostaLista(this, ColumnProposal.PREFISSI_DA_TOGLIERE, prefissiTabelleField.value)
            impostaLista(this, ColumnProposal.PREFISSI_COLONNA_CHIAVE, prefissiChiaveField.value)
        }
        val segreti = mapOf(JdbcSourceConnector.SECRET_PASSWORD to password)

        try {
            val connessione = if (esistente != null) {
                connectionOrchestrator.update(esistente.id, nome, parametri, segreti)
            } else {
                connectionOrchestrator.create(nome, tipo, parametri, segreti)
            }
            try {
                connectionOrchestrator.testConnection(connessione.id)
            } catch (e: Exception) {
                if (esistente == null) {
                    // Appena creata e non funziona: non la si lascia salvata.
                    try { connectionOrchestrator.delete(connessione.id) } catch (_: Exception) { }
                    throw IllegalStateException("La connessione non funziona, non è stata salvata: ${e.message}", e)
                }
                throw IllegalStateException("Connessione aggiornata, ma la prova è fallita: ${e.message}", e)
            }
            Notification.show("Connessione salvata e provata")
            close()
            onSaved()
        } catch (e: Exception) {
            Notification.show("Errore: ${e.message}", 6000, Notification.Position.MIDDLE)
            if (esistente != null) onSaved()
        }
    }

    /** Scrive una lista (separata da virgola) nei parametri; se è vuota toglie la proprietà. */
    private fun impostaLista(parametri: MutableMap<String, String>, proprieta: String, valore: String?) {
        val elementi = valore.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (elementi.isEmpty()) parametri.remove(proprieta) else parametri[proprieta] = elementi.joinToString(",")
    }

    /** Nome del database dall'indirizzo JDBC (SQL Server: databaseName=..., PostgreSQL: /nome). */
    private fun deriveDatabaseName(url: String): String {
        Regex("databaseName=([^;]+)", RegexOption.IGNORE_CASE).find(url)?.let { return it.groupValues[1].trim() }
        Regex("^jdbc:postgresql://[^/]+/([^?;]+)").find(url)?.let { return it.groupValues[1].trim() }
        return ""
    }
}