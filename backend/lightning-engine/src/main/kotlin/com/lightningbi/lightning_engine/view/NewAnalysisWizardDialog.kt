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
import com.vaadin.flow.component.html.Div
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
 *   dal punto di vista di LightningBI) può alimentare più Analisi
 *   diverse: se già configurata, si salta direttamente alla discovery
 *   colonne senza richiedere una nuova connessione.
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
        var role: String = "Filtro",   // "Filtro" | "Somma"
        var displayName: String = column.name
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
    private lateinit var updatedAtCombo: ComboBox<String>
    private lateinit var viewNameField: TextField
    private lateinit var nomeAreaField: TextField
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

        add(content)
        showStepOrigin()
    }

    private fun slugify(nome: String): String =
        nome.lowercase().trim()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), "_")

    // ================= STEP 0: origine dati =================
    private fun showStepOrigin() {
        content.removeAll()
        content.add(Span("Da dove arrivano i dati per questa analisi?").apply { className = "lbi-wizard-label" })

        val existingSources = areaSourceRepository.findAll()
        val modeGroup = RadioButtonGroup<String>().apply {
            setItems("Sorgente già collegata", "Nuova sorgente esterna")
            value = if (existingSources.isNotEmpty()) "Sorgente già collegata" else "Nuova sorgente esterna"
            isEnabled = existingSources.isNotEmpty()
        }

        val existingCombo = ComboBox<AreaSource>("Sorgente esistente").apply {
            setItems(existingSources)
            setItemLabelGenerator { src ->
                val area = registryRepository.findAreaById(src.areaId)
                "${src.config.schema}.${src.config.mainTable}" + (area?.let { " (già usata da: ${it.nome})" } ?: "")
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
                showStepConnect()
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        content.add(HorizontalLayout(cancelButton, nextButton).apply { className = "lbi-wizard-actions" })
    }

    private fun reuseExistingSource(src: AreaSource) {
        // Le credenziali NON persistono tra un wizard e l'altro: anche riusando
        // una sorgente già configurata, l'utente deve ridigitare la password.
        // Pre-compiliamo solo i campi non sensibili (URL, utente, driver,
        // schema, tabella) per evitare di doverli ricercare da capo.
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
        if (reusingExistingSource != null) {
            content.add(Span("Sorgente già configurata: ${selectedSchema}.${reusingExistingSource!!.config.mainTable}. Inserisci di nuovo la password per connetterti (non viene mai salvata in sessione).").apply {
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
                connection?.close()
                jdbcUrlValue = jdbcUrlField.value
                usernameValue = usernameField.value
                passwordValue = passwordField.value
                selectedDriver = driver.first
                connection = metadataService.connect(jdbcUrlValue, usernameValue, passwordValue, driver.first)
                Notification.show("Connessione riuscita")

                val reused = reusingExistingSource
                if (reused != null) {
                    // Sorgente già nota: schema/tabella già decisi, si salta
                    // direttamente alla discovery colonne senza richiederli di nuovo.
                    selectedTable = metadataService.listTables(connection!!, selectedSchema)
                        .find { it.name == reused.config.mainTable }
                    if (selectedTable == null) {
                        Notification.show("Tabella \"${reused.config.mainTable}\" non trovata con queste credenziali", 6000, Notification.Position.MIDDLE)
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

        val conn = connection ?: return
        val schemaCombo = ComboBox<String>("Schema").apply {
            setItems(try { metadataService.listSchemas(conn) } catch (e: Exception) { emptyList() })
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
            val samples = try {
                metadataService.sampleRows(conn, selectedSchema, table.name, 3)
            } catch (e: Exception) {
                emptyList()
            }
            rawSamples = samples

            discoveredColumns = columns.map { col ->
                val exampleValues = samples.mapNotNull { row -> row[col.name]?.toString() }.take(3)
                val discovered = DiscoveredColumn(
                    name = col.name,
                    type = col.typeName,
                    sample = if (exampleValues.isEmpty()) "—" else exampleValues.joinToString(", ")
                )
                ColumnChoice(discovered).apply { displayName = discovered.name }
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

            val headerBox = VerticalLayout(
                Span(choice.column.name).apply { style.set("font-weight", "600") },
                Span(choice.column.type).apply {
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
        }

        content.add(updatedAtCombo, viewNameField)
        content.add(Span("Anteprima").apply { className = "lbi-wizard-label" })
        content.add(sqlPreviewArea)

        val backButton = Button("← Indietro") {
            if (reusingExistingSource != null) showStepOrigin() else showStepTable()
        }
        val nextButton = Button("Avanti →") {
            val included = discoveredColumns.filter { it.included }
            if (included.isEmpty()) {
                Notification.show("Seleziona almeno una colonna", 4000, Notification.Position.MIDDLE)
                return@Button
            }
            if (included.none { it.role == "Filtro" }) {
                Notification.show("Serve almeno un Filtro", 4000, Notification.Position.MIDDLE)
                return@Button
            }
            if (included.none { it.role == "Somma" }) {
                Notification.show("Serve almeno una Somma", 4000, Notification.Position.MIDDLE)
                return@Button
            }
            if (updatedAtCombo.value.isNullOrBlank()) {
                Notification.show("Indica la colonna data ultima modifica", 4000, Notification.Position.MIDDLE)
                return@Button
            }
            showStepConfirm()
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        content.add(HorizontalLayout(backButton, nextButton).apply { className = "lbi-wizard-actions" })
    }

    private fun updateUpdatedAtOptions() {
        if (!::updatedAtCombo.isInitialized) return
        val included = discoveredColumns.filter { it.included }.map { it.column.name }
        updatedAtCombo.setItems(included)
    }

    private fun updateSqlPreview() {
        if (!::viewNameField.isInitialized) return
        val driver = selectedDriver ?: return
        val table = selectedTable?.name ?: return
        val included = discoveredColumns.filter { it.included }
        val direct = included.map { it.column.name to it.column.name }
        val baseName = if (::nomeAreaField.isInitialized && nomeAreaField.value.isNotBlank())
            slugify(nomeAreaField.value) else "nuova_analisi"

        sqlPreviewArea.value = viewSqlGenerator.generate(
            driverClass = driver,
            viewName = viewNameField.value.ifBlank { "vw_lbi_$baseName" },
            schema = selectedSchema,
            mainTable = table,
            directMappings = direct,
            lookups = emptyList(),
            updatedAtColumn = updatedAtCombo.value ?: "updated_at"
        )
    }

    // ================= STEP 4: nome analisi + conferma =================
    private fun showStepConfirm() {
        content.removeAll()
        content.add(Span("Come vuoi chiamare questa analisi?").apply { className = "lbi-wizard-label" })

        nomeAreaField = TextField("Nome analisi").apply {
            placeholder = "es. Vendite 2026"
            setWidthFull()
            addValueChangeListener {
                viewNameField.value = "vw_lbi_${slugify(it.value ?: "")}"
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
        content.add(sqlPreviewArea)

        val backButton = Button("← Indietro") { showStepDiscovery() }
        val createButton = Button("Crea Analisi") { createAreaWithSource() }
            .apply { addThemeVariants(ButtonVariant.LUMO_SUCCESS) }

        content.add(HorizontalLayout(backButton, createButton).apply { className = "lbi-wizard-actions" })
    }

    // ================= Creazione: Analisi + Sorgente in un unico salvataggio =================
    private fun createAreaWithSource() {
        if (nomeAreaField.value.isNullOrBlank()) {
            Notification.show("Indica il nome dell'analisi")
            return
        }

        try {
            val included = discoveredColumns.filter { it.included }
            val filtri = included.filter { it.role == "Filtro" }
            val somme = included.filter { it.role == "Somma" }

            val tabellaFisica = "ch_lbi_" + slugify(nomeAreaField.value)
            symbolTableService.createAreaTable(
                tabellaFisica,
                filtri.map { it.column.name },
                somme.map { it.column.name }
            )

            val area = registryService.createArea(nomeAreaField.value, tabellaFisica)

            filtri.forEach { f ->
                val dimensione = registryService.createDimensione(f.column.name, "string", true, null, null)
                registryService.linkDimensioneToArea(area.id, dimensione.id, f.column.name, false, null)
            }
            somme.forEach { s ->
                registryService.addMetrica(area.id, s.column.name, s.column.name, "SUM")
            }

            val direct = filtri.map { DirectMapping(
                registryRepository.findDimensioniByArea(area.id).first { d ->
                    registryRepository.findDimensione(d.dimensioneId)?.nome == it.column.name
                }.dimensioneId, it.column.name
            ) } + somme.map { s ->
                DirectMapping(
                    registryRepository.findMetricheByArea(area.id).first { it2 -> it2.nome == s.column.name }.id,
                    s.column.name
                )
            }

            val encryptedPassword = if (reusingExistingSource != null) {
                reusingExistingSource!!.config.encryptedPassword
            } else {
                cryptoService.encrypt(passwordValue)
            }

            val config = SourceConfig(
                jdbcUrl = jdbcUrlValue,
                username = usernameValue,
                encryptedPassword = encryptedPassword,
                driverClassName = selectedDriver!!,
                schema = selectedSchema,
                mainTable = selectedTable!!.name,
                viewName = viewNameField.value,
                directMappings = direct,
                lookups = emptyList(),
                syncMode = SyncMode.FULL_RELOAD
            )

            val source = AreaSource(
                id = UUID.randomUUID(),
                areaId = area.id,
                tipoSorgente = "jdbc",
                config = config,
                status = SourceStatus.PENDING_VIEW,
                errorDetail = null,
                createdAt = Instant.now()
            )
            areaSourceRepository.save(source)

            Notification.show(
                "Analisi \"${nomeAreaField.value}\" creata. Consegna l'SQL al DBA o crea la view automaticamente, poi verifica la sorgente da \"Sorgenti Dati\".",
                6000, Notification.Position.MIDDLE
            )
            onAreaCreated()
            close()
        } catch (e: Exception) {
            Notification.show("Errore: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
    }
}

