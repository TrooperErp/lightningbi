package com.lightning.bi.qlik

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Un gruppo di campi di Qlik: ciclico (si cambia campo con un clic) o gerarchico (drill-down). */
data class GruppoQlik(val nome: String, val ciclico: Boolean, val campi: List<String>)

/** Una espressione di un oggetto Qlik, con l'etichetta mostrata in colonna. */
data class MisuraQlik(val formula: String, val etichetta: String)

/** Un grafico o una tabella di un foglio Qlik. */
data class AnalisiQlik(
    val id: String,
    val titolo: String,
    /** GRAPH_MODE_PIVOTTABLE, GRAPH_MODE_STRAIGHTTABLE, GRAPH_MODE_COMBO, GRAPH_MODE_PIE... */
    val modo: String,
    /** Dimensioni in righe (in una pivot le prime NumberOfLeftDimensions; altrimenti tutte). */
    val righe: List<String>,
    /** Dimensioni in colonna (solo nelle pivot). */
    val colonne: List<String>,
    val misure: List<MisuraQlik>
) {
    val pivot: Boolean get() = modo == "GRAPH_MODE_PIVOTTABLE"
    val tabella: Boolean get() = modo == "GRAPH_MODE_PIVOTTABLE" || modo == "GRAPH_MODE_STRAIGHTTABLE"
}

/** Un foglio Qlik: le sue analisi e i campi delle sue listbox (i filtri). */
data class FoglioQlik(val id: String, val nome: String, val analisi: List<AnalisiQlik>, val filtri: List<String>)

data class DocumentoQlik(val fogli: List<FoglioQlik>, val gruppi: Map<String, GruppoQlik>) {
    /** I campi veri di una dimensione: un gruppo si espande nei suoi campi, un campo resta com'è. */
    fun campiDi(dimensione: String): List<String> = gruppi[dimensione]?.campi ?: listOf(dimensione)
}

/**
 * Legge l'export XML di un documento QlikView (la cartella "-prj", anche zippata):
 * QlikViewProject.xml (fogli e loro oggetti), AllProperties.xml (gruppi),
 * un XML per oggetto (GraphProperties, ListBoxProperties). Kotlin puro, niente database.
 */
object QlikProgetto {

    /** Legge lo zip: i file possono stare in una sottocartella, conta solo il nome. */
    fun daZip(zip: InputStream): DocumentoQlik {
        val file = mutableMapOf<String, ByteArray>()
        ZipInputStream(zip).use { z ->
            var e = z.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name.endsWith(".xml", ignoreCase = true)) {
                    file[e.name.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".xml")] = z.readBytes()
                }
                e = z.nextEntry
            }
        }
        return leggi(file)
    }

    /** [file]: nome senza estensione (es. "SH43", "Agenti_Top") → contenuto XML. */
    fun leggi(file: Map<String, ByteArray>): DocumentoQlik {
        fun xml(nome: String): Element? = file[nome]?.let { analizza(it) }

        val gruppi = xml("AllProperties")?.let { radice ->
            radice.tutti("GroupProperties").mapNotNull { g ->
                val nome = g.testo("Name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                GruppoQlik(nome, g.testo("IsCyclic") == "true", g.tutti("FieldDefEx").map { it.testo("Name") }.filter { it.isNotBlank() })
            }.associateBy { it.nome }
        }.orEmpty()

        val progetto = xml("QlikViewProject") ?: error("Nello zip manca QlikViewProject.xml")
        val fogli = progetto.tutti("PrjSheetProperties").map { sh ->
            val id = sh.testo("SheetId").substringAfterLast('\\')
            val nome = xml(id)?.let { titolo(it) }?.ifBlank { null } ?: id
            val oggetti = sh.figli("ChildObjects").flatMap { it.figli() }
                .map { (it.testo("ObjectId").ifBlank { it.textContent.trim() }).substringAfterLast('\\') }
                .filter { it.isNotBlank() }
            val analisi = mutableListOf<AnalisiQlik>()
            val filtri = mutableListOf<String>()
            oggetti.forEach { oid ->
                val o = xml(oid) ?: return@forEach
                when (o.tagName) {
                    "GraphProperties" -> analisi += analisi(oid, o)
                    "ListBoxProperties" -> o.testo("Def/Name").takeIf { it.isNotBlank() }?.let { filtri += it }
                }
            }
            FoglioQlik(id, nome.trim(), analisi, filtri.distinct())
        }
        return DocumentoQlik(fogli, gruppi)
    }

    private fun analisi(id: String, o: Element): AnalisiQlik {
        val modo = o.testo("GraphLayout/GraphMode")
        val dimensioni = o.figli("Dimensions").flatMap { it.figli("ChartDimensionDataDef") }
            .map { it.testo("PseudoDef/Name") }.filter { it.isNotBlank() }
        val aSinistra = if (modo == "GRAPH_MODE_PIVOTTABLE")
            o.primo("NumberOfLeftDimensions")?.textContent?.trim()?.toIntOrNull() ?: dimensioni.size
        else dimensioni.size
        val dati = o.figli("_ExpressionDatas").flatMap { it.figli("ExpressionData") }
        val visuali = o.figli("_ExpressionVisuals").flatMap { it.figli("ExpressionVisualDef") }
        val misure = dati.mapIndexedNotNull { i, d ->
            if (d.testo("Enable") == "false") return@mapIndexedNotNull null
            val formula = d.testo("Definition/v").trim()
            if (formula.isEmpty()) return@mapIndexedNotNull null
            val etichetta = visuali.getOrNull(i)?.testo("Label/v")?.trim().orEmpty()
            MisuraQlik(formula.removePrefix("=").trim(), pulisciTitolo(etichetta).ifBlank { formula.take(60) })
        }
        return AnalisiQlik(
            id, pulisciTitolo(titolo(o)).ifBlank { id }, modo,
            dimensioni.take(aSinistra.coerceIn(0, dimensioni.size)),
            dimensioni.drop(aSinistra.coerceIn(0, dimensioni.size)),
            misure
        )
    }

    /** Titolo di un oggetto o di un foglio. */
    private fun titolo(e: Element): String =
        e.testo("GraphLayout/Frame/Name/v").ifBlank { e.testo("Name/v") }.ifBlank { e.primo("Name")?.let { n -> n.testo("v").ifBlank { n.textContent.trim() } }.orEmpty() }

    /** "='Fatturato periodico2 '" → "Fatturato periodico2". Le formule vere restano come sono. */
    private fun pulisciTitolo(t: String): String {
        val s = t.trim()
        val m = Regex("^=\\s*'([^']*)'\\s*$").find(s)
        return (m?.groupValues?.get(1) ?: s).trim()
    }

    // ---------------- XML ----------------

    private fun analizza(byte: ByteArray): Element {
        val f = DocumentBuilderFactory.newInstance().apply {
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            isNamespaceAware = false
        }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(byte)).documentElement
    }

    private fun Element.figli(nome: String? = null): List<Element> {
        val out = mutableListOf<Element>()
        val n = childNodes
        for (i in 0 until n.length) {
            val c = n.item(i)
            if (c is Element && (nome == null || c.tagName == nome)) out += c
        }
        return out
    }

    /** Testo del percorso di figli diretti "A/B/C"; vuoto se manca. */
    private fun Element.testo(percorso: String): String {
        var e: Element = this
        for (p in percorso.split('/')) e = e.figli(p).firstOrNull() ?: return ""
        return e.textContent?.trim().orEmpty()
    }

    private fun Element.tutti(nome: String): List<Element> {
        val n = getElementsByTagName(nome)
        return (0 until n.length).mapNotNull { n.item(it) as? Element }
    }

    private fun Element.primo(nome: String): Element? = getElementsByTagName(nome).item(0) as? Element
}