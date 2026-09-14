package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.*
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.service.*
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.textfield.PasswordField
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Wizard unificato "Nuova Analisi". Sostituisce AddAreaWizardDialog +
 * la parte di mapping di ConfigureSourceDialog.
 *
 * Principi guida (decisioni prese in sessione):
 * - Discovery-first: prima si vedono le colonne reali della sorgente
 *   (con tuple di esempio), poi si costruisce l'Analisi sopra.
 * - Solo colonna diretta, NESSUN join/lookup al volo nel wizard.
 *   Se serve incrociare più tabelle, si prepara prima una view sul
 *   database di origine con il join già fatto (portabilità: se cambia
 *   il motore DB origine, cambia solo la view).
 * - Una sorgente fisica (tabella o view sul DB origine, indistinguibili
 *   dal punto di vista di LightningBI) può alimentare più Analisi diverse.
 * - I nomi fisici NON si costruiscono qui: si passa sempre da Naming.
 * - Le dimensioni si RIUSANO se esistono già con lo stesso nome fisico:
 *   crearne di nuove ogni volta produce symbol table duplicate e rompe
 *   il concetto di dimensione conformata.
 */
class NewAnalysisWizardDialog(
    private val registryService: RegistryService,
    private val registryRepository: RegistryRepository,
    private val symbolTableService: SymbolTableService,
    private val areaSourceRepository: AreaSourceRepository,
    private val cryptoService: CryptoService,
    private val metadataService: MetadataService,
    private val viewSqlGenerator: ViewSqlGenerator,
    private val onAreaCreated: () -> Unit
) : Dialog() {

    private val driverOptions = listOf(
        "com.microsoft.sqlserver.jdbc.SQLServerDriver" to "SQL Server",
        "org.postgresql.Driver" to "PostgreSQL",
        "com.mysql.cj.jdbc.Driver" to "MySQL",
        "oracle.jdbc.OracleDriver" to "Oracle",
        "com.ibm.db2.jcc.DB2Driver" to "DB2"
    )

    /** Colonna reale scoperta sulla sorgente, con dati di esempio. */
    private data class DiscoveredColumn(
        val name: String,
        val type: String,
        val sample: String
    )

    /** Scelta dell'utente per una colonna scoperta. */
    private data class ColumnChoice(
        val column: DiscoveredColumn,
        var included: Boolean = false,
        var role: String = "Filtro"   // "Filtro" | "Somma"
    )

    private var connection: Connection? = null
    private var selectedSchema: String? = null
    private var selectedTable: TableInfo? = null
    private var selectedDriver: String? = null
    private var jdbcUrlValue: String = ""
    private var usernameValue: String = ""
    private var passwordValue: String = ""
    private var reusingExistingSource: AreaSource? = null

    private var discoveredColumns: List<ColumnChoice> = emptyList()
    private var rawSamples: List<Map<String, Any?>> = emptyList()

    private var updatedAtCombo: ComboBox<String>? = null
    private var viewNameField: TextField? = null
    private var nomeAreaField: TextField? = null
    private var viewNameTouched = false

    private val sqlPreviewArea = TextArea("Anteprima SQL (CREATE VIEW)").apply {
        isReadOnly = true
        setWidthFull()
        height = "160px"
    }

    private val content = VerticalLayout().apply { className = "lbi-wizard-content" }

    init {
        className = "lbi-wizard-dialog"
        width = "920px"
        headerTitle = "Nuova Analisi"
        isCloseOnEsc = false
        isCloseOnOutsideClick = false

        // La connessione JDBC va chiusa comunque il dialog si chiuda,
        // annullamento incluso: prima restava aperta in caso di abbandono.
        addDetachListener { closeConnectionQuietly() }

        add(content)
        showStepOrigin()
    }

    private fun closeConnectionQuietly() {
        try { connection?.close() } catch (_: Exception) { }
        connection = null
    }

    // ================= STEP 0: origine dati =================
    private fun showStepOrigin() {
        content.removeAll()
        content.add(Span("Da dove arrivano i dati per questa analisi?").apply { className = "lbi-wizard-label" })

        val existingSources = try {
            areaSourceRepository.findAll()
        } catch (e: Exception) {
            Notification.show("Impossibile leggere le sorgenti esistenti: ${e.message}", 5000, Notification.Position.MIDDLE)
            emptyList()
        }

        val modeGroup = RadioButtonGroup<String>().apply {
            setItems("Sorgente già collegata", "Nuova sorgente esterna")
            value = if (existingSources.isNotEmpty()) "Sorgente già collegata" else "Nuova sorgente esterna"
            isEnabled = existingSources.isNotEmpty()
        }

        if (existingSources.isEmpty()) {
            content.add(Span("Nessuna sorgente ancora collegata: si parte da una nuova connessione.").apply {
                className = "lbi-wizard-label"
            })
        }

        val existingCombo = ComboBox<AreaSource>("Sorgente esistente").apply {
            setItems(existingSources)
            setItemLabelGenerator { src ->
                val areaNome = try {
                    registryRepository.findAreaById(src.areaId)?.nome
                } catch (_: Exception) { null }
                "${src.config.schema}.${src.config.mainTable}" + (areaNome?.let { " (già usata da: $it)" } ?: "")
            }
            setWidthFull()
            isVisible = existingSources.isNotEmpty()
        }

        modeGroup.addValueChangeListener {
            existingCombo.isVisible = it.value == "Sorgente già collegata"
        }

        content.add(modeGroup, existingCombo)

        val cancelButton = Button("Annulla") { close() }
        val nextButton = Button("Avanti →") {
            if (modeGroup.value == "Sorgente già collegata") {
                val src = existingCombo.value
                if (src == null) {
                    Notification.show("Seleziona una sorgente")
                    return@Button
                }
                reuseExistingSource(src)
            } else {
                reusingExistingSource = null
                showStepConnect()
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        content.add(HorizontalLayout(cancelButton, nextButton).apply { className = "lbi-wizard-actions" })
    }

    private fun reuseExistingSource(src: AreaSource) {
        // Le credenziali NON persistono tra un wizard e l'altro: anche riusando
        // una sorgente già configurata, l'utente deve ridigitare la password.
        // Pre-compiliamo solo i campi non sensibili.
        reusingExistingSource = src
        jdbcUrlValue = src.config.jdbcUrl
        usernameValue = src.config.username
        selectedDriver = src.config.driverClassName
        selectedSchema = src.config.schema
        showStepConnect()
    }

    // ================= STEP 1: connessione =================
    private fun showStepConnect() {
        content.removeAll()
        content.add(Span("Connessione al database di origine").apply { className = "lbi-wizard-label" })

        reusingExistingSource?.let { reused ->
            content.add(Span("Sorgente già configurata: $selectedSchema.${reused.config.mainTable}. Reinserisci la password per connetterti.").apply {
                className = "lbi-wizard-label"
            })
        }

        val jdbcUrlField = TextField("Indirizzo database (JDBC URL)").apply {
            placeholder = "jdbc:sqlserver://server:1433;databaseName=ERP;trustServerCertificate=true"
            value = jdbcUrlValue
            setWidthFull()
        }
        val driverCombo = ComboBox<Pair<String, String>>("Tipo database").apply {
            setItems(driverOptions)
            setItemLabelGenerator { it.second }
            value = driverOptions.find { it.first == selectedDriver }
            setWidthFull()
        }
        val usernameField = TextField("Utente").apply { value = usernameValue; setWidthFull() }
        val passwordField = PasswordField("Password").apply { setWidthFull() }

        content.add(jdbcUrlField, driverCombo, usernameField, passwordField)

        val backButton = Button("← Indietro") { showStepOrigin() }
        val connectButton = Button("Connetti →") {
            val driver = driverCombo.value
            if (jdbcUrlField.value.isNullOrBlank() || driver == null || usernameField.value.isNullOrBlank()) {
                Notification.show("Compila tutti i campi")
                return@Button
            }
            try {
                closeConnectionQuietly()
                jdbcUrlValue = jdbcUrlField.value
                usernameValue = usernameField.value
                passwordValue = passwordField.value
                selectedDriver = driver.first
                connection = metadataService.connect(jdbcUrlValue, usernameValue, passwordValue, driver.first)
                Notification.show("Connessione riuscita")

                val reused = reusingExistingSource
                if (reused != null) {
                    selectedTable = metadataService.listTables(connection!!, selectedSchema)
                        .find { it.name == reused.config.mainTable }
                    if (selectedTable == null) {
                        Notification.show(
                            "Tabella \"${reused.config.mainTable}\" non trovata con queste credenziali",
                            6000, Notification.Position.MIDDLE
                        )
                        return@Button
                    }
                    runDiscovery()
                } else {
                    showStepTable()
                }
            } catch (e: Exception) {
                Notification.show("Errore di connessione: ${e.message}", 6000, Notification.Position.MIDDLE)
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        content.add(HorizontalLayout(backButton, connectButton).apply { className = "lbi-wizard-actions" })
    }

    // ================= STEP 2: schema + tabella/view =================
    private fun showStepTable() {
        content.removeAll()
        content.add(Span("Scegli la tabella o view con i dati (es. Ordini, Vendite)").apply {
            className = "lbi-wizard-label"
        })
        content.add(Span("Serve incrociare più tabelle? Prepara prima una view sul database di origine con il join già fatto, poi collegala qui.").apply {
            className = "lbi-wizard-label"
        })

        val conn = connection ?: run {
            Notification.show("Connessione non disponibile, torna indietro")
            return
        }

        val schemaCombo = ComboBox<String>("Schema").apply {
            setItems(try { metadataService.listSchemas(conn) } catch (e: Exception) { emptyList() })
            value = selectedSchema
            setWidthFull()
        }
        val tableSearchField = TextField("Cerca tabella").apply {
            placeholder = "Digita per filtrare..."
            setWidthFull()
        }
        val tableCombo = ComboBox<TableInfo>("Tabella o view principale").apply {
            setItemLabelGenerator { it.name }
            setWidthFull()
        }

        var allTables: List<TableInfo> = emptyList()
        schemaCombo.addValueChangeListener { event ->
            val schema = event.value ?: return@addValueChangeListener
            allTables = try { metadataService.listTables(conn, schema) } catch (e: Exception) { emptyList() }
            tableCombo.setItems(allTables)
        }
        tableSearchField.addValueChangeListener { event ->
            val q = event.value
            tableCombo.setItems(if (q.isNullOrBlank()) allTables else allTables.filter { it.name.contains(q, true) })
        }

        content.add(schemaCombo, tableSearchField, tableCombo)

        val backButton = Button("← Indietro") { showStepConnect() }
        val nextButton = Button("Avanti →") {
            if (tableCombo.value == null) {
                Notification.show("Seleziona una tabella o view")
                return@Button
            }
            selectedSchema = schemaCombo.value
            selectedTable = tableCombo.value
            runDiscovery()
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        content.add(HorizontalLayout(backButton, nextButton).apply { className = "lbi-wizard-actions" })
    }

    // ================= STEP 3: discovery colonne =================
    private fun runDiscovery() {
        val conn = connection ?: return
        val table = selectedTable ?: return
        try {
            val columns = metadataService.listColumns(conn, selectedSchema, table.name)
            rawSamples = try {
                metadataService.sampleRows(conn, selectedSchema, table.name, 3)
            } catch (e: Exception) {
                emptyList()
            }

            discoveredColumns = columns.map { col ->
                val exampleValues = rawSamples.mapNotNull { row -> row[col.name]?.toString() }.take(3)
                ColumnChoice(
                    DiscoveredColumn(
                        name = col.name,
                        type = col.typeName,
                        sample = if (exampleValues.isEmpty()) "—" else exampleValues.joinToString(", ")
                    )
                )
            }
            showStepDiscovery()
        } catch (e: Exception) {
            Notification.show("Errore lettura colonne: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
    }

    private fun showStepDiscovery() {
        content.removeAll()
        content.add(Span("Seleziona le colonne da usare in questa analisi, indica il ruolo, e guarda i dati reali sotto").apply {
            className = "lbi-wizard-label"
        })

        val grid = Grid<Map<String, Any?>>().apply {
            setItems(rawSamples)
            setWidthFull()
            height = "260px"
        }

        discoveredColumns.forEach { choice ->
            val roleGroup = RadioButtonGroup<String>().apply {
                setItems("Filtro", "Somma")
                value = choice.role
                isVisible = choice.included
                addValueChangeListener {
                    choice.role = it.value
                    updateSqlPreview()
                }
            }
            val checkbox = Checkbox().apply {
                value = choice.included
                addValueChangeListener {
                    choice.included = it.value
                    roleGroup.isVisible = it.value
                    updateUpdatedAtOptions()
                    updateSqlPreview()
                }
            }

            // Nome fisico mostrato accanto a quello originale: rende evidente
            // con cosa verrà davvero creata la colonna su ClickHouse.
            val fisico = try { Naming.column(choice.column.name) } catch (e: Exception) { "?" }

            val headerBox = VerticalLayout(
                Span(choice.column.name).apply { style.set("font-weight", "600") },
                Span("${choice.column.type} → $fisico").apply {
                    style.set("font-size", "11px")
                    style.set("color", "var(--lbi-text-muted)")
                },
                checkbox,
                roleGroup
            ).apply {
                isPadding = false
                isSpacing = false
                style.set("gap", "4px")
            }

            grid.addColumn { row -> row[choice.column.name]?.toString() ?: "" }
                .setHeader(headerBox)
                .setAutoWidth(true)
        }

        content.add(grid)

        updatedAtCombo = ComboBox<String>("Colonna data ultima modifica").apply {
            setWidthFull()
            addValueChangeListener { updateSqlPreview() }
        }
        updateUpdatedAtOptions()

        viewNameField = TextField("Nome view").apply {
            setWidthFull()
            // Se l'utente lo tocca a mano, smettiamo di sovrascriverlo
            // quando digita il nome dell'analisi.
            addValueChangeListener { if (it.isFromClient) viewNameTouched = true }
        }

        content.add(updatedAtCombo, viewNameField)
        content.add(Span("Anteprima").apply { className = "lbi-wizard-label" })
        content.add(sqlPreviewArea)
        updateSqlPreview()

        val backButton = Button("← Indietro") {
            if (reusingExistingSource != null) showStepOrigin() else showStepTable()
        }
        val nextButton = Button("Avanti →") {
            val err = validateDiscovery()
            if (err != null) {
                Notification.show(err, 4000, Notification.Position.MIDDLE)
                return@Button
            }
            showStepConfirm()
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        content.add(HorizontalLayout(backButton, nextButton).apply { className = "lbi-wizard-actions" })
    }

    /** Ritorna il messaggio d'errore, o null se va tutto bene. */
    private fun validateDiscovery(): String? {
        val included = discoveredColumns.filter { it.included }
        if (included.isEmpty()) return "Seleziona almeno una colonna"
        if (included.none { it.role == "Filtro" }) return "Serve almeno un Filtro"
        if (included.none { it.role == "Somma" }) return "Serve almeno una Somma"
        if (updatedAtCombo?.value.isNullOrBlank()) return "Indica la colonna data ultima modifica"

        // Due colonne diverse possono collassare sullo stesso nome fisico
        // (es. "Cod Cliente" e "COD_CLIENTE"): va intercettato qui, non
        // al CREATE TABLE.
        val fisici = included.map {
            try { Naming.column(it.column.name) } catch (e: Exception) { return "Colonna non utilizzabile: ${it.column.name}" }
        }
        val collisioni = fisici.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (collisioni.isNotEmpty()) {
            return "Colonne che collidono dopo la normalizzazione: ${collisioni.joinToString(", ")}. Deselezionane una."
        }
        return null
    }

    private fun updateUpdatedAtOptions() {
        val combo = updatedAtCombo ?: return
        val included = discoveredColumns.filter { it.included }.map { it.column.name }
        val current = combo.value
        combo.setItems(included)
        if (current != null && current in included) combo.value = current
    }

    private fun updateSqlPreview() {
        val driver = selectedDriver ?: return
        val table = selectedTable?.name ?: return
        val included = discoveredColumns.filter { it.included }

        // La view deve produrre colonne con lo STESSO nome fisico usato per
        // creare la tabella ClickHouse, altrimenti l'ETL non le trova.
        val direct = included.mapNotNull {
            try { it.column.name to Naming.column(it.column.name) } catch (e: Exception) { null }
        }

        sqlPreviewArea.value = viewSqlGenerator.generate(
            driverClass = driver,
            viewName = currentViewName(),
            schema = selectedSchema,
            mainTable = table,
            directMappings = direct,
            lookups = emptyList(),
            updatedAtColumn = updatedAtCombo?.value ?: "updated_at"
        )
    }

    private fun currentViewName(): String {
        viewNameField?.value?.takeIf { it.isNotBlank() }?.let { return it }
        val nome = nomeAreaField?.value
        return if (!nome.isNullOrBlank()) Naming.viewName(nome) else "vw_lbi_nuova_analisi"
    }

    // ================= STEP 4: nome analisi + conferma =================
    private fun showStepConfirm() {
        content.removeAll()
        content.add(Span("Come vuoi chiamare questa analisi?").apply { className = "lbi-wizard-label" })

        nomeAreaField = TextField("Nome analisi").apply {
            placeholder = "es. Vendite 2026"
            setWidthFull()
            addValueChangeListener { ev ->
                val nome = ev.value
                if (!viewNameTouched && !nome.isNullOrBlank()) {
                    viewNameField?.value = try { Naming.viewName(nome) } catch (e: Exception) { "" }
                }
                updateSqlPreview()
            }
        }
        content.add(nomeAreaField)

        val included = discoveredColumns.filter { it.included }
        val filtri = included.filter { it.role == "Filtro" }
        val somme = included.filter { it.role == "Somma" }

        content.add(Span("Riepilogo").apply { className = "lbi-wizard-label" })
        content.add(Span("Filtri: ${filtri.joinToString(", ") { it.column.name }}"))
        content.add(Span("Somme: ${somme.joinToString(", ") { it.column.name }}"))

        // Mostra quali filtri riusano una dimensione già esistente: è
        // informazione che serve all'utente per capire se sta creando
        // un duplicato involontario.
        val riusate = filtri.mapNotNull { f ->
            registryService.findDimensioneByNomeFisico(f.column.name)?.let { "${f.column.name} → ${it.nome}" }
        }
        if (riusate.isNotEmpty()) {
            content.add(Span("Filtri collegati a dimensioni già esistenti: ${riusate.joinToString(", ")}").apply {
                className = "lbi-wizard-label"
            })
        }

        content.add(sqlPreviewArea)

        val backButton = Button("← Indietro") { showStepDiscovery() }
        val createButton = Button("Crea Analisi") { createAreaWithSource() }
            .apply { addThemeVariants(ButtonVariant.LUMO_SUCCESS) }

        content.add(HorizontalLayout(backButton, createButton).apply { className = "lbi-wizard-actions" })
    }

    // ================= Creazione: Analisi + Sorgente =================
    private fun createAreaWithSource() {
        val nomeArea = nomeAreaField?.value
        if (nomeArea.isNullOrBlank()) {
            Notification.show("Indica il nome dell'analisi")
            return
        }
        validateDiscovery()?.let {
            Notification.show(it, 5000, Notification.Position.MIDDLE)
            return
        }
        if (registryRepository.findAreaByNome(nomeArea) != null) {
            Notification.show("Esiste già un'analisi chiamata \"$nomeArea\"", 5000, Notification.Position.MIDDLE)
            return
        }

        var tabellaCreata: String? = null
        try {
            val included = discoveredColumns.filter { it.included }
            val filtri = included.filter { it.role == "Filtro" }
            val somme = included.filter { it.role == "Somma" }

            // I nomi fisici li decide SymbolTableService via Naming: qui si
            // passano i nomi logici e basta.
            val tabellaFisica = symbolTableService.createAreaTable(
                nomeArea,
                filtri.map { it.column.name },
                somme.map { it.column.name }
            )
            tabellaCreata = tabellaFisica

            val area = registryService.createArea(nomeArea, tabellaFisica)

            // I DirectMapping si raccolgono mentre si creano gli oggetti:
            // prima venivano ricercati a posteriori con una query dentro un
            // ciclo, che oltre a essere N+1 esplodeva su nomi ambigui.
            val direct = mutableListOf<DirectMapping>()

            filtri.forEach { f ->
                val dimensione = registryService.findOrCreateDimensione(f.column.name)
                val colonna = Naming.column(f.column.name)
                registryService.linkDimensioneToArea(area.id, dimensione.id, colonna, false, null)
                direct += DirectMapping(dimensione.id, colonna)
            }

            somme.forEach { s ->
                val colonna = Naming.column(s.column.name)
                val metrica = registryService.addMetrica(area.id, s.column.name, colonna, "SUM")
                direct += DirectMapping(metrica.id, colonna)
            }

            // Se si riusa una sorgente ma l'utente ha digitato una password
            // diversa, si salva quella nuova: prima si riusava sempre la
            // vecchia, lasciando la config disallineata.
            val encryptedPassword = cryptoService.encrypt(passwordValue)

            val config = SourceConfig(
                jdbcUrl = jdbcUrlValue,
                username = usernameValue,
                encryptedPassword = encryptedPassword,
                driverClassName = selectedDriver!!,
                schema = selectedSchema,
                mainTable = selectedTable!!.name,
                viewName = currentViewName(),
                directMappings = direct,
                lookups = emptyList(),
                syncMode = SyncMode.FULL_RELOAD
            )

            areaSourceRepository.save(
                AreaSource(
                    id = UUID.randomUUID(),
                    areaId = area.id,
                    tipoSorgente = "jdbc",
                    config = config,
                    status = SourceStatus.PENDING_VIEW,
                    errorDetail = null,
                    createdAt = Instant.now()
                )
            )

            Notification.show(
                "Analisi \"$nomeArea\" creata. Consegna l'SQL al DBA o crea la view automaticamente, poi verifica la sorgente da \"Sorgenti Dati\".",
                6000, Notification.Position.MIDDLE
            )
            onAreaCreated()
            close()
        } catch (e: Exception) {
            // La tabella ClickHouse non partecipa alla transazione Postgres:
            // se il salvataggio fallisce va rimossa a mano, altrimenti resta
            // orfana e il tentativo successivo trova una tabella già esistente
            // con uno schema potenzialmente diverso.
            tabellaCreata?.let {
                try { symbolTableService.dropTable(it) } catch (_: Exception) { }
            }
            Notification.show("Errore: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
    }
}