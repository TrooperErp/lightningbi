package com.lightning.bi.qlik

import com.lightning.piattaforma.ui.LightningLayout
import com.lightning.piattaforma.ui.PaginaLightning
import com.lightning.piattaforma.view.CurrentUserHolder
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.H3
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.upload.Upload
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.streams.UploadHandler
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream

/**
 * "Importa da Qlik": si carica lo zip dell'export XML del documento Qlik e si creano
 * dataset, analisi e filtri. Sotto, il rapporto foglio per foglio di quello che è stato saltato.
 */
@Route(value = "bi/importa-qlik", layout = LightningLayout::class)
class ImportaQlikView(private val importaQlik: ImportaQlik) : Div(), PaginaLightning {

    override val moduloId = "bi"
    override fun percorso() = listOf("Importa da Qlik")

    private val log = LoggerFactory.getLogger(ImportaQlikView::class.java)
    private val rapporto = Div().apply { className = "lt-modulo-campi" }

    init {
        className = "lt-pagina"
        val upload = Upload(UploadHandler.inMemory { metadata, dati ->
            ui.ifPresent { ui -> ui.access { importa(metadata.fileName(), dati) } }
        }).apply {
            setAcceptedFileTypes(".zip", "application/zip")
            maxFiles = 1
        }
        add(
            H3("Importa da Qlik"),
            Span("Carica lo zip dell'export XML del documento (cartella -prj). Ogni foglio Qlik con analisi diventa un dataset con le sue analisi; i dataset che esistono già non si toccano.")
                .apply { className = "lt-vuoto" },
            upload,
            rapporto
        )
    }

    private fun importa(nomeFile: String, dati: ByteArray) {
        rapporto.removeAll()
        val utente = CurrentUserHolder.get()
        if (utente == null) {
            Notification.show("Sessione scaduta: rientra", 4000, Notification.Position.MIDDLE)
            return
        }
        try {
            val documento = QlikProgetto.daZip(ByteArrayInputStream(dati))
            val esiti = importaQlik.importa(documento, utente.userId)
            rapporto.add(Span("$nomeFile: ${documento.fogli.size} fogli letti, ${esiti.count { it.datasetId != null }} dataset creati, ${esiti.sumOf { it.analisiCreate }} analisi.")
                .apply { className = "lt-modulo-sezione" })
            esiti.forEach { e ->
                rapporto.add(Span("${e.foglio}: " + if (e.datasetId != null) "dataset creato, ${e.analisiCreate} analisi" else "non creato")
                    .apply { className = "lt-modulo-sezione" })
                e.avvisi.forEach { rapporto.add(Span("· $it")) }
            }
        } catch (e: Exception) {
            log.warn("Import Qlik da {} fallito", nomeFile, e)
            rapporto.add(Span("Import fallito: ${e.message}"))
        }
    }
}