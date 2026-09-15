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
 * - Una sorgente fisica può alimentare più Analisi diverse.
 * - I nomi fisici NON si costruiscono qui: si passa sempre da Naming.
 * - Le dimensioni si RIUSANO se esistono già con lo stesso nome fisico.
 * - La sorgente selezionata in discovery può essere già pronta o da
 *   generare (checkbox esplicita nel passo di conferma).
 * - Una colonna marcata Metrica può generare PIÙ metriche con aggregazioni
 *   diverse (es. media, minimo e massimo di lead_time_giorni nella stessa
 *   analisi): il ruolo di una colonna non è più "una colonna = una
 *   metrica con SUM fissa", ma "una colonna = uno o più modi di
 *   aggregarla", scelti esplicitamente dall'utente con un nome proposto
 *   e sempre modificabile.
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

    /**
     * Una singola metrica configurata su una colonna: il tipo di
     * aggregazione scelto e il nome, proposto automaticamente ma sempre
     * modificabile dall'utente prima di creare l'analisi.
     */
    private data class MetricSelection(
        var tipo: TipoAggregazione,
        var nome: String
    )

    /** Scelta dell'utente per una colonna scoperta. */
    private data class ColumnChoice(
        val column: DiscoveredColumn,
        var included: Boolean = false,
        var role: String = "Filtro",   // "Filtro" | "Metrica"
        val metricSelections: MutableList<MetricSelection> = mutableListOf()
    )

    private var connection: Connection? = null
    private var selectedSchema: String? = null
    private var selectedTable: TableInfo? = null
    private var selectedDriver: String? = null
    private var jdbcUrlValue: String = ""
    private var usernameValue: String = ""
    private var passwordValue: String = ""
    private var reusingExistingSource: AreaSource? = null

    private var useExistingSourceAsIs = false

    private var discoveredColumns: List<ColumnChoice> = emptyList()
    private var rawSamples: List<Map<String, Any?>> = emptyList()

    private var updatedAtCombo: ComboBox<String>? = null
    private var viewNameField: TextField? = null
    private var nomeAreaField: TextField? = null
    private var viewNameTouched = false

    /** Riferimenti agli span di riepilogo metriche nell'header colonna, per aggiornarli senza ridisegnare la grid. */
    private val metricSummarySpans = mutableMapOf<String, Span>()

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
        content.add(Span("Se la view è già pronta con i nomi colonna definitivi, potrai indicarlo nel passo di conferma per usarla direttamente senza generarne una nuova.").apply {
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
        metricSummarySpans.clear()
        content.add(Span("Seleziona le colonne da usare in questa analisi, indica il ruolo, e guarda i dati reali sotto").apply {
            className = "lbi-wizard-label"
        })

        val grid = Grid<Map<String, Any?>>().apply {
            setItems(rawSamples)
            setWidthFull()
            height = "260px"
        }

        discoveredColumns.forEach { choice ->
            val metricSummary = Span().apply {
                style.set("font-size", "11px")
                style.set("color", "var(--lbi-text-muted)")
                isVisible = false
            }
            metricSummarySpans[choice.column.name] = metricSummary

            val configureMetricButton = Button("Configura aggregazioni") {
                openMetricConfigDialog(choice, metricSummary)
            }.apply {
                addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY)
                isVisible = false
            }

            val roleGroup = RadioButtonGroup<String>().apply {
                setItems("Filtro", "Metrica")
                value = choice.role
                isVisible = choice.included
                addValueChangeListener {
                    choice.role = it.value
                    val isMetrica = it.value == "Metrica"
                    configureMetricButton.isVisible = isMetrica
                    metricSummary.isVisible = isMetrica && choice.metricSelections.isNotEmpty()
                    if (isMetrica && choice.metricSelections.isEmpty()) {
                        // Al primo passaggio a Metrica si propone subito una
                        // aggregazione di default, così la colonna non resta
                        // "Metrica" senza nessuna metrica configurata.
                        choice.metricSelections += MetricSelection(
                            suggestDefaultAggregation(choice.column.name), suggestName(choice.column.name, suggestDefaultAggregation(choice.column.name))
                        )
                        updateMetricSummary(choice, metricSummary)
                    }
                    updateSqlPreview()
                }
            }
            val checkbox = Checkbox().apply {
                value = choice.included
                addValueChangeListener {
                    choice.included = it.value
                    roleGroup.isVisible = it.value
                    val isMetrica = it.value && choice.role == "Metrica"
                    configureMetricButton.isVisible = isMetrica
                    metricSummary.isVisible = isMetrica && choice.metricSelections.isNotEmpty()
                    updateUpdatedAtOptions()
                    updateSqlPreview()
                }
            }

            val fisico = try { Naming.column(choice.column.name) } catch (e: Exception) { "?" }

            val headerBox = VerticalLayout(
                Span(choice.column.name).apply { style.set("font-weight", "600") },
                Span("${choice.column.type} → $fisico").apply {
                    style.set("font-size", "11px")
                    style.set("color", "var(--lbi-text-muted)")
                },
                checkbox,
                roleGroup,
                configureMetricButton,
                metricSummary
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

    /**
     * Apre il dialog di configurazione delle aggregazioni per una colonna
     * marcata Metrica. Una riga per tipo di aggregazione disponibile:
     * checkbox per includerla, campo nome proposto e modificabile.
     *
     * Più aggregazioni sulla stessa colonna sono il caso normale, non
     * un'eccezione: media, minimo e massimo dello stesso lead time nella
     * stessa analisi sono tre metriche distinte sulla stessa colonna fisica.
     */
    private fun openMetricConfigDialog(choice: ColumnChoice, summarySpan: Span) {
        val dialog = Dialog().apply {
            headerTitle = "Aggregazioni su \"${choice.column.name}\""
            width = "520px"
        }

        data class Row(val tipo: TipoAggregazione, val checkbox: Checkbox, val nameField: TextField)

        val existing = choice.metricSelections.associateBy { it.tipo }
        val rows = TipoAggregazione.entries.map { tipo ->
            val preselected = existing.containsKey(tipo)
            val nameField = TextField().apply {
                setWidthFull()
                value = existing[tipo]?.nome ?: suggestName(choice.column.name, tipo)
                isEnabled = preselected
            }
            val checkbox = Checkbox(aggregationLabel(tipo)).apply {
                value = preselected
                addValueChangeListener { nameField.isEnabled = it.value }
            }
            Row(tipo, checkbox, nameField)
        }

        val rowsLayout = VerticalLayout().apply {
            isPadding = false
            rows.forEach { row ->
                add(HorizontalLayout(row.checkbox, row.nameField).apply {
                    isPadding = false
                    setWidthFull()
                    setFlexGrow(0.0, row.checkbox)
                    setFlexGrow(1.0, row.nameField)
                })
            }
        }

        dialog.add(VerticalLayout(
            Span("Seleziona una o più aggregazioni per questa colonna. Ogni aggregazione diventa una metrica separata nell'analisi.").apply {
                className = "lbi-wizard-label"
            },
            rowsLayout
        ))

        val cancelButton = Button("Annulla") { dialog.close() }
        val confirmButton = Button("Conferma") {
            val selected = rows.filter { it.checkbox.value }
            if (selected.isEmpty()) {
                Notification.show("Seleziona almeno un'aggregazione, o riporta la colonna a Filtro")
                return@Button
            }
            val nomiVuoti = selected.any { it.nameField.value.isNullOrBlank() }
            if (nomiVuoti) {
                Notification.show("Ogni aggregazione selezionata deve avere un nome")
                return@Button
            }
            val duplicatiInterni = selected.map { it.nameField.value.trim() }
                .groupingBy { it.lowercase() }.eachCount().filterValues { it > 1 }
            if (duplicatiInterni.isNotEmpty()) {
                Notification.show("Nomi duplicati fra le aggregazioni di questa colonna: ${duplicatiInterni.keys.joinToString(", ")}")
                return@Button
            }
            // Univocità anche rispetto alle metriche già configurate su
            // ALTRE colonne: due metriche con lo stesso nome nella stessa
            // area si sovrascriverebbero silenziosamente nella mappa dei
            // risultati aggregati.
            val nomiAltrove = discoveredColumns
                .filter { it !== choice }
                .flatMap { it.metricSelections }
                .map { it.nome.lowercase() }
                .toSet()
            val collisioneEsterna = selected.map { it.nameField.value.trim() }
                .firstOrNull { it.lowercase() in nomiAltrove }
            if (collisioneEsterna != null) {
                Notification.show("Il nome \"$collisioneEsterna\" è già usato da una metrica su un'altra colonna")
                return@Button
            }

            choice.metricSelections.clear()
            selected.forEach { row ->
                choice.metricSelections += MetricSelection(row.tipo, row.nameField.value.trim())
            }
            updateMetricSummary(choice, summarySpan)
            updateSqlPreview()
            dialog.close()
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        dialog.footer.add(cancelButton, confirmButton)
        dialog.open()
    }

    private fun updateMetricSummary(choice: ColumnChoice, span: Span) {
        span.isVisible = choice.metricSelections.isNotEmpty()
        span.text = choice.metricSelections.joinToString(", ") { "${it.nome} (${aggregationLabel(it.tipo)})" }
    }

    private fun aggregationLabel(tipo: TipoAggregazione): String = when (tipo) {
        TipoAggregazione.SUM -> "Somma"
        TipoAggregazione.AVG -> "Media"
        TipoAggregazione.COUNT -> "Conteggio"
        TipoAggregazione.COUNT_DISTINCT -> "Conteggio distinto"
        TipoAggregazione.MIN -> "Minimo"
        TipoAggregazione.MAX -> "Massimo"
    }

    /**
     * Suggerisce l'aggregazione più plausibile in base al nome colonna.
     * È solo un default proposto: l'utente lo vede subito nel dialog di
     * configurazione e può cambiarlo prima di confermare.
     */
    private fun suggestDefaultAggregation(colName: String): TipoAggregazione {
        val n = colName.lowercase()
        val mediaHints = listOf("giorni", "tempo", "durata", "media", "pct", "percentuale", "rate")
        return if (mediaHints.any { n.contains(it) }) TipoAggregazione.AVG else TipoAggregazione.SUM
    }

    private fun suggestName(colName: String, tipo: TipoAggregazione): String {
        val leggibile = colName.replace('_', ' ').replaceFirstChar { it.uppercase() }
        val prefisso = when (tipo) {
            TipoAggregazione.SUM -> "Totale"
            TipoAggregazione.AVG -> "Media"
            TipoAggregazione.COUNT -> "Conteggio"
            TipoAggregazione.COUNT_DISTINCT -> "Conteggio distinto"
            TipoAggregazione.MIN -> "Minimo"
            TipoAggregazione.MAX -> "Massimo"
        }
        return "$prefisso $leggibile"
    }

    private fun validateDiscovery(): String? {
        val included = discoveredColumns.filter { it.included }
        if (included.isEmpty()) return "Seleziona almeno una colonna"
        if (included.none { it.role == "Filtro" }) return "Serve almeno un Filtro"

        val metriche = included.filter { it.role == "Metrica" }
        if (metriche.isEmpty()) return "Serve almeno una Metrica"
        if (metriche.any { it.metricSelections.isEmpty() }) {
            return "Configura almeno un'aggregazione per ogni colonna marcata Metrica"
        }

        if (updatedAtCombo?.value.isNullOrBlank()) return "Indica la colonna data ultima modifica"

        val fisici = included.map {
            try { Naming.column(it.column.name) } catch (e: Exception) { return "Colonna non utilizzabile: ${it.column.name}" }
        }
        val collisioni = fisici.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (collisioni.isNotEmpty()) {
            return "Colonne che collidono dopo la normalizzazione: ${collisioni.joinToString(", ")}. Deselezionane una."
        }

        val tuttiNomiMetriche = metriche.flatMap { it.metricSelections }.map { it.nome.trim().lowercase() }
        val duplicati = tuttiNomiMetriche.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicati.isNotEmpty()) {
            return "Nomi di metrica duplicati nell'analisi: ${duplicati.joinToString(", ")}"
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

    /**
     * Aggiorna l'anteprima. Se la sorgente è già pronta così com'è, non
     * c'è nessuna view da generare: si mostra solo cosa verrà usato.
     */
    private fun updateSqlPreview() {
        if (useExistingSourceAsIs) {
            sqlPreviewArea.value = "Nessuna view da creare: verrà usata direttamente ${qualifiedSourceName()}"
            return
        }
        val driver = selectedDriver ?: return
        val table = selectedTable?.name ?: return
        val included = discoveredColumns.filter { it.included }

        // Ogni colonna coinvolta (filtro o base di una o più metriche) va
        // esposta una sola volta nella view, anche se genera più metriche.
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

    /**
     * Nome della tabella/view scelta in discovery, SENZA schema (lo schema
     * è già salvato a parte in SourceConfig.schema).
     */
    private fun qualifiedSourceName(): String {
        return selectedTable?.name ?: ""
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
                if (!viewNameTouched && !nome.isNullOrBlank() && !useExistingSourceAsIs) {
                    viewNameField?.value = try { Naming.viewName(nome) } catch (e: Exception) { "" }
                }
                updateSqlPreview()
            }
        }
        content.add(nomeAreaField)

        val included = discoveredColumns.filter { it.included }
        val filtri = included.filter { it.role == "Filtro" }
        val metriche = included.filter { it.role == "Metrica" }

        val useExistingCheckbox = Checkbox("La sorgente selezionata è già pronta così com'è, non serve creare una nuova view").apply {
            value = useExistingSourceAsIs
            addValueChangeListener {
                useExistingSourceAsIs = it.value
                if (it.value) {
                    viewNameField?.value = qualifiedSourceName()
                } else if (!viewNameTouched) {
                    viewNameField?.value = try { Naming.viewName(nomeAreaField?.value ?: "") } catch (e: Exception) { "" }
                }
                updateSqlPreview()
            }
        }
        content.add(useExistingCheckbox)

        content.add(Span("Riepilogo").apply { className = "lbi-wizard-label" })
        content.add(Span("Filtri: ${filtri.joinToString(", ") { it.column.name }}"))
        content.add(Span(
            "Metriche: " + metriche.joinToString("; ") { col ->
                "${col.column.name} → " + col.metricSelections.joinToString(", ") { "${it.nome} (${aggregationLabel(it.tipo)})" }
            }
        ))

        val riusate = filtri.mapNotNull { f ->
            registryService.findDimensioneByNomeFisico(f.column.name)?.let { "${f.column.name} → ${it.nome}" }
        }
        if (riusate.isNotEmpty()) {
            content.add(Span("Filtri collegati a dimensioni già esistenti: ${riusate.joinToString(", ")}").apply {
                className = "lbi-wizard-label"
            })
        }

        content.add(sqlPreviewArea)
        updateSqlPreview()

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
            val metriche = included.filter { it.role == "Metrica" }

            // La tabella ClickHouse ha una colonna fisica per colonna
            // sorgente coinvolta in una metrica, non una per metrica: più
            // aggregazioni sulla stessa colonna condividono la stessa
            // colonna fisica, l'aggregazione avviene a lettura (AggregateService).
            val tabellaFisica = symbolTableService.createAreaTable(
                nomeArea,
                filtri.map { it.column.name },
                metriche.map { it.column.name }
            )
            tabellaCreata = tabellaFisica

            val area = registryService.createArea(nomeArea, tabellaFisica)

            val direct = mutableListOf<DirectMapping>()

            filtri.forEach { f ->
                val dimensione = registryService.findOrCreateDimensione(f.column.name)
                val colonna = Naming.column(f.column.name)
                registryService.linkDimensioneToArea(area.id, dimensione.id, colonna, false, null)
                direct += DirectMapping(dimensione.id, colonna)
            }

            metriche.forEach { m ->
                val colonna = Naming.column(m.column.name)
                m.metricSelections.forEach { sel ->
                    val metrica = registryService.addMetrica(
                        areaId = area.id,
                        nome = sel.nome,
                        colonnaFisica = colonna,
                        tipoAggregazione = sel.tipo
                    )
                    direct += DirectMapping(metrica.id, colonna)
                }
            }

            val encryptedPassword = cryptoService.encrypt(passwordValue)

            val viewName = if (useExistingSourceAsIs) qualifiedSourceName() else currentViewName()

            val config = SourceConfig(
                jdbcUrl = jdbcUrlValue,
                username = usernameValue,
                encryptedPassword = encryptedPassword,
                driverClassName = selectedDriver!!,
                schema = selectedSchema,
                mainTable = selectedTable!!.name,
                viewName = viewName,
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

            val messaggio = if (useExistingSourceAsIs) {
                "Analisi \"$nomeArea\" creata. La sorgente è quella già esistente: premi \"Verifica sorgente\" per controllare che esponga le colonne attese."
            } else {
                "Analisi \"$nomeArea\" creata. Consegna l'SQL al DBA o crea la view automaticamente, poi verifica la sorgente."
            }
            Notification.show(messaggio, 6000, Notification.Position.MIDDLE)
            onAreaCreated()
            close()
        } catch (e: Exception) {
            tabellaCreata?.let {
                try { symbolTableService.dropTable(it) } catch (_: Exception) { }
            }
            Notification.show("Errore: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
    }
}