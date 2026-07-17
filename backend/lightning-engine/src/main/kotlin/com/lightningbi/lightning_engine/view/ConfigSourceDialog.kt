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

class ConfigureSourceDialog(
    private val area: Area,
    private val areaSourceRepository: AreaSourceRepository,
    private val registryRepository: RegistryRepository,
    private val cryptoService: CryptoService,
    private val metadataService: MetadataService,
    private val viewSqlGenerator: ViewSqlGenerator,
    private val existingSource: AreaSource? = null,
    private val onSaved: () -> Unit
) : Dialog() {

    private val driverOptions = listOf(
        "com.microsoft.sqlserver.jdbc.SQLServerDriver" to "SQL Server",
        "org.postgresql.Driver" to "PostgreSQL",
        "com.mysql.cj.jdbc.Driver" to "MySQL",
        "oracle.jdbc.OracleDriver" to "Oracle",
        "com.ibm.db2.jcc.DB2Driver" to "DB2"
    )

    private val targetFields: List<Pair<String, UUID>>
    private var connection: Connection? = null

    // Step 1
    private val jdbcUrlField = TextField("Indirizzo database (JDBC URL)").apply {
        placeholder = "jdbc:sqlserver://server:1433;databaseName=ERP;trustServerCertificate=true"
        setWidthFull()
    }
    private val driverCombo = ComboBox<Pair<String, String>>("Tipo database").apply { setWidthFull() }
    private val usernameField = TextField("Utente").apply { setWidthFull() }
    private val passwordField = PasswordField("Password").apply { setWidthFull() }

    // Step 2
    private val schemaCombo = ComboBox<String>("Schema").apply { setWidthFull() }
    private val tableSearchField = TextField("Cerca tabella").apply {
        placeholder = "Digita per filtrare..."
        setWidthFull()
    }
    private val tableCombo = ComboBox<TableInfo>("Tabella principale (fatti)").apply {
        setItemLabelGenerator { it.name }
        setWidthFull()
    }
    private var allTables: List<TableInfo> = emptyList()

    // Step 3
    private data class FieldMapping(
        val targetFieldId: UUID,
        val targetName: String,
        var isDirect: Boolean = true,
        var directColumn: String? = null,
        var lookupTable: String? = null,
        var joinColumnMain: String? = null,
        var joinColumnLookup: String? = null,
        var valueColumn: String? = null
    )

    private val fieldMappings = mutableListOf<FieldMapping>()
    private val mappingListContainer = Div()
    private val sqlPreviewArea = TextArea("Anteprima SQL (CREATE VIEW)").apply {
        isReadOnly = true
        setWidthFull()
        height = "220px"
    }
    private lateinit var viewNameField: TextField
    private lateinit var updatedAtColumnField: TextField
    private var mainTableColumns: List<ColumnInfo> = emptyList()

    private val content = VerticalLayout().apply { className = "lbi-wizard-content" }

    init {
        val dims = registryRepository.findDimensioniByArea(area.id)
            .mapNotNull { ad -> registryRepository.findDimensione(ad.dimensioneId)?.let { it.nome to it.id } }
        val metriche = registryRepository.findMetricheByArea(area.id).map { it.nome to it.id }
        targetFields = dims + metriche
        fieldMappings.addAll(targetFields.map { (nome, id) -> FieldMapping(id, nome) })

        driverCombo.setItems(driverOptions)
        driverCombo.setItemLabelGenerator { it.second }
        driverCombo.setRenderer(com.vaadin.flow.data.renderer.ComponentRenderer { pair ->
            HorizontalLayout(driverIcon(pair.first), Span(pair.second)).apply {
                defaultVerticalComponentAlignment = com.vaadin.flow.component.orderedlayout.FlexComponent.Alignment.CENTER
                isSpacing = true
            }
        })

        className = "lbi-wizard-dialog"
        width = "700px"
        headerTitle = if (existingSource != null) "Modifica sorgente dati" else "Collega sorgente dati"
        isCloseOnEsc = false
        isCloseOnOutsideClick = false

        existingSource?.let { src ->
            jdbcUrlField.value = src.config.jdbcUrl
            usernameField.value = src.config.username
            driverCombo.value = driverOptions.find { it.first == src.config.driverClassName }
        }

        add(content)

        if (existingSource != null && existingSource.status != SourceStatus.PENDING_VIEW) {
            showVerifyStep()
        } else if (existingSource?.status == SourceStatus.PENDING_VIEW) {
            showStep1(resumeToVerify = true)
        } else {
            showStep1(resumeToVerify = false)
        }
    }

    private fun driverIcon(driverClass: String): com.vaadin.flow.component.Component {
        val fileName = when (driverClass) {
            "com.microsoft.sqlserver.jdbc.SQLServerDriver" -> "mssql_icon_big@2x.png"
            "org.postgresql.Driver" -> "postgresql_icon_big@2x.png"
            "com.mysql.cj.jdbc.Driver" -> "mysql_icon_big@2x.png"
            "oracle.jdbc.OracleDriver" -> "oracle_icon_big@2x.png"
            "com.ibm.db2.jcc.DB2Driver" -> "db2_icon_big@2x.png"
            else -> "postgresql_icon_big@2x.png"
        }
        return com.vaadin.flow.component.html.Image("images/db-icons/$fileName", fileName).apply {
            width = "20px"
            height = "20px"
        }
    }

    // ================= STEP 1: connessione =================
    private fun showStep1(resumeToVerify: Boolean) {
        content.removeAll()
        content.add(Span("Da dove LightningBI deve prendere i dati per questa analisi").apply {
            className = "lbi-wizard-label"
        })
        content.add(jdbcUrlField, driverCombo, usernameField, passwordField)

        val connectButton = Button("Connetti →") { attemptConnect(resumeToVerify) }
            .apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        val cancelButton = Button("Annulla") { close() }

        content.add(HorizontalLayout(cancelButton, connectButton).apply { className = "lbi-wizard-actions" })
    }

    private fun attemptConnect(resumeToVerify: Boolean) {
        val url = jdbcUrlField.value
        val driver = driverCombo.value
        val user = usernameField.value
        val pass = passwordField.value

        if (url.isNullOrBlank() || driver == null || user.isNullOrBlank()) {
            Notification.show("Compila tutti i campi")
            return
        }

        try {
            connection?.close()
            connection = metadataService.connect(url, user, pass, driver.first)
            Notification.show("Connessione riuscita")
            if (resumeToVerify) {
                runVerification()
            } else {
                showStep2()
            }
        } catch (e: Exception) {
            Notification.show("Errore di connessione: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
    }

    // ================= STEP 2: schema + tabella principale =================
    private fun showStep2() {
        content.removeAll()
        content.add(Span("Scegli la tabella principale con i dati (es. Ordini, Vendite)").apply {
            className = "lbi-wizard-label"
        })

        val conn = connection ?: return
        val schemas = try { metadataService.listSchemas(conn) } catch (e: Exception) { emptyList() }
        schemaCombo.setItems(schemas)
        schemaCombo.addValueChangeListener { loadTablesForSchema(it.value) }

        tableSearchField.addValueChangeListener { filterTables(it.value) }

        content.add(schemaCombo, tableSearchField, tableCombo)

        if (schemas.isNotEmpty()) schemaCombo.value = schemas.first()

        val backButton = Button("← Indietro") { showStep1(false) }
        val nextButton = Button("Avanti →") {
            if (tableCombo.value == null) {
                Notification.show("Seleziona una tabella")
                return@Button
            }
            loadMainTableColumns()
            showStep3()
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        content.add(HorizontalLayout(backButton, nextButton).apply { className = "lbi-wizard-actions" })
    }

    private fun loadTablesForSchema(schema: String?) {
        val conn = connection ?: return
        try {
            allTables = metadataService.listTables(conn, schema)
            tableCombo.setItems(allTables)
        } catch (e: Exception) {
            Notification.show("Errore caricamento tabelle: ${e.message}")
        }
    }

    private fun filterTables(query: String?) {
        val filtered = if (query.isNullOrBlank()) allTables
        else allTables.filter { it.name.contains(query, ignoreCase = true) }
        tableCombo.setItems(filtered)
    }

    private fun loadMainTableColumns() {
        val conn = connection ?: return
        val table = tableCombo.value ?: return
        mainTableColumns = try {
            metadataService.listColumns(conn, schemaCombo.value, table.name)
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ================= STEP 3: mapping filtri/somme =================
    private fun showStep3() {
        content.removeAll()
        content.add(Span("Associa ogni filtro/somma dell'analisi a una colonna (diretta o tramite lookup)").apply {
            className = "lbi-wizard-label"
        })

        viewNameField = TextField("Nome view").apply {
            value = "vw_lbi_${area.nome.lowercase().replace(Regex("\\s+"), "_")}"
            setWidthFull()
        }
        updatedAtColumnField = TextField("Colonna data ultima modifica nella tabella principale").apply {
            placeholder = "es. data_modifica"
            setWidthFull()
        }
        content.add(viewNameField, updatedAtColumnField)

        mappingListContainer.removeAll()
        fieldMappings.forEach { fm -> mappingListContainer.add(buildMappingRow(fm)) }
        content.add(mappingListContainer)

        content.add(Span("Anteprima").apply { className = "lbi-wizard-label" })
        content.add(sqlPreviewArea)
        updateSqlPreview()

        val backButton = Button("← Indietro") { showStep2() }
        val nextButton = Button("Avanti →") {
            val unmapped = fieldMappings.filter {
                if (it.isDirect) it.directColumn.isNullOrBlank()
                else it.lookupTable.isNullOrBlank() || it.joinColumnMain.isNullOrBlank() ||
                        it.joinColumnLookup.isNullOrBlank() || it.valueColumn.isNullOrBlank()
            }
            if (unmapped.isNotEmpty()) {
                Notification.show("Filtri/somme non mappati: ${unmapped.joinToString(", ") { it.targetName }}", 5000, Notification.Position.MIDDLE)
                return@Button
            }
            if (updatedAtColumnField.value.isNullOrBlank()) {
                Notification.show("Indica la colonna data ultima modifica")
                return@Button
            }
            showStep4()
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        content.add(HorizontalLayout(backButton, nextButton).apply { className = "lbi-wizard-actions" })
    }

    private fun buildMappingRow(fm: FieldMapping): Div {
        val modeGroup = RadioButtonGroup<String>().apply {
            setItems("Colonna diretta", "Lookup da altra tabella")
            value = if (fm.isDirect) "Colonna diretta" else "Lookup da altra tabella"
        }

        val directColumnCombo = ComboBox<ColumnInfo>("Colonna").apply {
            setItems(mainTableColumns)
            setItemLabelGenerator { it.name }
            isVisible = fm.isDirect
            setWidthFull()
            value = mainTableColumns.find { it.name == fm.directColumn }
            addValueChangeListener { fm.directColumn = it.value?.name; updateSqlPreview() }
        }

        val lookupTableCombo = ComboBox<TableInfo>("Tabella lookup").apply {
            setItems(allTables)
            setItemLabelGenerator { it.name }
            isVisible = !fm.isDirect
            setWidthFull()
        }

        val joinMainCombo = ComboBox<ColumnInfo>("Colonna join (tabella principale)").apply {
            setItems(mainTableColumns)
            setItemLabelGenerator { it.name }
            isVisible = !fm.isDirect
            setWidthFull()
            addValueChangeListener { fm.joinColumnMain = it.value?.name; updateSqlPreview() }
        }

        val joinLookupCombo = ComboBox<ColumnInfo>("Colonna join (tabella lookup)").apply {
            isVisible = !fm.isDirect
            setWidthFull()
            addValueChangeListener { fm.joinColumnLookup = it.value?.name; updateSqlPreview() }
        }

        val valueColumnCombo = ComboBox<ColumnInfo>("Colonna valore").apply {
            isVisible = !fm.isDirect
            setWidthFull()
            addValueChangeListener { fm.valueColumn = it.value?.name; updateSqlPreview() }
        }

        lookupTableCombo.addValueChangeListener { event ->
            val table = event.value ?: return@addValueChangeListener
            fm.lookupTable = table.name
            val conn = connection
            if (conn != null) {
                val cols = try { metadataService.listColumns(conn, schemaCombo.value, table.name) } catch (e: Exception) { emptyList() }
                joinLookupCombo.setItems(cols)
                valueColumnCombo.setItems(cols)

                val mainTable = tableCombo.value
                if (mainTable != null) {
                    val fks = try { metadataService.getImportedForeignKeys(conn, schemaCombo.value, mainTable.name) } catch (e: Exception) { emptyList() }
                    val match = fks.find { it.pkTable.equals(table.name, ignoreCase = true) }
                    if (match != null) {
                        joinMainCombo.value = mainTableColumns.find { it.name == match.fkColumn }
                        joinLookupCombo.value = cols.find { it.name == match.pkColumn }
                        fm.joinColumnMain = match.fkColumn
                        fm.joinColumnLookup = match.pkColumn
                    }
                }
            }
            updateSqlPreview()
        }

        modeGroup.addValueChangeListener {
            val isDirect = it.value == "Colonna diretta"
            fm.isDirect = isDirect
            directColumnCombo.isVisible = isDirect
            lookupTableCombo.isVisible = !isDirect
            joinMainCombo.isVisible = !isDirect
            joinLookupCombo.isVisible = !isDirect
            valueColumnCombo.isVisible = !isDirect
            updateSqlPreview()
        }

        val title = Span(fm.targetName).apply { className = "lbi-filter-title" }
        return Div(
            title, modeGroup, directColumnCombo, lookupTableCombo,
            joinMainCombo, joinLookupCombo, valueColumnCombo
        ).apply { className = "lbi-wizard-item" }
    }

    private fun updateSqlPreview() {
        if (!::viewNameField.isInitialized) return
        val direct = fieldMappings.filter { it.isDirect && !it.directColumn.isNullOrBlank() }
            .map { it.directColumn!! to it.targetName.lowercase().replace(Regex("\\s+"), "_") }
        val lookups = fieldMappings.filter { !it.isDirect && !it.lookupTable.isNullOrBlank() }
            .mapIndexed { idx, fm ->
                ViewSqlGenerator.LookupSql(
                    lookupTable = fm.lookupTable!!,
                    lookupAlias = "lk$idx",
                    joinColumnMain = fm.joinColumnMain ?: "",
                    joinColumnLookup = fm.joinColumnLookup ?: "",
                    valueColumn = fm.valueColumn ?: "",
                    outputAlias = fm.targetName.lowercase().replace(Regex("\\s+"), "_")
                )
            }
        val driver = driverCombo.value?.first ?: return
        val mainTable = tableCombo.value?.name ?: return

        sqlPreviewArea.value = viewSqlGenerator.generate(
            driverClass = driver,
            viewName = viewNameField.value.ifBlank { "vw_lbi_area" },
            schema = schemaCombo.value,
            mainTable = mainTable,
            directMappings = direct,
            lookups = lookups,
            updatedAtColumn = updatedAtColumnField.value.ifBlank { "updated_at" }
        )
    }

    // ================= STEP 4: genera / salva =================
    private fun showStep4() {
        content.removeAll()
        content.add(Span("Consegna questo SQL al tuo DBA per creare la view (opzione consigliata)").apply {
            className = "lbi-wizard-label"
        })
        content.add(sqlPreviewArea)

        val copyButton = Button("Copia SQL per il DBA") {
            saveAsPending()
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        val autoCreateButton = Button("Crea view automaticamente (richiede permessi DDL)") {
            createViewAutomatically()
        }

        val backButton = Button("← Indietro") { showStep3() }

        content.add(HorizontalLayout(backButton, copyButton, autoCreateButton).apply { className = "lbi-wizard-actions" })
    }

    private fun buildConfig(): SourceConfig {
        val direct = fieldMappings.filter { it.isDirect }
            .map { DirectMapping(it.targetFieldId, it.directColumn ?: "") }
        val lookups = fieldMappings.filter { !it.isDirect }
            .map { LookupConfig(it.targetFieldId, it.lookupTable ?: "", it.joinColumnMain ?: "", it.joinColumnLookup ?: "", it.valueColumn ?: "") }

        val encryptedPassword = if (passwordField.value.isNullOrBlank()) {
            existingSource?.config?.encryptedPassword ?: ""
        } else {
            cryptoService.encrypt(passwordField.value)
        }

        return SourceConfig(
            jdbcUrl = jdbcUrlField.value,
            username = usernameField.value,
            encryptedPassword = encryptedPassword,
            driverClassName = driverCombo.value.first,
            schema = schemaCombo.value,
            mainTable = tableCombo.value?.name ?: "",
            viewName = viewNameField.value,
            directMappings = direct,
            lookups = lookups,
            syncMode = SyncMode.FULL_RELOAD
        )
    }

    private fun saveAsPending() {
        try {
            val source = AreaSource(
                id = existingSource?.id ?: UUID.randomUUID(),
                areaId = area.id,
                tipoSorgente = "jdbc",
                config = buildConfig(),
                status = SourceStatus.PENDING_VIEW,
                errorDetail = null,
                createdAt = existingSource?.createdAt ?: Instant.now()
            )
            areaSourceRepository.save(source)
            Notification.show("Configurazione salvata. Consegna l'SQL al DBA, poi riapri questa finestra per verificare.")
            onSaved()
            close()
        } catch (e: Exception) {
            Notification.show("Errore: ${e.message}", 5000, Notification.Position.MIDDLE)
        }
    }

    private fun createViewAutomatically() {
        val conn = connection ?: return
        try {
            conn.createStatement().use { it.execute(sqlPreviewArea.value) }
            Notification.show("View creata con successo")
            val source = AreaSource(
                id = existingSource?.id ?: UUID.randomUUID(),
                areaId = area.id,
                tipoSorgente = "jdbc",
                config = buildConfig(),
                status = SourceStatus.PENDING_VIEW,
                errorDetail = null,
                createdAt = existingSource?.createdAt ?: Instant.now()
            )
            areaSourceRepository.save(source)
            runVerification(source)
        } catch (e: Exception) {
            Notification.show("Errore creazione view: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
    }

    // ================= Verifica (drift-check) =================
    private fun showVerifyStep() {
        content.removeAll()
        val src = existingSource!!
        content.add(Span("Sorgente configurata. Stato: ${src.status}").apply { className = "lbi-wizard-label" })
        if (src.status == SourceStatus.ERROR && src.errorDetail != null) {
            content.add(Span(src.errorDetail).apply { className = "lbi-wizard-label" })
        }

        val verifyButton = Button("Verifica view") {
            jdbcUrlField.value = src.config.jdbcUrl
            usernameField.value = src.config.username
            driverCombo.value = driverOptions.find { it.first == src.config.driverClassName }
            attemptConnect(resumeToVerify = true)
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        val reconfigureButton = Button("Riconfigura da zero") { showStep1(false) }
        val closeButton = Button("Chiudi") { close() }

        content.add(HorizontalLayout(closeButton, reconfigureButton, verifyButton).apply { className = "lbi-wizard-actions" })
    }

    private fun runVerification(source: AreaSource? = null) {
        val conn = connection ?: return
        val src = source ?: existingSource ?: return

        val allExpected = targetFields.map { (nome, _) ->
            nome.lowercase().replace(Regex("\\s+"), "_")
        } + "lbi_updated_at"

        val (ok, error) = try {
            metadataService.viewExistsWithColumns(conn, src.config.schema, src.config.viewName, allExpected)
        } catch (e: Exception) {
            false to e.message
        }

        val updated = src.copy(
            status = if (ok) SourceStatus.VERIFIED else SourceStatus.ERROR,
            errorDetail = if (ok) null else error
        )
        areaSourceRepository.save(updated)

        if (ok) {
            Notification.show("View verificata con successo. Sorgente pronta per la sincronizzazione.")
            onSaved()
            close()
        } else {
            Notification.show("Verifica fallita: $error", 6000, Notification.Position.MIDDLE)
            content.removeAll()
            content.add(Span("Verifica fallita").apply { className = "lbi-wizard-label" })
            content.add(Span(error ?: "Errore sconosciuto").apply { className = "lbi-wizard-label" })
            val retryButton = Button("Riprova") { runVerification(updated) }
            val closeButton = Button("Chiudi") { close() }
            content.add(HorizontalLayout(closeButton, retryButton).apply { className = "lbi-wizard-actions" })
        }
    }
}