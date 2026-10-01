package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.connector.ConnectionOrchestrator
import com.lightningbi.lightning_engine.connector.JdbcSourceConnector
import com.lightningbi.lightning_engine.model.ImportedTable
import com.lightningbi.lightning_engine.model.ModalitaSync
import com.lightningbi.lightning_engine.model.RuoloTabella
import com.lightningbi.lightning_engine.model.SourceConnection
import com.lightningbi.lightning_engine.service.AdminGuard
import com.lightningbi.lightning_engine.service.AuthService
import com.lightningbi.lightning_engine.service.ColumnProposal
import com.lightningbi.lightning_engine.service.TabellaImportataInfo
import com.lightningbi.lightning_engine.service.TableImportService
import com.lightningbi.lightning_engine.service.TableSyncService
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.dependency.Uses
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
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
 * La creazione e la modifica stanno in tre finestre: [ConnectionDialog],
 * [AddTableDialog] e [TableSyncDialog]. Il pulsante "Sincronizza" non c'è
 * ancora: dipende dall'ETL per tabella (Fase C).
 */
@Route("tabelle-importate")
@Uses(Icon::class)
class TabelleImportateView(
    private val tableImportService: TableImportService,
    private val tableSyncService: TableSyncService,
    private val connectionOrchestrator: ConnectionOrchestrator,
    private val adminGuard: AdminGuard,
    private val authService: AuthService
) : VerticalLayout(), BeforeEnterObserver {

    private val tabelleGrid = Grid<TabellaImportataInfo>()
    private val connessioniGrid = Grid<SourceConnection>()
    private var paginaCostruita = false

    private val formatoData = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

    override fun beforeEnter(event: BeforeEnterEvent) {
        if (!adminGuard.isAdmin()) {
            event.forwardTo(AssociativeExplorerView::class.java)
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
                        getUI().ifPresent { it.navigate(AssociativeExplorerView::class.java) }
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
                HorizontalLayout(
                    Button("Sincronizzazione") { apriSincronizzazione(info) }.apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL)
                    },
                    Button("Elimina") { confermaEliminaTabella(info) }.apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR)
                    }
                ).apply { isPadding = false }
            }.setHeader("")
        }

        layout.add(aggiungi, tabelleGrid)
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

    private fun apriSincronizzazione(info: TabellaImportataInfo) {
        TableSyncDialog(tableSyncService, tableImportService, info.tabella) { ricarica() }.open()
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

    private fun conferma(titolo: String, messaggio: String, azione: () -> Unit) {
        val dialog = Dialog().apply {
            headerTitle = titolo
            width = "440px"
        }
        dialog.add(Span(messaggio))
        dialog.footer.add(
            Button("Annulla") { dialog.close() },
            Button("Elimina") {
                dialog.close()
                azione()
            }.apply { addThemeVariants(ButtonVariant.LUMO_ERROR) }
        )
        dialog.open()
    }
}