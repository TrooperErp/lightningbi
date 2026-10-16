package com.lightning.bi.ui

import com.vaadin.flow.component.UI
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.checkbox.CheckboxGroup
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.html.Anchor
import com.vaadin.flow.component.html.Div
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.textfield.TextArea
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.server.streams.DownloadHandler
import com.vaadin.flow.server.streams.DownloadResponse
import java.io.ByteArrayInputStream
import java.io.Serializable
import java.util.UUID

/** Risposte date nel menu guidato dell'export Excel. */
data class RisposteExcel(
    val campiIntestazione: Set<String>,
    val righeLibereIntestazione: String,
    val campoRighe: String?,
    val campoColonne: String?,
    val misura: String?,
    val titoloTabella: String,
    val variazione: Boolean,
    val totali: Boolean,
    val tuttiIValori: Boolean,
    val righeLibereFondo: String,
    val unFilePer: String?
) : Serializable

/** Risposte + firma della pivot con cui sono state date. */
data class RisposteSalvate(val firma: String, val risposte: RisposteExcel) : Serializable

/**
 * Menu guidato dell'export Excel. Legge la pivot del momento (Righe, Colonne, Misure, selezioni attive).
 * Le risposte si ricordano solo se la disposizione della pivot è identica; altrimenti si riparte da zero.
 * Stesso schema delle altre finestre dell'app: corpo "lt-finestra", pulsanti nel pié, primario a destra.
 */
class EsportaExcelDialog(
    vistaId: UUID,
    private val righe: List<String>,
    private val colonne: List<String>,
    private val misure: List<String>,
    private val selezioni: List<Pair<String, Int>>,
    private val nomeFile: String,
    private val genera: (RisposteExcel) -> ByteArray,
    private val salvaSuRete: ((RisposteExcel) -> String)? = null
) : Dialog() {

    private val chiave = "esporta-excel-$vistaId"
    private val firma = "R:${righe.joinToString("|")};C:${colonne.joinToString("|")};M:${misure.joinToString("|")}"

    private val campiIntestazione = CheckboxGroup<String>().apply {
        label = "Campi selezionati da mostrare in intestazione"
        setItems(selezioni.map { it.first })
        setItemLabelGenerator { nome -> "$nome (${selezioni.first { it.first == nome }.second} valori)" }
        isVisible = selezioni.isNotEmpty()
    }
    private val righeIntestazione = TextArea("Righe libere in intestazione").apply {
        setWidthFull(); minHeight = "70px"
        placeholder = "Una per riga, come  Zona: TN-BZ-VR"
    }

    private val campoRighe = ComboBox<String>("Campo sulle righe").apply { setWidthFull(); setItems(righe) }
    private val campoColonne = ComboBox<String>("Campo sulle colonne").apply { setWidthFull(); setItems(colonne) }
    private val misura = ComboBox<String>("Misura").apply { setWidthFull(); setItems(misure) }
    private val titolo = TextField("Titolo della tabella").apply { setWidthFull() }
    private val variazione = Checkbox("Colonna Variaz. %")
    private val totali = Checkbox("Riga Totali")
    private val tuttiIValori = Checkbox("Tutti i valori anche se vuoti (es. 12 mesi)")

    private val righeFondo = TextArea("Righe in fondo").apply {
        setWidthFull(); minHeight = "70px"
        placeholder = "Una per riga, come  Budget Anno 2026: 1800000"
    }
    private val unFilePer = ComboBox<String>("Un file per…").apply {
        setWidthFull()
        setItems((selezioni.map { it.first } + righe + colonne).distinct())
        isClearButtonVisible = true
        helperText = "Vuoto = un solo file"
    }
    private val esito = Span().apply {
        className = "lt-vuoto"
        style.set("white-space", "pre-line")
        isVisible = false
    }

    init {
        headerTitle = "Esporta in Excel"
        width = "520px"

        fun sezione(testo: String) = Span(testo).apply { className = "lt-modulo-sezione" }
        val riepilogo = Span(
            "Righe: ${righe.joinToString(", ").ifEmpty { "—" }} · " +
                    "Colonne: ${colonne.joinToString(", ").ifEmpty { "—" }} · " +
                    "Misure: ${misure.joinToString(", ").ifEmpty { "—" }}"
        ).apply { className = "lt-vuoto" }

        add(
            Div(
                riepilogo, esito,
                sezione("Intestazione"), campiIntestazione, righeIntestazione,
                sezione("Tabella"), campoRighe, campoColonne, misura, titolo, variazione, totali, tuttiIValori,
                sezione("Piè di tabella"), righeFondo,
                sezione("Più file"), unFilePer
            ).apply { className = "lt-finestra lt-modulo-campi" }
        )

        val chiudi = Button("Chiudi") { close() }
        val salvaRete = Button("Salva su rete") { salva() }.apply { isVisible = salvaSuRete != null }
        val scarica = Button("Scarica").apply { addThemeName("primary") }
        val ancora = Anchor(
            DownloadHandler.fromInputStream { _ ->
                val dati = generaOra()
                DownloadResponse(
                    ByteArrayInputStream(dati), nomeFile,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", dati.size.toLong()
                )
            },
            ""
        ).apply {
            element.setAttribute("download", true)
            add(scarica)
        }
        footer.add(chiudi, salvaRete, ancora)

        precompila()
    }

    private fun precompila() {
        campoRighe.value = righe.firstOrNull()
        campoColonne.value = colonne.firstOrNull()
        misura.value = misure.firstOrNull()
        variazione.value = true
        totali.value = true
        val salvate = UI.getCurrent()?.session?.getAttribute(chiave) as? RisposteSalvate ?: return
        if (salvate.firma != firma) {
            // La pivot è cambiata: le risposte non valgono più.
            UI.getCurrent().session.setAttribute(chiave, null)
            return
        }
        val r = salvate.risposte
        campiIntestazione.value = r.campiIntestazione.filter { c -> selezioni.any { it.first == c } }.toSet()
        righeIntestazione.value = r.righeLibereIntestazione
        campoRighe.value = r.campoRighe
        campoColonne.value = r.campoColonne
        misura.value = r.misura
        titolo.value = r.titoloTabella
        variazione.value = r.variazione
        totali.value = r.totali
        tuttiIValori.value = r.tuttiIValori
        righeFondo.value = r.righeLibereFondo
        unFilePer.value = r.unFilePer
    }

    private fun leggi() = RisposteExcel(
        campiIntestazione.value, righeIntestazione.value.orEmpty(), campoRighe.value, campoColonne.value,
        misura.value, titolo.value.orEmpty(), variazione.value, totali.value, tuttiIValori.value,
        righeFondo.value.orEmpty(), unFilePer.value
    )

    /** Le risposte si ricordano per la prossima volta, finché la pivot non cambia. */
    private fun memorizza(r: RisposteExcel) {
        UI.getCurrent()?.session?.setAttribute(chiave, RisposteSalvate(firma, r))
    }

    private fun generaOra(): ByteArray {
        val r = leggi()
        memorizza(r)
        return genera(r)
    }

    private fun salva() {
        val r = leggi()
        if (r.unFilePer == null) {
            Notification.show("Scegli prima il campo di \"Un file per…\"", 3000, Notification.Position.MIDDLE)
            return
        }
        memorizza(r)
        esito.isVisible = true
        esito.text = "Salvataggio in corso…"
        esito.text = try {
            salvaSuRete?.invoke(r) ?: "Non disponibile"
        } catch (e: Exception) {
            "Errore: ${e.message}"
        }
    }
}