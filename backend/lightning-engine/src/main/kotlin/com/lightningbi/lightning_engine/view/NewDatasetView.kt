package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.ImportedTable
import com.lightningbi.lightning_engine.model.RuoloTabella
import com.lightningbi.lightning_engine.model.TipoAggregazione
import com.lightningbi.lightning_engine.service.AdminGuard
import com.lightningbi.lightning_engine.service.Associazione
import com.lightningbi.lightning_engine.service.AuthService
import com.lightningbi.lightning_engine.service.CampoEffettivo
import com.lightningbi.lightning_engine.service.Confidenza
import com.lightningbi.lightning_engine.service.DatasetBozza
import com.lightningbi.lightning_engine.service.DatasetNonValidoException
import com.lightningbi.lightning_engine.service.DatasetService
import com.lightningbi.lightning_engine.service.MetricaBozza
import com.lightningbi.lightning_engine.service.OccorrenzaBozza
import com.lightningbi.lightning_engine.service.RiferimentoCampo
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dependency.Uses
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.menubar.MenuBar
import com.vaadin.flow.component.menubar.MenuBarVariant
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.FlexComponent
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.tabs.Tab
import com.vaadin.flow.component.tabs.Tabs
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.BeforeEvent
import com.vaadin.flow.router.BeforeLeaveEvent
import com.vaadin.flow.router.BeforeLeaveObserver
import com.vaadin.flow.router.HasUrlParameter
import com.vaadin.flow.router.OptionalParameter
import com.vaadin.flow.router.Route
import java.util.UUID

/**
 * Pagina "Dataset" (solo admin): costruzione e modifica di un dataset nello
 * stile della vista Associations del Data manager di Qlik, in forma di
 * elenco (le bolle arrivano in fase F).
 *
 * - A sinistra le tabelle importate; si aggiungono al dataset anche più volte
 *   (la seconda occorrenza ha i campi qualificati con l'alias).
 * - Al centro le tabelle del dataset con i loro campi (rinomina, escludi,
 *   "associa a...", uso come dimensione) e le metriche.
 * - A destra le associazioni (campi con lo stesso nome) e i problemi del
 *   modello: loop (bloccano il salvataggio) e chiavi sintetiche (avvisi).
 *
 * Route "nuovo-dataset" per un dataset nuovo, "nuovo-dataset/{id}" per
 * modificarne uno esistente. Lo stato vive in una bozza in memoria
 * ([DatasetBozza]): solo "Salva" scrive sul database. Se si lascia la pagina
 * con modifiche non salvate si chiede conferma.
 */
@Route("nuovo-dataset")
@Uses(Icon::class)
class NewDatasetView(
    private val datasetService: DatasetService,
    private val adminGuard: AdminGuard,
    private val authService: AuthService
) : VerticalLayout(), HasUrlParameter<String>, BeforeEnterObserver, BeforeLeaveObserver {

    private var parametroArea: String? = null
    private var bozza: DatasetBozza = DatasetBozza()
    private var modificato = false
    private var occorrenzaSelezionata: UUID? = null
    private var aggiornando = false

    private val nomeField = TextField("Nome del dataset")
    private val tabelleGrid = Grid<ImportedTable>()
    private val occorrenzeGrid = Grid<OccorrenzaBozza>()
    private val campiGrid = Grid<CampoEffettivo>()
    private val metricheGrid = Grid<MetricaBozza>()
    private val associazioniGrid = Grid<Associazione>()
    private val problemiBox = VerticalLayout().apply { isPadding = false; isSpacing = false }

    // ================= Accesso e ciclo di vita =================

    override fun setParameter(event: BeforeEvent, @OptionalParameter parameter: String?) {
        parametroArea = parameter
    }

    override fun beforeEnter(event: BeforeEnterEvent) {
        if (!adminGuard.isAdmin()) {
            event.forwardTo(AssociativeExplorerView::class.java)
            return
        }
        bozza = DatasetBozza()
        modificato = false
        occorrenzaSelezionata = null

        val param = parametroArea
        if (!param.isNullOrBlank()) {
            val caricata = try {
                datasetService.carica(UUID.fromString(param))
            } catch (_: IllegalArgumentException) {
                null
            }
            if (caricata == null) {
                Notification.show("Dataset non trovato", 5000, Notification.Position.MIDDLE)
            } else {
                bozza = caricata
                occorrenzaSelezionata = caricata.occorrenze.firstOrNull()?.id
            }
        }
        buildPage()
    }

    override fun beforeLeave(event: BeforeLeaveEvent) {
        if (!modificato) return
        val azione = event.postpone()
        val dialog = Dialog().apply {
            headerTitle = "Modifiche non salvate"
            width = "420px"
        }
        dialog.add(Span("Hai modifiche non salvate al dataset. Se esci vengono perse."))
        dialog.footer.add(
            Button("Resta qui") { dialog.close() },
            Button("Esci senza salvare") {
                modificato = false
                dialog.close()
                azione.proceed()
            }.apply { addThemeVariants(ButtonVariant.LUMO_ERROR) }
        )
        dialog.open()
    }

    // ================= Pagina =================

    private fun buildPage() {
        removeAll()
        setSizeFull()
        isPadding = false

        // La stessa istanza può essere riusata se cambia solo il parametro dell'URL:
        // le colonne si rifanno da capo.
        tabelleGrid.removeAllColumns()
        occorrenzeGrid.removeAllColumns()
        campiGrid.removeAllColumns()
        metricheGrid.removeAllColumns()
        associazioniGrid.removeAllColumns()

        val menuGroups = listOf(
            LbiSidebarMenu.MenuGroup(
                label = "Analisi",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Torna alle analisi", icon = VaadinIcon.ARROW_LEFT) { tornaAiDataset() }
                ),
                icon = VaadinIcon.CHART
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Amministrazione",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Tabelle importate", icon = VaadinIcon.DATABASE) {
                        getUI().ifPresent { it.navigate(TabelleImportateView::class.java) }
                    },
                    LbiSidebarMenu.MenuEntry("Dataset", icon = VaadinIcon.TABLE) { },
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
        aggiorna()
    }

    private fun tornaAiDataset() {
        getUI().ifPresent { it.navigate(AssociativeExplorerView::class.java) }
    }

    private fun buildContent(): Component {
        nomeField.apply {
            setWidth("320px")
            value = bozza.nome
            addValueChangeListener {
                if (it.isFromClient) {
                    bozza = bozza.copy(nome = it.value.orEmpty())
                    modificato = true
                    aggiornaProblemi()
                }
            }
        }

        val intestazione = HorizontalLayout(
            Span(if (bozza.areaId == null) "Nuovo dataset" else "Modifica dataset").apply { className = "lbi-section-title" },
            nomeField,
            Button("Annulla") { tornaAiDataset() },
            Button("Salva", Icon(VaadinIcon.CHECK)) { salva() }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        ).apply {
            setWidthFull()
            defaultVerticalComponentAlignment = FlexComponent.Alignment.END
        }

        val principale = HorizontalLayout(buildColonnaTabelle(), buildColonnaModello(), buildColonnaControlli()).apply {
            setSizeFull()
            isPadding = false
        }

        return VerticalLayout(intestazione, principale).apply {
            setSizeFull()
            setFlexGrow(1.0, principale)
        }
    }

    // ---------- colonna sinistra: tabelle importate ----------

    private fun buildColonnaTabelle(): Component {
        tabelleGrid.apply {
            setSizeFull()
            addColumn { it.nomeLogico }.setHeader("Tabella").setAutoWidth(true)
            addColumn { if (it.ruolo == RuoloTabella.FATTI) "Fatti" else "Dimensione" }
                .setHeader("Ruolo").setAutoWidth(true)
            addItemDoubleClickListener { aggiungiTabella(it.item) }
        }
        val tabelle = datasetService.tabelleDisponibili()
        tabelleGrid.setItems(tabelle)

        val aggiungi = Button("Aggiungi al dataset", Icon(VaadinIcon.PLUS)) {
            val scelta = tabelleGrid.asSingleSelect().value
            if (scelta == null) {
                Notification.show("Scegli una tabella dall'elenco", 3000, Notification.Position.MIDDLE)
            } else {
                aggiungiTabella(scelta)
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        val vuoto = Span("Nessuna tabella importata: importale dalla pagina Tabelle importate.")
            .apply { isVisible = tabelle.isEmpty() }

        return VerticalLayout(
            Span("Tabelle importate").apply { className = "lbi-wizard-label" },
            Span("Doppio clic per aggiungere. La stessa tabella si può aggiungere più volte.")
                .apply { element.style.set("font-size", "var(--lumo-font-size-xs)") },
            vuoto, tabelleGrid, aggiungi
        ).apply {
            setWidth("22%")
            isPadding = false
            setFlexGrow(1.0, tabelleGrid)
        }
    }

    // ---------- colonna centrale: tabelle e campi, metriche ----------

    private fun buildColonnaModello(): Component {
        val tabCampi = Tab("Tabelle e campi")
        val tabMetriche = Tab("Metriche")
        val tabs = Tabs(tabCampi, tabMetriche)

        val pannelloCampi = buildPannelloCampi()
        val pannelloMetriche = buildPannelloMetriche().apply { isVisible = false }
        tabs.addSelectedChangeListener {
            val campi = tabs.selectedTab == tabCampi
            pannelloCampi.isVisible = campi
            pannelloMetriche.isVisible = !campi
        }

        return VerticalLayout(tabs, pannelloCampi, pannelloMetriche).apply {
            setWidth("48%")
            isPadding = false
            setFlexGrow(1.0, pannelloCampi)
            setFlexGrow(1.0, pannelloMetriche)
        }
    }

    private fun buildPannelloCampi(): VerticalLayout {
        occorrenzeGrid.apply {
            setWidthFull()
            height = "230px"
            addColumn { it.alias }.setHeader("Alias").setAutoWidth(true)
            addColumn { it.nomeLogico }.setHeader("Tabella").setAutoWidth(true)
            addColumn { if (it.ruolo == RuoloTabella.FATTI) "Fatti" else "Dimensione" }
                .setHeader("Ruolo").setAutoWidth(true)
            addColumn { o ->
                bozza.campi().count { it.occorrenzaId == o.id && !it.escluso }
            }.setHeader("Campi").setAutoWidth(true)
            addComponentColumn { o ->
                HorizontalLayout(
                    Button(Icon(VaadinIcon.EDIT)) { apriRinominaAlias(o) }.apply {
                        addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL)
                        element.setAttribute("title", "Rinomina alias")
                    },
                    Button(Icon(VaadinIcon.TRASH)) { confermaRimuoviTabella(o) }.apply {
                        addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR)
                        element.setAttribute("title", "Togli dal dataset")
                    }
                ).apply { isPadding = false; isSpacing = false }
            }.setHeader("")
            addSelectionListener { ev ->
                if (!aggiornando) {
                    occorrenzaSelezionata = ev.firstSelectedItem.orElse(null)?.id
                    aggiornaCampi()
                }
            }
        }

        campiGrid.apply {
            setSizeFull()
            addComponentColumn { campo ->
                Checkbox(RiferimentoCampo(campo.occorrenzaId, campo.colonna) in bozza.dimensioni).apply {
                    isEnabled = !campo.escluso
                    addValueChangeListener { ev ->
                        bozza = bozza.impostaDimensione(campo.occorrenzaId, campo.colonna, ev.value)
                        modificato = true
                    }
                    element.setAttribute("title", "Usa come dimensione (filtro)")
                }
            }.setHeader("Dim.").setAutoWidth(true).setFlexGrow(0)
            addComponentColumn { campo ->
                Span(campo.nomeCampo + if (campo.escluso) "  (escluso)" else "").apply {
                    if (campo.escluso) {
                        element.style.set("color", "var(--lumo-disabled-text-color)")
                        element.style.set("text-decoration", "line-through")
                    }
                }
            }.setHeader("Campo").setAutoWidth(true)
            addColumn { it.nomeOrigine }.setHeader("Colonna").setAutoWidth(true)
            addColumn { it.tipo }.setHeader("Tipo").setAutoWidth(true)
            addColumn { if (it.isChiave) "chiave" else "" }.setHeader("").setAutoWidth(true)
            addColumn { campo -> associatiCon(campo) }.setHeader("Associato con").setAutoWidth(true)
            addComponentColumn { campo ->
                MenuBar().apply {
                    addThemeVariants(MenuBarVariant.LUMO_TERTIARY_INLINE)
                    val radice = addItem(Icon(VaadinIcon.ELLIPSIS_DOTS_V))
                    radice.subMenu.addItem("Rinomina") { apriRinominaCampo(campo) }
                    radice.subMenu.addItem("Associa a...") { apriAssociaCampo(campo) }
                    if (campo.escluso) {
                        radice.subMenu.addItem("Ripristina") {
                            modifica { it.ripristinaCampo(campo.occorrenzaId, campo.colonna) }
                        }
                    } else {
                        radice.subMenu.addItem("Escludi dal dataset") {
                            modifica { it.escludiCampo(campo.occorrenzaId, campo.colonna) }
                        }
                    }
                }
            }.setHeader("")
        }

        return VerticalLayout(
            Span("Tabelle del dataset").apply { className = "lbi-wizard-label" },
            occorrenzeGrid,
            Span("Campi della tabella selezionata").apply { className = "lbi-wizard-label" },
            campiGrid
        ).apply {
            isPadding = false
            setSizeFull()
            setFlexGrow(1.0, campiGrid)
        }
    }

    private fun buildPannelloMetriche(): VerticalLayout {
        metricheGrid.apply {
            setSizeFull()
            addColumn { it.nome }.setHeader("Metrica").setAutoWidth(true)
            addColumn { etichettaAggregazione(it.tipo) }.setHeader("Aggregazione").setAutoWidth(true)
            addColumn { m -> bozza.occorrenze.firstOrNull { it.id == m.areaTabellaId }?.alias ?: "—" }
                .setHeader("Tabella").setAutoWidth(true)
            addColumn { m -> nomeCampoDi(m) }.setHeader("Campo").setAutoWidth(true)
            addComponentColumn { m ->
                HorizontalLayout(
                    Button(Icon(VaadinIcon.EDIT)) { apriMetrica(m) }.apply {
                        addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL)
                    },
                    Button(Icon(VaadinIcon.TRASH)) { modifica { it.rimuoviMetrica(m.id) } }.apply {
                        addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR)
                    }
                ).apply { isPadding = false; isSpacing = false }
            }.setHeader("")
        }
        val aggiungi = Button("Aggiungi metrica", Icon(VaadinIcon.PLUS)) { apriMetrica(null) }
            .apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        return VerticalLayout(aggiungi, metricheGrid).apply {
            isPadding = false
            setSizeFull()
            setFlexGrow(1.0, metricheGrid)
        }
    }

    // ---------- colonna destra: associazioni e problemi ----------

    private fun buildColonnaControlli(): Component {
        associazioniGrid.apply {
            setWidthFull()
            height = "260px"
            addColumn { it.nomeCampo }.setHeader("Campo").setAutoWidth(true)
            addColumn { a ->
                a.occorrenze.mapNotNull { id -> bozza.occorrenze.firstOrNull { it.id == id }?.alias }.joinToString(", ")
            }.setHeader("Tabelle").setAutoWidth(true)
            addComponentColumn { a ->
                Span(if (a.confidenza == Confidenza.ALTA) "Alta" else "Media").apply {
                    element.style.set(
                        "color",
                        if (a.confidenza == Confidenza.ALTA) "var(--lumo-success-text-color)" else "var(--lumo-secondary-text-color)"
                    )
                }
            }.setHeader("Confidenza").setAutoWidth(true)
            addColumn { if (it.perValore) "per valore" else "" }.setHeader("").setAutoWidth(true)
        }

        return VerticalLayout(
            Span("Associazioni").apply { className = "lbi-wizard-label" },
            Span("Campi con lo stesso nome in più tabelle. Confidenza alta: il campo è una chiave.")
                .apply { element.style.set("font-size", "var(--lumo-font-size-xs)") },
            associazioniGrid,
            Span("Problemi").apply { className = "lbi-wizard-label" },
            problemiBox
        ).apply {
            setWidth("30%")
            isPadding = false
        }
    }

    // ================= Aggiornamento della UI =================

    private fun aggiorna() {
        aggiornaOccorrenze()
        aggiornaCampi()
        metricheGrid.setItems(bozza.metriche)
        associazioniGrid.setItems(bozza.associazioni())
        aggiornaProblemi()
    }

    private fun aggiornaOccorrenze() {
        aggiornando = true
        try {
            occorrenzeGrid.setItems(bozza.occorrenze)
            if (occorrenzaSelezionata != null && bozza.occorrenze.none { it.id == occorrenzaSelezionata }) {
                occorrenzaSelezionata = bozza.occorrenze.firstOrNull()?.id
            }
            val sel = bozza.occorrenze.firstOrNull { it.id == occorrenzaSelezionata }
            if (sel != null) occorrenzeGrid.asSingleSelect().value = sel else occorrenzeGrid.asSingleSelect().clear()
        } finally {
            aggiornando = false
        }
    }

    private fun aggiornaCampi() {
        val sel = occorrenzaSelezionata
        campiGrid.setItems(bozza.campi().filter { it.occorrenzaId == sel })
    }

    private fun aggiornaProblemi() {
        problemiBox.removeAll()
        val esito = bozza.valida()
        if (esito.errori.isEmpty() && esito.avvisi.isEmpty()) {
            problemiBox.add(Span("Nessun problema").apply {
                element.style.set("color", "var(--lumo-success-text-color)")
            })
            return
        }
        esito.errori.forEach {
            problemiBox.add(Span("✖ ${it.messaggio}").apply {
                element.style.set("color", "var(--lumo-error-text-color)")
                element.style.set("margin-bottom", "var(--lumo-space-s)")
            })
        }
        esito.avvisi.forEach {
            problemiBox.add(Span("⚠ ${it.messaggio}").apply {
                element.style.set("color", "var(--lumo-warning-text-color)")
                element.style.set("margin-bottom", "var(--lumo-space-s)")
            })
        }
    }

    /** Applica una modifica alla bozza; i nomi non validi (Naming) sono segnalati senza perdere lo stato. */
    private fun modifica(operazione: (DatasetBozza) -> DatasetBozza) {
        try {
            bozza = operazione(bozza)
            modificato = true
            aggiorna()
        } catch (e: IllegalArgumentException) {
            Notification.show(e.message ?: "Operazione non valida", 6000, Notification.Position.MIDDLE)
        }
    }

    private fun aggiungiTabella(tabella: ImportedTable) {
        try {
            bozza = datasetService.aggiungiTabella(bozza, tabella.id)
            modificato = true
            occorrenzaSelezionata = bozza.occorrenze.lastOrNull()?.id
            aggiorna()
        } catch (e: IllegalArgumentException) {
            Notification.show(e.message ?: "Operazione non valida", 6000, Notification.Position.MIDDLE)
        }
    }

    private fun associatiCon(campo: CampoEffettivo): String {
        if (campo.escluso) return ""
        val altri = bozza.campi().filter {
            !it.escluso && it.nomeCampo == campo.nomeCampo && it.occorrenzaId != campo.occorrenzaId
        }
        return altri.map { it.alias }.distinct().joinToString(", ")
    }

    private fun nomeCampoDi(m: MetricaBozza): String {
        val colonna = m.colonna ?: return "—"
        return bozza.campi().firstOrNull { it.occorrenzaId == m.areaTabellaId && it.colonna == colonna }
            ?.nomeOrigine ?: colonna
    }

    private fun etichettaAggregazione(tipo: TipoAggregazione): String = when (tipo) {
        TipoAggregazione.SUM -> "Somma"
        TipoAggregazione.AVG -> "Media"
        TipoAggregazione.COUNT -> "Conteggio righe"
        TipoAggregazione.COUNT_DISTINCT -> "Conteggio distinti"
        TipoAggregazione.MIN -> "Minimo"
        TipoAggregazione.MAX -> "Massimo"
    }

    // ================= Finestre =================

    private fun apriRinominaAlias(o: OccorrenzaBozza) {
        val campo = TextField("Alias della tabella").apply { setWidthFull(); value = o.alias }
        finestra("Rinomina alias", campo, "Rinomina") {
            modifica { it.rinominaAlias(o.id, campo.value.orEmpty()) }
        }
    }

    private fun apriRinominaCampo(c: CampoEffettivo) {
        val campo = TextField("Nome del campo nel dataset").apply { setWidthFull(); value = c.nomeCampo }
        finestra("Rinomina campo", campo, "Rinomina") {
            modifica { it.rinominaCampo(c.occorrenzaId, c.colonna, campo.value.orEmpty()) }
        }
    }

    /**
     * "Associa a...": il campo scelto prende il nome del campo di un'altra
     * tabella. Se le colonne hanno nomi diversi l'associazione è per valore.
     */
    private fun apriAssociaCampo(c: CampoEffettivo) {
        val altri = bozza.campi().filter { !it.escluso && it.occorrenzaId != c.occorrenzaId }
        if (altri.isEmpty()) {
            Notification.show("Non ci sono campi di altre tabelle", 4000, Notification.Position.MIDDLE)
            return
        }
        val combo = ComboBox<CampoEffettivo>("Associa «${c.nomeCampo}» al campo").apply {
            setWidthFull()
            setItems(altri)
            setItemLabelGenerator { "${it.alias} · ${it.nomeOrigine}" }
        }
        finestra("Associa campo", combo, "Associa") {
            val scelto = combo.value
            if (scelto == null) {
                Notification.show("Scegli il campo", 3000, Notification.Position.MIDDLE)
            } else {
                modifica { it.associa(scelto.occorrenzaId, scelto.colonna, c.occorrenzaId, c.colonna) }
            }
        }
    }

    private fun confermaRimuoviTabella(o: OccorrenzaBozza) {
        val testo = Span("La tabella \"${o.alias}\" esce dal dataset, con le sue metriche e dimensioni. " +
                "La tabella importata non viene toccata.")
        finestra("Togliere \"${o.alias}\"?", testo, "Togli") {
            modifica { it.rimuoviTabella(o.id) }
        }
    }

    /** Finestra con un contenuto, Annulla e un pulsante di conferma. */
    private fun finestra(titolo: String, contenuto: Component, conferma: String, azione: () -> Unit) {
        val dialog = Dialog().apply {
            headerTitle = titolo
            width = "460px"
        }
        dialog.add(VerticalLayout(contenuto).apply { isPadding = false })
        dialog.footer.add(
            Button("Annulla") { dialog.close() },
            Button(conferma) {
                dialog.close()
                azione()
            }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }
        )
        dialog.open()
    }

    /** Aggiunge (esistente = null) o modifica una metrica. */
    private fun apriMetrica(esistente: MetricaBozza?) {
        val fatti = bozza.occorrenze.filter { it.ruolo == RuoloTabella.FATTI }
        if (fatti.isEmpty()) {
            Notification.show("Aggiungi prima una tabella Fatti", 4000, Notification.Position.MIDDLE)
            return
        }
        val tabellaCombo = ComboBox<OccorrenzaBozza>("Tabella Fatti").apply {
            setWidthFull()
            setItems(fatti)
            setItemLabelGenerator { it.alias }
            value = fatti.firstOrNull { it.id == esistente?.areaTabellaId } ?: fatti.first()
        }
        val tipoCombo = ComboBox<TipoAggregazione>("Aggregazione").apply {
            setWidthFull()
            setItems(TipoAggregazione.entries)
            setItemLabelGenerator { etichettaAggregazione(it) }
            value = esistente?.tipo ?: TipoAggregazione.SUM
        }
        val campoCombo = ComboBox<CampoEffettivo>("Campo").apply {
            setWidthFull()
            setItemLabelGenerator { it.nomeOrigine }
        }
        val nomeMetrica = TextField("Nome della metrica").apply {
            setWidthFull()
            value = esistente?.nome.orEmpty()
        }

        fun riempiCampi() {
            val occ = tabellaCombo.value
            val tipo = tipoCombo.value
            val soloNumerici = tipo in setOf(
                TipoAggregazione.SUM, TipoAggregazione.AVG, TipoAggregazione.MIN, TipoAggregazione.MAX
            )
            val candidati = bozza.campi().filter {
                occ != null && it.occorrenzaId == occ.id && !it.escluso && (!soloNumerici || it.numerico)
            }
            campoCombo.setItems(candidati)
            campoCombo.isEnabled = tipo != TipoAggregazione.COUNT
            campoCombo.value = candidati.firstOrNull { it.colonna == esistente?.colonna }
        }
        tabellaCombo.addValueChangeListener { riempiCampi() }
        tipoCombo.addValueChangeListener { riempiCampi() }
        riempiCampi()

        val contenuto = VerticalLayout(tabellaCombo, tipoCombo, campoCombo, nomeMetrica).apply { isPadding = false }
        finestra(if (esistente == null) "Aggiungi metrica" else "Modifica metrica", contenuto, "Salva") {
            val occ = tabellaCombo.value
            val tipo = tipoCombo.value
            val campo = campoCombo.value
            if (occ == null || tipo == null) return@finestra
            if (tipo != TipoAggregazione.COUNT && campo == null) {
                Notification.show("Scegli il campo", 3000, Notification.Position.MIDDLE)
                return@finestra
            }
            val nome = nomeMetrica.value.orEmpty().trim().ifEmpty {
                if (tipo == TipoAggregazione.COUNT) "Numero righe"
                else "${etichettaAggregazione(tipo)} ${campo?.nomeOrigine.orEmpty()}"
            }
            val nuova = MetricaBozza(
                id = esistente?.id ?: UUID.randomUUID(),
                nome = nome,
                areaTabellaId = occ.id,
                colonna = if (tipo == TipoAggregazione.COUNT) null else campo?.colonna,
                tipo = tipo
            )
            modifica { b -> (if (esistente != null) b.rimuoviMetrica(esistente.id) else b).aggiungiMetrica(nuova) }
        }
    }

    // ================= Salvataggio =================

    private fun salva() {
        try {
            adminGuard.requireAdmin()
        } catch (e: SecurityException) {
            Notification.show(e.message ?: "Operazione non consentita")
            return
        }
        try {
            val id = datasetService.salva(bozza)
            modificato = false
            Notification.show(
                "Dataset \"${bozza.nome.trim()}\" salvato. Premi \"Sincronizza\" per caricare i dati.",
                6000, Notification.Position.MIDDLE
            )
            getUI().ifPresent { it.navigate(AssociativeExplorerView::class.java, id.toString()) }
        } catch (e: DatasetNonValidoException) {
            aggiornaProblemi()
            Notification.show(
                "Il dataset ha ${e.esito.errori.size} problemi da correggere (vedi a destra)",
                7000, Notification.Position.MIDDLE
            )
        } catch (e: Exception) {
            Notification.show("Errore nel salvataggio: ${e.message}", 10000, Notification.Position.MIDDLE)
        }
    }
}