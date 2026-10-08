package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.connector.ConnectionOrchestrator
import com.lightningbi.lightning_engine.etl.EsitoSync
import com.lightningbi.lightning_engine.etl.EtlOrchestrator
import com.lightningbi.lightning_engine.connector.JdbcSourceConnector
import com.lightningbi.lightning_engine.model.ImportedTable
import com.lightningbi.lightning_engine.model.ModalitaSync
import com.lightningbi.lightning_engine.model.RuoloTabella
import com.lightningbi.lightning_engine.model.SourceConnection
import com.lightningbi.lightning_engine.service.AdminGuard
import com.lightningbi.lightning_engine.service.AuthService
import com.lightningbi.lightning_engine.service.ColumnProposal
import com.lightningbi.lightning_engine.service.ImportazioneMultiplaService
import com.lightningbi.lightning_engine.service.TabellaImportataInfo
import com.lightningbi.lightning_engine.service.TableImportService
import com.lightningbi.lightning_engine.service.TableSyncService
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dependency.Uses
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.menubar.MenuBarVariant
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.progressbar.ProgressBar
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.Tabs
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.Route
import java.time.format.DateTimeFormatter

/**
 * Pagina amministrativa "Tabelle importate": le tabelle (Fatti e Dimensioni)
 * che si portano su ClickHouse e si riusano in N dataset, e le connessioni
 * da cui arrivano. Riservata all'admin (MANAGE_USERS): la guardia sta in
 * [beforeEnter] e ogni azione è controllata di nuovo dai servizi.
 *
 * La creazione e la modifica stanno in quattro finestre: [ConnectionDialog],
 * [AddTableDialog], [ImportaPiuTabelleDialog] e [TableSyncDialog]. "Sincronizza"
 * gira in un thread a parte e mostra l'avanzamento in una finestra; "Ricarico
 * completo" forza la ricreazione e il ricarico di tutta la tabella;
 * "Sincronizza tutto" lancia una dopo l'altra tutte le tabelle (Dimensioni
 * prima, Fatti dopo, in ordine alfabetico).
 */
@Route("tabelle-importate")
@Uses(Icon::class)
class TabelleImportateView(
    private val tableImportService: TableImportService,
    private val tableSyncService: TableSyncService,
    private val etlOrchestrator: EtlOrchestrator,
    private val connectionOrchestrator: ConnectionOrchestrator,
    private val importazioneMultiplaService: ImportazioneMultiplaService,
    private val adminGuard: AdminGuard,
    private val authService: AuthService
) : VerticalLayout(), BeforeEnterObserver {

    private val tabelleGrid = Grid<TabellaImportataInfo>()
    private val connessioniGrid = Grid<SourceConnection>()
    private var paginaCostruita = false

    /** Tabelle in sincronizzazione da questa pagina (solo thread della UI). */
    private val inCorso = mutableSetOf<java.util.UUID>()

    private val formatoData = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    /** Una riga del rapporto di "Sincronizza tutto". */
    private class RigaRapporto(val nome: String, val ruolo: String, val esito: String, val dettaglio: String)

    override fun beforeEnter(event: BeforeEnterEvent) {
        if (!adminGuard.isAdmin()) {
            event.forwardTo(DatasetFiltriView::class.java)
            return
        }
        if (!paginaCostruita) {
            buildPage()
            paginaCostruita = true
        }
    }

    private fun buildPage() {
        removeAll()
        setSizeFull()
        isPadding = false

        val menuGroups = listOf(
            LbiSidebarMenu.MenuGroup(
                label = "Analisi",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Torna alle analisi", icon = VaadinIcon.ARROW_LEFT) {
                        getUI().ifPresent { it.navigate(DatasetFiltriView::class.java) }
                    }
                ),
                icon = VaadinIcon.CHART
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Amministrazione",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Tabelle importate", icon = VaadinIcon.DATABASE) { },
                    LbiSidebarMenu.MenuEntry("Gestione utenti", icon = VaadinIcon.USERS) {
                        getUI().ifPresent { it.navigate(AdminView::class.java) }
                    }
                ),
                active = true,
                icon = VaadinIcon.COG
            )
        )

        val shell = LbiAppShell(menuGroups, buildContent(), authService)
        add(shell)
        setFlexGrow(1.0, shell)
    }

    private fun buildContent(): Component {
        val layout = VerticalLayout().apply { setSizeFull() }

        val tabTabelle = Tab("Tabelle importate")
        val tabConnessioni = Tab("Connessioni")
        val tabs = Tabs(tabTabelle, tabConnessioni)

        val pannelloTabelle = buildPannelloTabelle()
        val pannelloConnessioni = buildPannelloConnessioni().apply { isVisible = false }

        tabs.addSelectedChangeListener {
            val tabelle = tabs.selectedTab == tabTabelle
            pannelloTabelle.isVisible = tabelle
            pannelloConnessioni.isVisible = !tabelle
        }

        layout.add(tabs, pannelloTabelle, pannelloConnessioni)
        ricarica()
        return layout
    }

    // ================= Tabelle importate =================

    private fun buildPannelloTabelle(): VerticalLayout {
        val layout = VerticalLayout().apply { isPadding = false; setSizeFull() }

        val aggiungi = Button("Aggiungi tabella", Icon(VaadinIcon.PLUS)) { apriAggiungiTabella() }
            .apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        val importaPiu = Button("Importa più tabelle", Icon(VaadinIcon.DOWNLOAD)) { apriImportaPiu() }
        val sincronizzaTutto = Button("Sincronizza tutto", Icon(VaadinIcon.REFRESH)) { confermaSincronizzaTutto() }
        val barra = HorizontalLayout(aggiungi, importaPiu, sincronizzaTutto).apply { isPadding = false }

        tabelleGrid.apply {
            setSizeFull()
            addColumn { it.tabella.nomeLogico }.setHeader("Tabella").setAutoWidth(true)
            addColumn { if (it.tabella.ruolo == RuoloTabella.FATTI) "Fatti" else "Dimensione" }
                .setHeader("Ruolo").setAutoWidth(true)
            addColumn { it.connessione ?: "—" }.setHeader("Connessione").setAutoWidth(true)
            addColumn { origine(it.tabella) }.setHeader("Origine").setAutoWidth(true)
            addColumn { it.nColonne }.setHeader("Colonne").setAutoWidth(true)
            addColumn { if (it.dataset.isEmpty()) "—" else it.dataset.joinToString(", ") }
                .setHeader("Dataset").setAutoWidth(true)
            addColumn { statoSincronizzazione(it) }.setHeader("Sincronizzazione").setAutoWidth(true)
            addComponentColumn { info ->
                val sincronizza = Button("Sincronizza") { sincronizza(info, forzaCompleta = false) }.apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY)
                }
                val campi = Button("Campi") { apriCampi(info) }.apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL)
                }
                val altro = MenuBar().apply {
                    addClassName("lbi-menu-righe")
                    addThemeVariants(MenuBarVariant.LUMO_TERTIARY_INLINE)
                    val radice = addItem(Icon(VaadinIcon.ELLIPSIS_DOTS_V).apply { style.set("color", "var(--lbi-text)") })
                    radice.subMenu.addItem("Ricarico completo") { confermaRicaricoCompleto(info) }
                    radice.subMenu.addItem("Configura sincronizzazione") { apriSincronizzazione(info) }
                    radice.subMenu.addItem("Campi") { apriCampi(info) }
                    radice.subMenu.addItem("Elimina") { confermaEliminaTabella(info) }
                }
                HorizontalLayout(sincronizza, campi, altro).apply { isPadding = false }
            }.setHeader("").setFrozenToEnd(true).setAutoWidth(true).setFlexGrow(0)
        }

        layout.add(barra, tabelleGrid)
        layout.setFlexGrow(1.0, tabelleGrid)
        return layout
    }

    private fun origine(t: ImportedTable): String {
        val schema = t.schemaOrigine?.takeIf { it.isNotBlank() }?.let { "$it." } ?: ""
        return schema + (t.nomeOrigine ?: "—")
    }

    private fun statoSincronizzazione(info: TabellaImportataInfo): String {
        val modalita = when (info.modalita) {
            ModalitaSync.COMPLETA -> "Completa"
            ModalitaSync.INCREMENTALE -> "Incrementale"
            null -> "Non configurata"
        }
        val ultima = info.ultimaSyncInizio?.format(formatoData)?.let { "ultima: $it" } ?: "mai sincronizzata"
        return "$modalita · $ultima"
    }

    private fun apriAggiungiTabella() {
        if (connectionOrchestrator.findAll().isEmpty()) {
            Notification.show("Crea prima una connessione, dalla scheda Connessioni", 5000, Notification.Position.MIDDLE)
            return
        }
        AddTableDialog(connectionOrchestrator, tableImportService) { ricarica() }.open()
    }

    private fun apriImportaPiu() {
        if (connectionOrchestrator.findAll().isEmpty()) {
            Notification.show("Crea prima una connessione, dalla scheda Connessioni", 5000, Notification.Position.MIDDLE)
            return
        }
        ImportaPiuTabelleDialog(connectionOrchestrator, importazioneMultiplaService, adminGuard) { ricarica() }.open()
    }

    private fun apriSincronizzazione(info: TabellaImportataInfo) {
        TableSyncDialog(tableSyncService, tableImportService, info.tabella) { ricarica() }.open()
    }

    private fun apriCampi(info: TabellaImportataInfo) {
        CampiTabellaDialog(tableImportService, info.tabella, info.dataset.isNotEmpty()) { ricarica() }.open()
    }

    private fun confermaRicaricoCompleto(info: TabellaImportataInfo) {
        conferma(
            titolo = "Ricarico completo di \"${info.tabella.nomeLogico}\"?",
            messaggio = "La tabella viene ricreata e ricaricata per intero dalla sorgente. " +
                    "Su tabelle grandi può richiedere molto tempo.",
            etichetta = "Ricarica tutto",
            pericolo = false
        ) { sincronizza(info, forzaCompleta = true) }
    }

    /**
     * Lancia la sincronizzazione in un thread a parte, così la pagina resta
     * viva, e mostra l'avanzamento in una finestra. Il controllo "solo admin"
     * si fa qui, nel thread della UI: l'utente sta nella sessione Vaadin.
     */
    private fun sincronizza(info: TabellaImportataInfo, forzaCompleta: Boolean) {
        try {
            adminGuard.requireAdmin()
        } catch (e: SecurityException) {
            Notification.show(e.message ?: "Operazione non consentita")
            return
        }
        val id = info.tabella.id
        if (!inCorso.add(id)) {
            Notification.show("Sincronizzazione già in corso per questa tabella")
            return
        }
        val ui = UI.getCurrent()
        if (ui == null) {
            inCorso.remove(id)
            return
        }

        val avanzamento = Span("Avvio...")
        val dialog = Dialog().apply {
            headerTitle = "Sincronizzazione di \"${info.tabella.nomeLogico}\""
            width = "520px"
            isCloseOnOutsideClick = false
            isCloseOnEsc = false
        }
        dialog.add(
            VerticalLayout(ProgressBar().apply { isIndeterminate = true }, avanzamento).apply { isPadding = false }
        )
        dialog.open()

        val thread = Thread {
            try {
                val esito = etlOrchestrator.syncTabella(id, forzaCompleta) { fase ->
                    aggiornaUi(ui) { avanzamento.text = fase }
                }
                aggiornaUi(ui) {
                    dialog.close()
                    inCorso.remove(id)
                    Notification.show(descriviEsito(esito), 7000, Notification.Position.MIDDLE)
                    ricarica()
                }
            } catch (e: Exception) {
                aggiornaUi(ui) {
                    dialog.close()
                    inCorso.remove(id)
                    Notification.show(
                        "Sincronizzazione fallita: ${e.message ?: e::class.simpleName}",
                        10000, Notification.Position.MIDDLE
                    )
                    ricarica()
                }
            }
        }
        thread.isDaemon = true
        thread.name = "sync-${info.tabella.nomeLogico}"
        thread.start()
    }

    // ---------- Sincronizza tutto ----------

    /** Le tabelle da sincronizzare: Dimensioni prima, Fatti dopo, in ordine alfabetico; salta quelle già in corso. */
    private fun tabelleDaSincronizzare(): List<ImportedTable> =
        tableImportService.elenco()
            .map { it.tabella }
            .filter { it.id !in inCorso }
            .sortedWith(
                compareBy<ImportedTable>({ if (it.ruolo == RuoloTabella.FATTI) 1 else 0 }, { it.nomeLogico.lowercase() })
            )

    private fun confermaSincronizzaTutto() {
        val n = tabelleDaSincronizzare().size
        if (n == 0) {
            Notification.show("Nessuna tabella da sincronizzare")
            return
        }
        conferma(
            titolo = "Sincronizzare tutte le tabelle ($n)?",
            messaggio = "Una dopo l'altra: prima le Dimensioni, poi i Fatti, in ordine alfabetico. " +
                    "Può richiedere molto tempo. Una tabella in errore non ferma le altre.",
            etichetta = "Sincronizza tutto",
            pericolo = false
        ) { avviaSincronizzaTutto() }
    }

    private fun avviaSincronizzaTutto() {
        try {
            adminGuard.requireAdmin()
        } catch (e: SecurityException) {
            Notification.show(e.message ?: "Operazione non consentita")
            return
        }
        val tabelle = tabelleDaSincronizzare()
        if (tabelle.isEmpty()) return
        val ui = UI.getCurrent() ?: return
        inCorso.addAll(tabelle.map { it.id })

        val avanzamento = Span("Avvio...")
        val barra = ProgressBar().apply { min = 0.0; max = tabelle.size.toDouble(); value = 0.0 }
        val corpo = VerticalLayout(barra, avanzamento).apply { isPadding = false }
        val chiudi = Button("Chiudi").apply { isEnabled = false }
        val dialog = Dialog().apply {
            headerTitle = "Sincronizza tutto"
            width = "760px"
            isCloseOnOutsideClick = false
            isCloseOnEsc = false
            add(corpo)
            footer.add(chiudi)
        }
        chiudi.addClickListener { dialog.close() }
        dialog.open()

        val thread = Thread {
            val rapporto = mutableListOf<RigaRapporto>()
            tabelle.forEachIndexed { i, tabella ->
                val ruolo = if (tabella.ruolo == RuoloTabella.FATTI) "Fatti" else "Dimensione"
                val prefisso = "${i + 1} di ${tabelle.size} · ${tabella.nomeLogico}"
                aggiornaUi(ui) { avanzamento.text = prefisso }
                try {
                    val esito = etlOrchestrator.syncTabella(tabella.id, false) { fase ->
                        aggiornaUi(ui) { avanzamento.text = "$prefisso · $fase" }
                    }
                    rapporto += RigaRapporto(tabella.nomeLogico, ruolo, "OK", descriviEsito(esito))
                } catch (e: Exception) {
                    rapporto += RigaRapporto(tabella.nomeLogico, ruolo, "Errore", e.message ?: e::class.simpleName.orEmpty())
                }
                aggiornaUi(ui) {
                    inCorso.remove(tabella.id)
                    barra.value = (i + 1).toDouble()
                }
            }
            aggiornaUi(ui) {
                inCorso.removeAll(tabelle.map { it.id }.toSet())
                mostraRapportoSincronizzazione(corpo, rapporto)
                chiudi.isEnabled = true
                dialog.isCloseOnEsc = true
                ricarica()
            }
        }
        thread.isDaemon = true
        thread.name = "sync-tutto"
        thread.start()
    }

    private fun mostraRapportoSincronizzazione(corpo: VerticalLayout, rapporto: List<RigaRapporto>) {
        val ok = rapporto.count { it.esito == "OK" }
        val errori = rapporto.count { it.esito == "Errore" }
        val griglia = Grid<RigaRapporto>().apply {
            setWidthFull()
            height = "380px"
            addColumn { it.nome }.setHeader("Tabella").setAutoWidth(true)
            addColumn { it.ruolo }.setHeader("Ruolo").setAutoWidth(true)
            addColumn { it.esito }.setHeader("Esito").setAutoWidth(true)
            addColumn { it.dettaglio }.setHeader("Dettaglio").setAutoWidth(true).setFlexGrow(1)
            setItems(rapporto)
        }
        corpo.removeAll()
        corpo.add(Span("Sincronizzate: $ok · Errori: $errori"), griglia)
    }

    /** Aggiorna la UI dal thread di sincronizzazione; se la pagina è stata chiusa non fa niente. */
    private fun aggiornaUi(ui: UI, azione: () -> Unit) {
        try {
            ui.access { azione() }
        } catch (_: Exception) {
            // UI staccata: l'utente ha lasciato la pagina, la sincronizzazione prosegue comunque.
        }
    }

    private fun descriviEsito(esito: EsitoSync): String = when (esito.modalita) {
        ModalitaSync.COMPLETA ->
            "Sincronizzazione completa: ${esito.righeCaricate} righe caricate, ${esito.righeScartate} scartate"
        ModalitaSync.INCREMENTALE ->
            "Sincronizzazione incrementale: ${esito.unitaSostituite} unità sostituite, " +
                    "${esito.unitaEliminate} eliminate, ${esito.righeCaricate} righe riscritte"
    }

    private fun confermaEliminaTabella(info: TabellaImportataInfo) {
        conferma(
            titolo = "Eliminare \"${info.tabella.nomeLogico}\"?",
            messaggio = "La tabella e i dati caricati su ClickHouse verranno rimossi. L'operazione non è reversibile."
        ) {
            try {
                tableImportService.elimina(info.tabella.id)
                Notification.show("Tabella eliminata")
            } catch (e: Exception) {
                Notification.show(e.message ?: "Errore", 6000, Notification.Position.MIDDLE)
            }
            ricarica()
        }
    }

    // ================= Connessioni =================

    private fun buildPannelloConnessioni(): VerticalLayout {
        val layout = VerticalLayout().apply { isPadding = false; setSizeFull() }

        val nuova = Button("Nuova connessione", Icon(VaadinIcon.PLUS)) { apriConnessione(null) }
            .apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        connessioniGrid.apply {
            setSizeFull()
            addColumn { it.nome }.setHeader("Nome").setAutoWidth(true)
            addColumn { it.tipo }.setHeader("Tipo").setAutoWidth(true)
            addColumn { it.parametri[JdbcSourceConnector.PARAM_DATABASE] ?: "—" }.setHeader("Database").setAutoWidth(true)
            addColumn { it.parametri[JdbcSourceConnector.PARAM_JDBC_URL] ?: "—" }.setHeader("Indirizzo").setAutoWidth(true)
            addColumn { elencoProprieta(it, ColumnProposal.PREFISSI_DA_TOGLIERE) }
                .setHeader("Prefissi da togliere").setAutoWidth(true)
            addColumn { elencoProprieta(it, ColumnProposal.PREFISSI_COLONNA_CHIAVE) }
                .setHeader("Prefissi colonna chiave").setAutoWidth(true)
            addComponentColumn { connessione ->
                HorizontalLayout(
                    Button("Modifica") { apriConnessione(connessione) }.apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL)
                    },
                    Button("Elimina") { confermaEliminaConnessione(connessione) }.apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR)
                    }
                ).apply { isPadding = false }
            }.setHeader("")
        }

        layout.add(nuova, connessioniGrid)
        layout.setFlexGrow(1.0, connessioniGrid)
        return layout
    }

    private fun elencoProprieta(connessione: SourceConnection, proprieta: String): String =
        ColumnProposal.lista(connessione.parametri, proprieta).joinToString(", ").ifEmpty { "—" }

    private fun apriConnessione(esistente: SourceConnection?) {
        ConnectionDialog(connectionOrchestrator, esistente) { ricarica() }.open()
    }

    private fun confermaEliminaConnessione(connessione: SourceConnection) {
        conferma(
            titolo = "Eliminare \"${connessione.nome}\"?",
            messaggio = "La connessione verrà rimossa. Se è usata da tabelle o dataset l'operazione viene rifiutata."
        ) {
            try {
                connectionOrchestrator.delete(connessione.id)
                Notification.show("Connessione eliminata")
            } catch (e: Exception) {
                Notification.show(e.message ?: "Errore", 6000, Notification.Position.MIDDLE)
            }
            ricarica()
        }
    }

    // ================= Comuni =================

    private fun ricarica() {
        try {
            tabelleGrid.setItems(tableImportService.elenco())
            connessioniGrid.setItems(connectionOrchestrator.findAll().sortedBy { it.nome.lowercase() })
        } catch (e: Exception) {
            Notification.show("Errore nel caricamento: ${e.message}", 6000, Notification.Position.MIDDLE)
        }
    }

    private fun conferma(
        titolo: String,
        messaggio: String,
        etichetta: String = "Elimina",
        pericolo: Boolean = true,
        azione: () -> Unit
    ) {
        val dialog = Dialog().apply {
            headerTitle = titolo
            width = "440px"
        }
        dialog.add(Span(messaggio))
        dialog.footer.add(
            Button("Annulla") { dialog.close() },
            Button(etichetta) {
                dialog.close()
                azione()
            }.apply { addThemeVariants(if (pericolo) ButtonVariant.LUMO_ERROR else ButtonVariant.LUMO_PRIMARY) }
        )
        dialog.open()
    }
}