package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.*
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.repository.ImportedTableRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.service.*
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.DetachEvent
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.checkbox.CheckboxGroup
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.radiobutton.RadioButtonGroup
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.Tabs
import com.vaadin.flow.component.textfield.PasswordField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.Route
import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Pagina di creazione di un nuovo Dataset (schema a stella nativo:
 * Fatti + Dimensioni importati come tabelle separate). Solo admin.
 *
 * Sostituisce NewAnalysisWizardDialog (dialog a view singola). Crea un
 * DATASET (Area + sorgente + tabelle importate + dimensioni + metriche),
 * non un'Analisi (PivotView), che si crea dal menu Analisi.
 *
 * Flusso a 6 step, tutti su questa pagina:
 *   1 ORIGINE   nuova connessione / riuso dei parametri di una esistente
 *   2 CONNETTI  credenziali, connessione, schema
 *   3 TABELLE   scelta di esattamente 1 Fatti + N Dimensioni, con nome logico
 *   4 CHIAVI    chiave di JOIN per ogni Dimensione (proposta da nome colonna condiviso)
 *   5 COLONNE   una scheda per tabella, TUTTE le colonne importate di default
 *   6 CONFERMA  riepilogo, nome dataset, creazione
 *
 * REGOLE DI MODELLO (schema a stella, come l'engine associativo Qlik):
 * - Nessuna classe per tabella: tutto è dato (ImportedSourceTable /
 *   ImportedTable / ImportedColumn), lo schema si scopre a runtime.
 * - Una colonna con lo stesso nome nei Fatti e in una Dimensione È lo
 *   stesso campo: viene registrata una sola volta, sui Fatti. Nella
 *   Dimensione appare bloccata come "Condivisa con Fatti".
 * - La colonna scelta come chiave di JOIN è tecnica (ruolo Chiave,
 *   bloccato): non diventa dimensione né metrica.
 * - Un attributo con lo stesso nome in due Dimensioni diverse (es.
 *   DESCRIZIONE) diventa "Descrizione (Clienti)" / "Descrizione (Articoli)",
 *   altrimenti finirebbe per fondersi in un'unica dimensione.
 * - Le metriche esistono solo sulle colonne dei Fatti.
 *
 * Lo stato vive in memoria (WizardState) fino a "Crea Dataset": prima di
 * allora nulla viene scritto. La creazione scrive SOLO il registry
 * (Postgres): le tabelle ClickHouse le crea l'ETL alla prima
 * sincronizzazione, leggendo ImportedTable/ImportedColumn.
 */
@Route("nuovo-dataset")
class NewDatasetView(
    private val permissionCheckService: PermissionCheckService,
    private val authService: AuthService,
    private val metadataService: MetadataService,
    private val areaSourceRepository: AreaSourceRepository,
    private val registryService: RegistryService,
    private val registryRepository: RegistryRepository,
    private val importedTableRepository: ImportedTableRepository,
    private val cryptoService: CryptoService
) : VerticalLayout(), BeforeEnterObserver {

    // ================= Tipi interni =================

    private enum class Step(val titolo: String) {
        ORIGINE("Origine"),
        CONNETTI("Connessione"),
        TABELLE("Tabelle"),
        CHIAVI("Chiavi"),
        COLONNE("Colonne"),
        CONFERMA("Conferma")
    }

    private enum class Ruolo(val label: String) {
        DIMENSIONE("Dimensione"),
        METRICA("Metrica"),
        IGNORA("Ignora"),
        CHIAVE("Chiave di JOIN"),
        CONDIVISA("Condivisa con Fatti")
    }

    /** Selezione di una tabella dell'elenco allo step TABELLE. */
    private class TableSel(var incluso: Boolean, var ruolo: RuoloTabella, var nomeLogico: String)

    /** Scelta dell'utente per una colonna di una tabella importata. */
    private class ColumnChoice(
        val nome: String,
        val tipo: String,
        val esempio: String,
        var ruolo: Ruolo,
        val aggregazioni: MutableSet<TipoAggregazione> = mutableSetOf()
    ) {
        val bloccata: Boolean get() = ruolo == Ruolo.CHIAVE || ruolo == Ruolo.CONDIVISA
    }

    private class DimPlan(val nomeDimensione: String, val colonna: String, val viewName: String)
    private class MetricPlan(val nome: String, val colonna: String, val tipo: TipoAggregazione)
    private class Plan(val dimensioni: List<DimPlan>, val metriche: List<MetricPlan>)

    private class WizardState {
        // ORIGINE
        var riusaSorgente: Boolean = false
        var sorgenteRiusata: AreaSource? = null

        // CONNETTI
        var connection: Connection? = null
        var jdbcUrl: String = ""
        var username: String = ""
        var password: String = ""
        var driverClassName: String? = null
        var schemiDisponibili: List<String> = emptyList()
        var schema: String? = null

        // TABELLE (chiave = nome view/tabella sulla sorgente)
        var tabelleDisponibili: List<TableInfo> = emptyList()
        val selezioni: MutableMap<String, TableSel> = mutableMapOf()

        // CHIAVI (chiave = viewName della Dimensione, valore = colonna di JOIN)
        val chiavi: MutableMap<String, String> = mutableMapOf()

        // COLONNE
        val colonne: MutableMap<String, MutableList<ColumnChoice>> = mutableMapOf()
        var firmaColonne: String? = null

        // CONFERMA
        var nomeDataset: String = ""
    }

    private val driverOptions = listOf(
        "com.microsoft.sqlserver.jdbc.SQLServerDriver" to "SQL Server",
        "org.postgresql.Driver" to "PostgreSQL",
        "com.mysql.cj.jdbc.Driver" to "MySQL",
        "oracle.jdbc.OracleDriver" to "Oracle",
        "com.ibm.db2.jcc.DB2Driver" to "DB2"
    )

    private var state = WizardState()
    private var currentStep = Step.ORIGINE

    private val stepIndicator = HorizontalLayout().apply {
        isPadding = false
        isSpacing = true
    }
    private val stepBody = Div().apply { setWidthFull() }
    private val backButton = Button("← Indietro") { goBack() }
    private val nextButton = Button("Avanti →") { goNext() }.apply {
        addThemeVariants(ButtonVariant.LUMO_PRIMARY)
    }

    // ================= Accesso e ciclo di vita =================

    /**
     * Guardia d'accesso: l'URL /nuovo-dataset resta raggiungibile a mano
     * anche se la voce di menu è nascosta agli utenti normali, quindi il
     * controllo va rifatto qui (stesso criterio di AdminView).
     */
    override fun beforeEnter(event: BeforeEnterEvent) {
        val user = CurrentUserHolder.get()
        val isAdmin = user != null && permissionCheckService.hasPermission(user.roleName, "MANAGE_USERS")
        if (!isAdmin) {
            event.forwardTo(AssociativeExplorerView::class.java)
            return
        }
        closeConnectionQuietly()
        state = WizardState()
        currentStep = Step.ORIGINE
        buildPage()
    }

    override fun onDetach(detachEvent: DetachEvent) {
        closeConnectionQuietly()
        super.onDetach(detachEvent)
    }

    private fun closeConnectionQuietly() {
        try { state.connection?.close() } catch (_: Exception) { }
        state.connection = null
    }

    // ================= Pagina e navigazione =================

    private fun buildPage() {
        removeAll()
        setSizeFull()
        isPadding = false

        val menuGroups = listOf(
            LbiSidebarMenu.MenuGroup(
                label = "Dataset",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Torna ai dataset") { tornaAiDataset() },
                    LbiSidebarMenu.MenuEntry("+ Nuovo dataset") { }
                ),
                active = true
            )
        )

        val footer = HorizontalLayout(
            Button("Annulla") { tornaAiDataset() },
            backButton,
            nextButton
        ).apply {
            setWidthFull()
            justifyContentMode = FlexComponent.JustifyContentMode.END
        }

        val content = VerticalLayout(
            Span("Nuovo dataset").apply { className = "lbi-section-title" },
            stepIndicator,
            stepBody,
            footer
        ).apply {
            setSizeFull()
            setFlexGrow(1.0, stepBody)
        }

        val shell = LbiAppShell(menuGroups, content, authService)
        add(shell)
        setFlexGrow(1.0, shell)

        renderStep()
    }

    private fun tornaAiDataset() {
        getUI().ifPresent { it.navigate(AssociativeExplorerView::class.java) }
    }

    private fun renderStep() {
        stepIndicator.removeAll()
        Step.values().forEach { step ->
            stepIndicator.add(Span("${step.ordinal + 1}. ${step.titolo}").apply {
                style.set("font-weight", if (step == currentStep) "700" else "400")
                style.set("opacity", if (step.ordinal <= currentStep.ordinal) "1" else "0.5")
            })
        }

        stepBody.removeAll()
        stepBody.add(
            when (currentStep) {
                Step.ORIGINE -> buildStepOrigine()
                Step.CONNETTI -> buildStepConnetti()
                Step.TABELLE -> buildStepTabelle()
                Step.CHIAVI -> buildStepChiavi()
                Step.COLONNE -> buildStepColonne()
                Step.CONFERMA -> buildStepConferma()
            }
        )

        backButton.isEnabled = currentStep != Step.ORIGINE
        nextButton.text = if (currentStep == Step.CONFERMA) "Crea Dataset" else "Avanti →"
    }

    private fun goBack() {
        val prev = Step.values().getOrNull(currentStep.ordinal - 1) ?: return
        currentStep = prev
        renderStep()
    }

    private fun goNext() {
        val error = validateCurrentStep()
        if (error != null) {
            Notification.show(error, 6000, Notification.Position.MIDDLE)
            return
        }
        if (currentStep == Step.CONFERMA) {
            createDataset()
            return
        }
        currentStep = Step.values()[currentStep.ordinal + 1]
        renderStep()
    }

    /** Messaggio d'errore se lo step corrente non è completo, null se si può avanzare. */
    private fun validateCurrentStep(): String? = when (currentStep) {
        Step.ORIGINE ->
            if (state.riusaSorgente && state.sorgenteRiusata == null) "Seleziona una connessione esistente" else null

        Step.CONNETTI -> when {
            state.connection == null -> "Premi \"Connetti\" prima di proseguire"
            state.schemiDisponibili.isNotEmpty() && state.schema == null -> "Scegli lo schema"
            else -> null
        }

        Step.TABELLE -> validateTabelle()

        Step.CHIAVI -> {
            val senzaChiave = dimensioniScelte().filter { state.chiavi[it.viewName].isNullOrBlank() }
            if (senzaChiave.isNotEmpty())
                "Nessuna chiave di JOIN per: ${senzaChiave.joinToString(", ") { it.nomeLogico }}. " +
                        "Torna indietro e rimuovi la tabella, oppure scegline una con colonne in comune coi Fatti."
            else null
        }

        Step.COLONNE -> validateColonne()

        Step.CONFERMA -> validateConferma()
    }

    // ================= STEP 1: Origine =================

    private fun buildStepOrigine(): Component {
        val layout = VerticalLayout().apply { isPadding = false }
        layout.add(Span("Da dove arrivano i dati del nuovo dataset?").apply { className = "lbi-wizard-label" })

        val existingSources = try {
            areaSourceRepository.findAll().distinctBy { it.config.jdbcUrl to it.config.username }
        } catch (e: Exception) {
            Notification.show("Impossibile leggere le sorgenti esistenti: ${e.message}", 5000, Notification.Position.MIDDLE)
            emptyList()
        }

        val nuova = "Nuova connessione"
        val esistente = "Riusa una connessione già configurata"

        val modeGroup = RadioButtonGroup<String>().apply {
            setItems(nuova, esistente)
            isEnabled = existingSources.isNotEmpty()
            value = if (state.riusaSorgente && existingSources.isNotEmpty()) esistente else nuova
        }

        val existingCombo = ComboBox<AreaSource>("Connessione esistente").apply {
            setItems(existingSources)
            setItemLabelGenerator { src -> "${src.config.jdbcUrl} · ${src.config.username}" }
            setWidthFull()
            value = state.sorgenteRiusata
            isVisible = modeGroup.value == esistente
            addValueChangeListener { ev ->
                val src = ev.value
                state.sorgenteRiusata = src
                if (src != null) applySourceCredentials(src)
            }
        }

        modeGroup.addValueChangeListener { ev ->
            state.riusaSorgente = ev.value == esistente
            existingCombo.isVisible = state.riusaSorgente
            if (!state.riusaSorgente) state.sorgenteRiusata = null
        }

        if (existingSources.isEmpty()) {
            layout.add(Span("Nessuna connessione ancora configurata: si parte da una nuova.").apply {
                className = "lbi-wizard-label"
            })
        }
        layout.add(modeGroup, existingCombo)
        return layout
    }

    /** Precompila i parametri di connessione da una sorgente esistente (password esclusa). */
    private fun applySourceCredentials(src: AreaSource) {
        invalidateConnection()
        state.jdbcUrl = src.config.jdbcUrl
        state.username = src.config.username
        state.driverClassName = src.config.driverClassName
        state.password = ""
        state.schema = src.config.schema
    }

    // ================= STEP 2: Connessione + schema =================

    private fun buildStepConnetti(): Component {
        val layout = VerticalLayout().apply { isPadding = false }
        layout.add(Span("Connessione al database di origine").apply { className = "lbi-wizard-label" })

        if (state.riusaSorgente) {
            layout.add(Span("Parametri precompilati dalla connessione esistente. Reinserisci la password.").apply {
                className = "lbi-wizard-label"
            })
        }

        val jdbcUrlField = TextField("Indirizzo database (JDBC URL)").apply {
            placeholder = "jdbc:sqlserver://server:1433;databaseName=SEM;trustServerCertificate=true"
            value = state.jdbcUrl
            setWidthFull()
        }
        val driverCombo = ComboBox<Pair<String, String>>("Tipo database").apply {
            setItems(driverOptions)
            setItemLabelGenerator { it.second }
            value = driverOptions.find { it.first == state.driverClassName }
            setWidthFull()
        }
        val usernameField = TextField("Utente").apply { value = state.username; setWidthFull() }
        val passwordField = PasswordField("Password").apply { value = state.password; setWidthFull() }

        val statusSpan = Span().apply { className = "lbi-wizard-label" }
        val schemaCombo = ComboBox<String>("Schema").apply {
            setWidthFull()
            setItems(state.schemiDisponibili)
            value = state.schema
            isEnabled = state.connection != null && state.schemiDisponibili.isNotEmpty()
            addValueChangeListener { ev ->
                if (ev.isFromClient && ev.value != state.schema) resetTabelleState()
                state.schema = ev.value
            }
        }

        // Cambiare un parametro dopo aver connesso rende la connessione
        // stantia: si invalida, così Avanti non procede con credenziali
        // diverse da quelle realmente testate.
        val onCredentialEdit: () -> Unit = {
            statusSpan.text = ""
            invalidateConnection()
            schemaCombo.setItems(emptyList<String>())
            schemaCombo.isEnabled = false
        }
        jdbcUrlField.addValueChangeListener { if (it.isFromClient) { state.jdbcUrl = it.value; onCredentialEdit() } }
        driverCombo.addValueChangeListener { if (it.isFromClient) { state.driverClassName = it.value?.first; onCredentialEdit() } }
        usernameField.addValueChangeListener { if (it.isFromClient) { state.username = it.value; onCredentialEdit() } }
        passwordField.addValueChangeListener { if (it.isFromClient) { state.password = it.value; onCredentialEdit() } }

        val connectButton = Button("Connetti") {
            val driver = driverCombo.value
            if (jdbcUrlField.value.isNullOrBlank() || driver == null || usernameField.value.isNullOrBlank()) {
                Notification.show("Compila indirizzo, tipo database e utente")
                return@Button
            }
            try {
                closeConnectionQuietly()
                state.jdbcUrl = jdbcUrlField.value
                state.username = usernameField.value
                state.password = passwordField.value
                state.driverClassName = driver.first
                val conn = metadataService.connect(state.jdbcUrl, state.username, state.password, driver.first)
                state.connection = conn

                // Gli schemi di sistema non sono mai sorgenti dati.
                val sistema = setOf("information_schema", "sys", "guest", "pg_catalog", "pg_toast")
                state.schemiDisponibili = metadataService.listSchemas(conn)
                    .filter { it.lowercase() !in sistema && !it.startsWith("db_") }
                    .sorted()

                // Se lo schema precompilato (riuso) non esiste più, si riparte da vuoto.
                if (state.schema != null && state.schema !in state.schemiDisponibili) state.schema = null
                // Con un solo schema disponibile la scelta è ovvia.
                if (state.schema == null && state.schemiDisponibili.size == 1) state.schema = state.schemiDisponibili.first()

                resetTabelleState()
                schemaCombo.setItems(state.schemiDisponibili)
                schemaCombo.value = state.schema
                schemaCombo.isEnabled = state.schemiDisponibili.isNotEmpty()
                statusSpan.text = "Connessione riuscita: ${state.schemiDisponibili.size} schemi disponibili"
            } catch (e: Exception) {
                invalidateConnection()
                statusSpan.text = ""
                Notification.show("Errore di connessione: ${e.message}", 6000, Notification.Position.MIDDLE)
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        if (state.connection != null) {
            statusSpan.text = "Connessione attiva: ${state.schemiDisponibili.size} schemi disponibili"
        }

        layout.add(jdbcUrlField, driverCombo, usernameField, passwordField, connectButton, statusSpan, schemaCombo)
        return layout
    }

    /** Chiude la connessione e azzera tutto ciò che ne dipende. */
    private fun invalidateConnection() {
        closeConnectionQuietly()
        state.schemiDisponibili = emptyList()
        state.schema = null
        resetTabelleState()
    }

    /** Azzera tabelle, chiavi e colonne (cambiano con lo schema). */
    private fun resetTabelleState() {
        state.tabelleDisponibili = emptyList()
        state.selezioni.clear()
        state.chiavi.clear()
        state.colonne.clear()
        state.firmaColonne = null
    }

    // ================= STEP 3: Tabelle =================

    private fun buildStepTabelle(): Component {
        val layout = VerticalLayout().apply { isPadding = false; setSizeFull() }
        val conn = state.connection ?: return Span("Connessione non disponibile: torna indietro")

        if (state.tabelleDisponibili.isEmpty()) {
            state.tabelleDisponibili = try {
                metadataService.listTables(conn, state.schema).sortedBy { it.name.lowercase() }
            } catch (e: Exception) {
                return Span("Errore lettura tabelle: ${e.message}")
            }
        }
        val tutte = state.tabelleDisponibili
        if (tutte.isEmpty()) return Span("Nessuna tabella o view nello schema scelto")

        layout.add(Span(
            "Scegli UNA tabella Fatti e le tabelle Dimensione collegate. " +
                    "Il nome logico è quello che vedranno gli utenti."
        ).apply { className = "lbi-wizard-label" })

        val grid = Grid<TableInfo>().apply {
            setWidthFull()
            height = "440px"
        }

        val search = TextField("Cerca").apply {
            placeholder = "Digita per filtrare..."
            setWidthFull()
            addValueChangeListener { ev ->
                val q = ev.value
                grid.setItems(if (q.isNullOrBlank()) tutte else tutte.filter { it.name.contains(q, true) })
            }
        }

        fun selOf(t: TableInfo): TableSel =
            state.selezioni.getOrPut(t.name) { TableSel(false, RuoloTabella.DIMENSIONE, nomeLogicoDefault(t.name)) }

        grid.addComponentColumn { t ->
            val sel = selOf(t)
            Checkbox(sel.incluso).apply {
                addValueChangeListener { ev ->
                    sel.incluso = ev.value
                    // La prima tabella inclusa diventa Fatti se non ce n'è già una.
                    if (ev.value && state.selezioni.values.none { it.incluso && it.ruolo == RuoloTabella.FATTI }) {
                        sel.ruolo = RuoloTabella.FATTI
                    }
                    grid.dataProvider.refreshItem(t)
                }
            }
        }.setHeader("").setAutoWidth(true).setFlexGrow(0)

        grid.addColumn { it.name }.setHeader("Tabella / view").setAutoWidth(true).setFlexGrow(1)

        grid.addComponentColumn { t ->
            val sel = selOf(t)
            ComboBox<RuoloTabella>().apply {
                setItems(RuoloTabella.FATTI, RuoloTabella.DIMENSIONE)
                setItemLabelGenerator { if (it == RuoloTabella.FATTI) "Fatti" else "Dimensione" }
                isAllowCustomValue = false
                value = sel.ruolo
                isEnabled = sel.incluso
                width = "160px"
                addValueChangeListener { ev ->
                    val nuovo = ev.value ?: return@addValueChangeListener
                    sel.ruolo = nuovo
                    // Una sola tabella Fatti: le altre passano a Dimensione.
                    if (nuovo == RuoloTabella.FATTI) {
                        state.selezioni.forEach { (nome, other) ->
                            if (nome != t.name && other.ruolo == RuoloTabella.FATTI) other.ruolo = RuoloTabella.DIMENSIONE
                        }
                        grid.dataProvider.refreshAll()
                    }
                }
            }
        }.setHeader("Ruolo").setAutoWidth(true).setFlexGrow(0)

        grid.addComponentColumn { t ->
            val sel = selOf(t)
            TextField().apply {
                value = sel.nomeLogico
                isEnabled = sel.incluso
                width = "200px"
                addValueChangeListener { sel.nomeLogico = it.value }
            }
        }.setHeader("Nome logico").setAutoWidth(true).setFlexGrow(0)

        grid.setItems(tutte)
        layout.add(search, grid)
        return layout
    }

    /** Nome logico proposto: toglie il prefisso TeamSystem e mette la maiuscola iniziale. */
    private fun nomeLogicoDefault(viewName: String): String {
        val senza = viewName
            .replace(Regex("^QLK_VISTA", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^QLK_", RegexOption.IGNORE_CASE), "")
            .ifBlank { viewName }
        return senza.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun validateTabelle(): String? {
        val incluse = state.selezioni.filterValues { it.incluso }
        val fatti = incluse.filterValues { it.ruolo == RuoloTabella.FATTI }
        if (fatti.size != 1) return "Serve esattamente una tabella Fatti (ora: ${fatti.size})"
        if (incluse.values.any { it.nomeLogico.isBlank() }) return "Ogni tabella scelta deve avere un nome logico"
        val slugs = try {
            incluse.values.map { Naming.slug(it.nomeLogico) }
        } catch (e: Exception) {
            return "Nome logico non utilizzabile: ${e.message}"
        }
        val doppi = slugs.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (doppi.isNotEmpty()) return "Nomi logici duplicati: ${doppi.joinToString(", ")}"
        return null
    }

    /** Tabelle scelte, Fatti per prima, con la chiave di JOIN se già confermata. */
    private fun tabelleScelte(): List<ImportedSourceTable> =
        state.selezioni.entries
            .filter { it.value.incluso }
            .sortedWith(compareBy({ it.value.ruolo != RuoloTabella.FATTI }, { it.key.lowercase() }))
            .map { (view, sel) ->
                ImportedSourceTable(
                    nomeLogico = sel.nomeLogico.trim(),
                    viewName = view,
                    ruolo = sel.ruolo,
                    colonnaChiave = if (sel.ruolo == RuoloTabella.DIMENSIONE) state.chiavi[view] else null
                )
            }

    private fun fattiScelta(): ImportedSourceTable? = tabelleScelte().firstOrNull { it.ruolo == RuoloTabella.FATTI }
    private fun dimensioniScelte(): List<ImportedSourceTable> = tabelleScelte().filter { it.ruolo == RuoloTabella.DIMENSIONE }

    // ================= STEP 4: Chiavi =================

    private fun buildStepChiavi(): Component {
        val layout = VerticalLayout().apply { isPadding = false }
        val conn = state.connection ?: return Span("Connessione non disponibile: torna indietro")
        val fatti = fattiScelta() ?: return Span("Nessuna tabella Fatti: torna indietro")
        val dimensioni = dimensioniScelte()

        // Le scelte fatte per tabelle non più incluse non servono più.
        state.chiavi.keys.retainAll(dimensioni.map { it.viewName }.toSet())

        if (dimensioni.isEmpty()) {
            layout.add(Span("Nessuna tabella Dimensione: il dataset usa solo i Fatti, non ci sono chiavi da collegare."))
            return layout
        }

        layout.add(Span(
            "Come si collega ogni Dimensione ai Fatti (${fatti.nomeLogico}). " +
                    "Le chiavi proposte sono le colonne con lo stesso nome in entrambe le tabelle."
        ).apply { className = "lbi-wizard-label" })

        dimensioni.forEach { dim ->
            val candidate = try {
                proposeJoinKeys(conn, fatti.viewName, dim.viewName)
            } catch (e: Exception) {
                layout.add(Span("${dim.nomeLogico}: errore lettura colonne (${e.message})"))
                return@forEach
            }

            if (candidate.isEmpty()) {
                state.chiavi.remove(dim.viewName)
                layout.add(Span("⚠ ${dim.nomeLogico}: nessuna colonna in comune coi Fatti, non collegabile.").apply {
                    style.set("color", "var(--lumo-error-text-color)")
                })
                return@forEach
            }

            val preselezionata = state.chiavi[dim.viewName]?.takeIf { it in candidate } ?: candidate.first()
            state.chiavi[dim.viewName] = preselezionata

            layout.add(ComboBox<String>("${dim.nomeLogico}  (${dim.viewName})").apply {
                setItems(candidate)
                value = preselezionata
                isAllowCustomValue = false
                setWidthFull()
                addValueChangeListener { ev ->
                    if (ev.value != null) state.chiavi[dim.viewName] = ev.value
                }
            })
        }
        return layout
    }

    /**
     * Colonne candidate per il JOIN Fatti <-> Dimensione: stesso nome in
     * entrambe le tabelle (case-insensitive), come l'engine associativo
     * Qlik. Prima le chiavi TeamSystem (_KEY*), poi le altre in ordine
     * alfabetico. Nessun risultato = tabelle non collegabili.
     */
    private fun proposeJoinKeys(conn: Connection, tabellaFatti: String, tabellaDimensione: String): List<String> {
        val colonneFatti = metadataService.listColumns(conn, state.schema, tabellaFatti).map { it.name }
        val colonneDimensione = metadataService.listColumns(conn, state.schema, tabellaDimensione)
            .map { it.name.lowercase() }
            .toSet()
        return colonneFatti
            .filter { it.lowercase() in colonneDimensione }
            .sortedWith(compareByDescending<String> { it.startsWith("_key", ignoreCase = true) }.thenBy { it.lowercase() })
    }

    // ================= STEP 5: Colonne =================

    private val tipiNumerici = listOf("DECIMAL", "NUMERIC", "MONEY", "FLOAT", "REAL", "DOUBLE")

    private fun isNumerico(tipo: String): Boolean = tipiNumerici.any { tipo.uppercase().contains(it) }

    /**
     * Legge le colonne reali di ogni tabella scelta e propone il ruolo di
     * default. Rifatto solo se cambiano tabelle/ruoli/chiavi (firma): tornare
     * indietro e avanti senza cambiare nulla non perde le scelte dell'utente.
     * Ritorna un messaggio d'errore, o null se ok.
     */
    private fun prepareColonne(): String? {
        val conn = state.connection ?: return "Connessione non disponibile: torna indietro"
        val tabelle = tabelleScelte()
        val firma = tabelle.joinToString("|") { "${it.viewName}:${it.ruolo}:${it.colonnaChiave}" }
        if (state.firmaColonne == firma && state.colonne.isNotEmpty()) return null

        state.colonne.clear()
        val fatti = tabelle.first { it.ruolo == RuoloTabella.FATTI }
        val chiaviScelte = tabelle.mapNotNull { it.colonnaChiave?.lowercase() }.toSet()

        val infoPerTabella = try {
            tabelle.associate { t ->
                val cols = metadataService.listColumns(conn, state.schema, t.viewName)
                val samples = try { metadataService.sampleRows(conn, state.schema, t.viewName, 3) } catch (_: Exception) { emptyList() }
                t.viewName to (cols to samples)
            }
        } catch (e: Exception) {
            return "Errore lettura colonne: ${e.message}"
        }

        val nomiFatti = infoPerTabella[fatti.viewName]!!.first.map { it.name.lowercase() }.toSet()

        tabelle.forEach { t ->
            val (cols, samples) = infoPerTabella[t.viewName]!!
            val lista = cols.map { col ->
                val esempi = samples.mapNotNull { row -> row[col.name]?.toString() }.take(3)
                val esempio = if (esempi.isEmpty()) "—" else esempi.joinToString(", ")
                val n = col.name.lowercase()
                val ruolo: Ruolo = if (t.ruolo == RuoloTabella.FATTI) {
                    when {
                        n in chiaviScelte -> Ruolo.CHIAVE
                        n.startsWith("_key") -> Ruolo.IGNORA
                        isNumerico(col.typeName) -> Ruolo.METRICA
                        else -> Ruolo.DIMENSIONE
                    }
                } else {
                    when {
                        n == t.colonnaChiave?.lowercase() -> Ruolo.CHIAVE
                        n in nomiFatti -> Ruolo.CONDIVISA
                        n.startsWith("_key") -> Ruolo.IGNORA
                        else -> Ruolo.DIMENSIONE
                    }
                }
                ColumnChoice(col.name, col.typeName, esempio, ruolo).also {
                    if (ruolo == Ruolo.METRICA) it.aggregazioni.add(suggestDefaultAggregation(col.name))
                }
            }
            state.colonne[t.viewName] = lista.toMutableList()
        }
        state.firmaColonne = firma
        return null
    }

    private fun buildStepColonne(): Component {
        prepareColonne()?.let { return Span(it) }

        val layout = VerticalLayout().apply { isPadding = false; setSizeFull() }
        layout.add(Span(
            "Tutte le colonne sono importate di default. Imposta \"Ignora\" per escluderne una. " +
                    "Le metriche si definiscono solo sulle colonne dei Fatti."
        ).apply { className = "lbi-wizard-label" })

        val tabelle = tabelleScelte()
        val tabs = Tabs()
        val holder = Div().apply { setWidthFull() }
        val tabToTable = LinkedHashMap<Tab, ImportedSourceTable>()

        tabelle.forEach { t ->
            val tipo = if (t.ruolo == RuoloTabella.FATTI) "Fatti" else "Dimensione"
            val tab = Tab("${t.nomeLogico} · $tipo")
            tabs.add(tab)
            tabToTable[tab] = t
        }

        fun mostra(t: ImportedSourceTable) {
            holder.removeAll()
            holder.add(buildColumnsGrid(t))
        }

        tabs.addSelectedChangeListener { ev -> tabToTable[ev.selectedTab]?.let { mostra(it) } }
        tabToTable.values.firstOrNull()?.let { mostra(it) }

        layout.add(tabs, holder)
        return layout
    }

    private fun buildColumnsGrid(t: ImportedSourceTable): Component {
        val righe = state.colonne[t.viewName] ?: return Span("Nessuna colonna")
        val isFatti = t.ruolo == RuoloTabella.FATTI

        val grid = Grid<ColumnChoice>().apply {
            setWidthFull()
            height = "460px"
            setItems(righe)
        }

        grid.addColumn { it.nome }.setHeader("Colonna").setAutoWidth(true).setFlexGrow(1)
        grid.addColumn { it.tipo }.setHeader("Tipo").setAutoWidth(true).setFlexGrow(0)
        grid.addColumn { if (it.esempio.length > 50) it.esempio.take(50) + "…" else it.esempio }
            .setHeader("Esempio").setAutoWidth(true).setFlexGrow(1)

        grid.addComponentColumn { c ->
            if (c.bloccata) {
                Span(c.ruolo.label).apply { style.set("opacity", "0.6") }
            } else {
                ComboBox<Ruolo>().apply {
                    setItems(if (isFatti) listOf(Ruolo.DIMENSIONE, Ruolo.METRICA, Ruolo.IGNORA) else listOf(Ruolo.DIMENSIONE, Ruolo.IGNORA))
                    setItemLabelGenerator { it.label }
                    isAllowCustomValue = false
                    value = c.ruolo
                    width = "170px"
                    addValueChangeListener { ev ->
                        val nuovo = ev.value ?: return@addValueChangeListener
                        c.ruolo = nuovo
                        if (nuovo == Ruolo.METRICA && c.aggregazioni.isEmpty()) {
                            c.aggregazioni.add(suggestDefaultAggregation(c.nome))
                        }
                        grid.dataProvider.refreshItem(c)
                    }
                }
            }
        }.setHeader("Ruolo").setAutoWidth(true).setFlexGrow(0)

        if (isFatti) {
            grid.addComponentColumn { c ->
                if (c.ruolo != Ruolo.METRICA) {
                    Span("")
                } else {
                    CheckboxGroup<TipoAggregazione>().apply {
                        setItems(*TipoAggregazione.values())
                        setItemLabelGenerator { aggregationLabel(it) }
                        value = c.aggregazioni.toSet()
                        addValueChangeListener { ev ->
                            c.aggregazioni.clear()
                            c.aggregazioni.addAll(ev.value)
                        }
                    }
                }
            }.setHeader("Aggregazioni").setAutoWidth(true).setFlexGrow(1)
        }
        return grid
    }

    private fun aggregationLabel(tipo: TipoAggregazione): String = when (tipo) {
        TipoAggregazione.SUM -> "Somma"
        TipoAggregazione.AVG -> "Media"
        TipoAggregazione.COUNT -> "Conteggio"
        TipoAggregazione.COUNT_DISTINCT -> "Conteggio distinto"
        TipoAggregazione.MIN -> "Minimo"
        TipoAggregazione.MAX -> "Massimo"
    }

    /** Aggregazione più plausibile dal nome colonna: solo un default, modificabile. */
    private fun suggestDefaultAggregation(colName: String): TipoAggregazione {
        val n = colName.lowercase()
        val mediaHints = listOf("giorni", "tempo", "durata", "media", "pct", "percentuale", "rate")
        return if (mediaHints.any { n.contains(it) }) TipoAggregazione.AVG else TipoAggregazione.SUM
    }

    private fun suggestMetricName(colName: String, tipo: TipoAggregazione): String {
        val leggibile = colName.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
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

    // ================= Piano di creazione (dimensioni + metriche) =================

    /**
     * Traduce le scelte dell'utente in dimensioni e metriche da registrare.
     * Usato sia per validare (step COLONNE/CONFERMA) sia per creare.
     */
    private fun buildPlan(): Plan {
        val fatti = fattiScelta()!!
        val dims = mutableListOf<DimPlan>()
        val metriche = mutableListOf<MetricPlan>()

        state.colonne[fatti.viewName].orEmpty().forEach { c ->
            when (c.ruolo) {
                Ruolo.DIMENSIONE -> dims += DimPlan(c.nome, c.nome, fatti.viewName)
                Ruolo.METRICA -> c.aggregazioni.sortedBy { it.ordinal }.forEach { agg ->
                    metriche += MetricPlan(suggestMetricName(c.nome, agg), c.nome, agg)
                }
                else -> Unit
            }
        }

        // Attributi delle Dimensioni: se lo stesso nome compare in più
        // Dimensioni si aggiunge il nome logico della tabella.
        val daDimensioni = dimensioniScelte().flatMap { t ->
            state.colonne[t.viewName].orEmpty().filter { it.ruolo == Ruolo.DIMENSIONE }.map { t to it }
        }
        val conteggio = daDimensioni.groupingBy { it.second.nome.lowercase() }.eachCount()
        daDimensioni.forEach { (t, c) ->
            val nome = if ((conteggio[c.nome.lowercase()] ?: 0) > 1) "${c.nome} (${t.nomeLogico})" else c.nome
            dims += DimPlan(nome, c.nome, t.viewName)
        }
        return Plan(dims, metriche)
    }

    private fun validateColonne(): String? {
        val fatti = fattiScelta() ?: return "Nessuna tabella Fatti"
        val plan = buildPlan()
        if (plan.dimensioni.isEmpty()) return "Serve almeno una dimensione"

        val metricheSenzaAgg = state.colonne[fatti.viewName].orEmpty()
            .filter { it.ruolo == Ruolo.METRICA && it.aggregazioni.isEmpty() }
        if (metricheSenzaAgg.isNotEmpty())
            return "Scegli almeno un'aggregazione per: ${metricheSenzaAgg.joinToString(", ") { it.nome }}"
        if (plan.metriche.isEmpty()) return "Serve almeno una metrica (colonna dei Fatti con ruolo Metrica)"

        // Colonne fisiche che collidono dopo la normalizzazione, per tabella.
        tabelleScelte().forEach { t ->
            val usate = state.colonne[t.viewName].orEmpty().filter { it.ruolo != Ruolo.IGNORA }
            val fisici = try {
                usate.map { Naming.column(it.nome) }
            } catch (e: Exception) {
                return "Colonna non utilizzabile in ${t.nomeLogico}: ${e.message}"
            }
            val doppi = fisici.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (doppi.isNotEmpty())
                return "In ${t.nomeLogico} ci sono colonne che collidono dopo la normalizzazione: ${doppi.joinToString(", ")}. Impostane una su Ignora."
        }

        try { plan.dimensioni.forEach { Naming.slug(it.nomeDimensione) } }
        catch (e: Exception) { return "Nome dimensione non utilizzabile: ${e.message}" }

        val nomiMetriche = plan.metriche.map { it.nome.lowercase() }
        val doppie = nomiMetriche.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (doppie.isNotEmpty()) return "Nomi di metrica duplicati: ${doppie.joinToString(", ")}"
        return null
    }

    // ================= STEP 6: Conferma =================

    private fun buildStepConferma(): Component {
        val layout = VerticalLayout().apply { isPadding = false }
        val fatti = fattiScelta() ?: return Span("Nessuna tabella Fatti: torna indietro")
        val plan = buildPlan()

        layout.add(TextField("Nome del dataset").apply {
            value = state.nomeDataset.ifBlank { fatti.nomeLogico }
            state.nomeDataset = value
            setWidthFull()
            addValueChangeListener { state.nomeDataset = it.value }
        })

        layout.add(Span("Riepilogo").apply { className = "lbi-wizard-label" })
        layout.add(Span("Fatti: ${fatti.nomeLogico} (${fatti.viewName})"))
        dimensioniScelte().forEach { d ->
            val nDim = plan.dimensioni.count { it.viewName == d.viewName }
            layout.add(Span("Dimensione: ${d.nomeLogico} (${d.viewName}) — chiave ${d.colonnaChiave}, $nDim campi"))
        }
        layout.add(Span("Dimensioni sui Fatti: ${plan.dimensioni.count { it.viewName == fatti.viewName }}"))
        layout.add(Span("Metriche: ${plan.metriche.size} (${plan.metriche.joinToString(", ") { it.nome }})"))
        layout.add(Span(
            "Alla creazione viene registrato il dataset. I dati si caricano con \"Sincronizza\"."
        ).apply { className = "lbi-wizard-label" })
        return layout
    }

    // ================= Nomi fisici =================

    /** Nome del database sorgente per il naming (catalog della connessione, poi schema). */
    private fun nomeDbSorgente(): String {
        val catalog = try { state.connection?.catalog } catch (_: Exception) { null }
        return catalog?.takeIf { it.isNotBlank() } ?: state.schema?.takeIf { it.isNotBlank() } ?: "db"
    }

    /** viewName -> nome fisico ClickHouse (<motore>_<db>__<nome>). */
    private fun nomiFisici(): Map<String, String> {
        val motore = Naming.motoreFromDriver(state.driverClassName ?: "")
        val db = nomeDbSorgente()
        return tabelleScelte().associate { it.viewName to Naming.importedTable(motore, db, it.nomeLogico) }
    }

    private fun validateConferma(): String? {
        val nome = state.nomeDataset.trim()
        if (nome.isBlank()) return "Indica il nome del dataset"
        try { Naming.areaTable(nome) } catch (e: Exception) { return "Nome dataset non utilizzabile: ${e.message}" }
        if (registryRepository.findAreaByNome(nome) != null) return "Esiste già un dataset chiamato \"$nome\""

        val fisici = try { nomiFisici() } catch (e: Exception) { return "Nome tabella non utilizzabile: ${e.message}" }

        // Una tabella ClickHouse importata appartiene a un solo dataset.
        val giaUsati = registryRepository.findAllAree()
            .flatMap { importedTableRepository.findByArea(it.id) }
            .map { it.tabellaFisica }
            .toSet()
        val conflitti = fisici.values.filter { it in giaUsati }
        if (conflitti.isNotEmpty())
            return "Tabelle già importate da un altro dataset: ${conflitti.joinToString(", ")}. " +
                    "Cambia il nome logico o usa tabelle diverse."
        return validateColonne()
    }

    // ================= Creazione =================

    /**
     * Scrive il registry: Area, AreaSource (con l'elenco tabelle),
     * ImportedTable/ImportedColumn, dimensioni collegate alla tabella di
     * provenienza, metriche. Nessuna tabella ClickHouse: le crea l'ETL.
     * Non essendoci una transazione unica su tutti i passi, un errore a
     * metà annulla a mano quanto già scritto (rollback()).
     */
    private fun createDataset() {
        val nome = state.nomeDataset.trim()
        val plan = buildPlan()
        val tabelle = tabelleScelte()
        val fatti = tabelle.first { it.ruolo == RuoloTabella.FATTI }
        val fisici = nomiFisici()
        var areaId: UUID? = null

        try {
            val area = registryService.createArea(nome, fisici.getValue(fatti.viewName))
            areaId = area.id

            val sourceId = UUID.randomUUID()
            areaSourceRepository.save(
                AreaSource(
                    id = sourceId,
                    areaId = area.id,
                    tipoSorgente = "jdbc",
                    config = SourceConfig(
                        jdbcUrl = state.jdbcUrl,
                        username = state.username,
                        encryptedPassword = cryptoService.encrypt(state.password),
                        driverClassName = state.driverClassName!!,
                        schema = state.schema,
                        tabelle = tabelle,
                        syncMode = SyncMode.FULL_RELOAD
                    ),
                    // Le view QLK_* esistono già sulla sorgente e le abbiamo
                    // appena lette: non c'è nessuna view da creare o verificare.
                    status = SourceStatus.VERIFIED,
                    errorDetail = null,
                    createdAt = Instant.now()
                )
            )

            val idTabelle = mutableMapOf<String, UUID>()
            tabelle.forEach { t ->
                val id = UUID.randomUUID()
                idTabelle[t.viewName] = id
                importedTableRepository.save(
                    ImportedTable(
                        id = id,
                        areaId = area.id,
                        nomeLogico = t.nomeLogico,
                        tabellaFisica = fisici.getValue(t.viewName),
                        ruolo = t.ruolo,
                        sourceId = sourceId,
                        colonnaChiave = t.colonnaChiave
                    )
                )
                importedTableRepository.saveColumns(
                    state.colonne[t.viewName].orEmpty()
                        .filter { it.ruolo != Ruolo.IGNORA }
                        .map { c ->
                            ImportedColumn(
                                id = UUID.randomUUID(),
                                importedTableId = id,
                                nome = c.nome,
                                tipo = c.tipo,
                                isChiave = c.ruolo == Ruolo.CHIAVE || c.ruolo == Ruolo.CONDIVISA
                            )
                        }
                )
            }

            plan.dimensioni.forEach { d ->
                val dimensione = registryService.findOrCreateDimensione(d.nomeDimensione)
                registryRepository.saveAreaDimensione(
                    AreaDimensione(
                        areaId = area.id,
                        dimensioneId = dimensione.id,
                        colonnaFisica = Naming.column(d.colonna),
                        obbligatoria = false,
                        cardinalitaStimata = null,
                        valoreGrezzo = false,
                        importedTableId = idTabelle.getValue(d.viewName)
                    )
                )
            }

            plan.metriche.forEach { m ->
                registryService.addMetrica(
                    areaId = area.id,
                    nome = m.nome,
                    colonnaFisica = m.colonna,
                    tipoAggregazione = m.tipo
                )
            }
            registryRepository.bumpVersion()

            closeConnectionQuietly()
            Notification.show(
                "Dataset \"$nome\" creato. Premi \"Sincronizza\" per caricare i dati.",
                6000, Notification.Position.MIDDLE
            )
            getUI().ifPresent { it.navigate(AssociativeExplorerView::class.java, area.id.toString()) }
        } catch (e: Exception) {
            areaId?.let { rollback(it) }
            Notification.show("Errore nella creazione: ${e.message}", 8000, Notification.Position.MIDDLE)
        }
    }

    /**
     * Annulla quanto scritto per un dataset creato a metà. L'ordine conta
     * per le chiavi esterne: dimensioni e metriche, poi le tabelle importate
     * (le loro colonne cadono in cascata), poi la sorgente, infine l'area.
     */
    private fun rollback(areaId: UUID) {
        fun tenta(passo: () -> Unit) { try { passo() } catch (_: Exception) { } }
        tenta { registryRepository.deleteAreaMetricheByArea(areaId) }
        tenta { registryRepository.deleteAreaDimensioniByArea(areaId) }
        tenta { importedTableRepository.deleteByArea(areaId) }
        tenta { areaSourceRepository.findByArea(areaId).forEach { areaSourceRepository.delete(it.id) } }
        tenta { registryRepository.deleteArea(areaId) }
        tenta { registryRepository.bumpVersion() }
    }
}