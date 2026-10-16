package com.lightning.bi.ui

import com.lightning.bi.service.PivotEngine
import com.lightning.bi.service.RisultatoAnalisi
import org.apache.poi.ss.usermodel.BorderStyle
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFCellStyle
import org.apache.poi.xssf.usermodel.XSSFColor
import org.apache.poi.xssf.usermodel.XSSFFont
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream

/**
 * Costruisce il file .xlsx dalle risposte del menu guidato e dai dati della pivot.
 * Stile del foglio di Orietta: blocco intestazione, tabella con titoli su sfondo FAFAD2,
 * Variaz. %, riga Totali, righe in fondo (es. Budget).
 */
object EsportaExcel {

    private val MESI = listOf(
        "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
        "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre"
    )
    private const val VARIAZIONE = "Variaz.%"

    /**
     * @param valoriCampo per ogni campo scelto per l'intestazione, i valori selezionati già in testo
     * @param testoStampa testo dell'intestazione di stampa (righe separate da a capo), vuoto = nessuno
     */
    fun genera(
        r: RisposteExcel,
        esito: RisultatoAnalisi,
        valoriCampo: Map<String, String>,
        testoStampa: String = "",
        nomeFoglio: String = "Foglio1"
    ): ByteArray {
        val misura = r.misura ?: error("Nessuna misura scelta")
        val wb = XSSFWorkbook()
        val ws = wb.createSheet(nomeFoglio.replace(Regex("[\\\\/?*\\[\\]:]"), " ").take(31))

        // ---- dati dalla pivot ----
        val tutteLeChiavi = LinkedHashSet<String>()
        fun raccogli(nodi: List<PivotEngine.PivotNode>): Unit = nodi.forEach { n ->
            tutteLeChiavi.addAll(n.values.keys); raccogli(n.children)
        }
        raccogli(esito.gerarchia)
        val rango = esito.risultato.colonne.withIndex().associate { (i, c) -> c to i }
        val chiavi = tutteLeChiavi
            .filter { !it.endsWith("|$VARIAZIONE") && (it == misura || it.startsWith("$misura|")) }
            .sortedWith(compareBy({ rango[it.substringAfter("|", "")] ?: Int.MAX_VALUE }, { it }))
        val titoliColonna = chiavi.map { it.substringAfter("|", misura).replace("|", " ") }

        var nodi = esito.gerarchia
        val tuttiMesi = nodi.isNotEmpty() && nodi.all { it.label in MESI }
        if (tuttiMesi) nodi = nodi.sortedBy { MESI.indexOf(it.label) }
        val etichette: List<Pair<String, PivotEngine.PivotNode?>> =
            if (tuttiMesi && r.tuttiIValori) MESI.map { m -> m to nodi.firstOrNull { it.label == m } }
            else nodi.map { it.label to it }

        // ---- stili ----
        fun font(dim: Int, grassetto: Boolean, rosso: Boolean = false): XSSFFont = wb.createFont().apply {
            fontName = "Calibri"; fontHeightInPoints = dim.toShort(); bold = grassetto
            if (rosso) setColor(XSSFColor(byteArrayOf(0x8B.toByte(), 0, 0), null))
        }
        val creaFmt = wb.createDataFormat()
        fun stile(f: XSSFFont, orizz: HorizontalAlignment, fmt: String? = null, fondo: Boolean = false, bordo: Boolean = false): XSSFCellStyle =
            wb.createCellStyle().apply {
                setFont(f); alignment = orizz
                if (fmt != null) dataFormat = creaFmt.getFormat(fmt)
                if (fondo) {
                    setFillForegroundColor(XSSFColor(byteArrayOf(0xFA.toByte(), 0xFA.toByte(), 0xD2.toByte()), null))
                    fillPattern = FillPatternType.SOLID_FOREGROUND
                }
                if (bordo) {
                    borderTop = BorderStyle.THIN; borderBottom = BorderStyle.THIN
                    borderLeft = BorderStyle.THIN; borderRight = BorderStyle.THIN
                }
            }
        val NUM = "#,##0.00"
        val VAR = "#,##0.00_ ;[RED]\\-#,##0.00\\ "
        val EURO = "\"€ \"#,##0.00"
        val sEtichetta = stile(font(14, true), HorizontalAlignment.LEFT)
        val sValoreInt = stile(font(12, true), HorizontalAlignment.CENTER)
        val sTitoloTab = stile(font(14, true), HorizontalAlignment.LEFT, fondo = true, bordo = true)
        val sTitoloMis = stile(font(14, true), HorizontalAlignment.CENTER, fondo = true, bordo = true)
        val sTitoloCol = stile(font(11, true), HorizontalAlignment.CENTER, fondo = true, bordo = true)
        val sTesto = stile(font(11, false), HorizontalAlignment.LEFT, bordo = true)
        val sNum = stile(font(11, false), HorizontalAlignment.RIGHT, NUM, bordo = true)
        val sVar = stile(font(11, false), HorizontalAlignment.RIGHT, VAR, bordo = true)
        val sTotTesto = stile(font(11, true), HorizontalAlignment.LEFT, bordo = true)
        val sTotNum = stile(font(11, true, rosso = true), HorizontalAlignment.RIGHT, EURO, bordo = true)
        val sTotVar = stile(font(11, true), HorizontalAlignment.RIGHT, VAR, bordo = true)
        val sFondoTesto = stile(font(11, false), HorizontalAlignment.LEFT)
        val sFondoNum = stile(font(11, false), HorizontalAlignment.RIGHT, EURO)

        val nCol = chiavi.size.coerceAtLeast(1)
        val conVar = r.variazione && nCol >= 2
        val ultimaCol = nCol + (if (conVar) 1 else 0)   // indice dell'ultima colonna (A = 0)

        fun cella(riga: Int, col: Int) = (ws.getRow(riga) ?: ws.createRow(riga)).let { it.getCell(col) ?: it.createCell(col) }
        fun unisci(riga: Int, da: Int, a: Int) { if (a > da) ws.addMergedRegion(CellRangeAddress(riga, riga, da, a)) }

        // ---- intestazione ----
        var riga = 0
        val voci = r.campiIntestazione.mapNotNull { c -> valoriCampo[c]?.let { c to it } } +
                r.righeLibereIntestazione.lines().filter { it.isNotBlank() }.map { l ->
                    if (':' in l) l.substringBefore(':').trim() to l.substringAfter(':').trim() else l.trim() to ""
                }
        voci.forEach { (nome, valore) ->
            cella(riga, 0).apply { setCellValue(nome); cellStyle = sEtichetta }
            cella(riga, 1).apply { setCellValue(valore); cellStyle = sValoreInt }
            unisci(riga, 1, ultimaCol)
            ws.getRow(riga).heightInPoints = 21f
            riga += 2
        }
        if (voci.isNotEmpty()) riga += 1

        // ---- tabella: titoli ----
        val rTit = riga
        cella(rTit, 0).apply { setCellValue(r.titoloTabella.ifBlank { r.campoRighe ?: "" }); cellStyle = sTitoloTab }
        cella(rTit + 1, 0).cellStyle = sTitoloTab
        cella(rTit, 1).apply { setCellValue(misura); cellStyle = sTitoloMis }
        for (c in 2..ultimaCol) cella(rTit, c).cellStyle = sTitoloMis
        unisci(rTit, 1, ultimaCol)
        titoliColonna.forEachIndexed { i, t ->
            cella(rTit + 1, 1 + i).apply {
                val n = t.toDoubleOrNull()
                if (n != null && '.' !in t) setCellValue(n) else setCellValue(t)
                cellStyle = sTitoloCol
            }
        }
        if (chiavi.isEmpty()) cella(rTit + 1, 1).cellStyle = sTitoloCol
        if (conVar) cella(rTit + 1, ultimaCol).apply { setCellValue("Variaz. %"); cellStyle = sTitoloCol }
        ws.getRow(rTit).heightInPoints = 21f
        ws.getRow(rTit + 1).heightInPoints = 16.5f

        // ---- tabella: righe ----
        val primo = rTit + 2
        etichette.forEachIndexed { i, (testo, nodo) ->
            val rr = primo + i
            cella(rr, 0).apply { setCellValue(testo); cellStyle = sTesto }
            val lista = if (chiavi.isEmpty()) listOf(misura) else chiavi
            lista.forEachIndexed { j, k ->
                cella(rr, 1 + j).apply {
                    cellStyle = sNum
                    nodo?.values?.get(k)?.let { setCellValue(it.toDouble()) }
                }
            }
            if (conVar) {
                val a = colonnaExcel(ultimaCol - 2); val b = colonnaExcel(ultimaCol - 1); val n = rr + 1
                cella(rr, ultimaCol).apply {
                    cellFormula = "IF(OR($a$n=\"\",$a$n=0,$b$n=\"\"),\"\",$b$n*100/$a$n-100)"
                    cellStyle = sVar
                }
            }
            ws.getRow(rr).heightInPoints = 16.5f
        }
        var ultima = primo + etichette.size - 1

        // ---- totali ----
        if (r.totali && etichette.isNotEmpty()) {
            val rt = ultima + 2
            cella(rt, 0).apply { setCellValue("Totali ----------------->"); cellStyle = sTotTesto }
            for (j in 0 until nCol) {
                val col = colonnaExcel(1 + j)
                cella(rt, 1 + j).apply { cellFormula = "SUM($col${primo + 1}:$col${ultima + 1})"; cellStyle = sTotNum }
            }
            if (conVar) {
                val a = colonnaExcel(ultimaCol - 2); val b = colonnaExcel(ultimaCol - 1); val n = rt + 1
                cella(rt, ultimaCol).apply { cellFormula = "IF($a$n=0,\"\",$b$n*100/$a$n-100)"; cellStyle = sTotVar }
            }
            ws.getRow(rt).heightInPoints = 16.5f
            ultima = rt
        }

        // ---- righe in fondo ----
        val fondo = r.righeLibereFondo.lines().filter { it.isNotBlank() }
        if (fondo.isNotEmpty()) {
            var rf = ultima + 2
            fondo.forEach { l ->
                val nome = if (':' in l) l.substringBefore(':').trim() else l.trim()
                val resto = if (':' in l) l.substringAfter(':').trim() else ""
                cella(rf, 0).apply { setCellValue(nome); cellStyle = sFondoTesto }
                if (resto.isNotEmpty()) cella(rf, 1).apply {
                    val n = resto.replace(".", "").replace(",", ".").toDoubleOrNull()
                    if (n != null) { setCellValue(n); cellStyle = sFondoNum } else { setCellValue(resto); cellStyle = sFondoTesto }
                }
                ws.getRow(rf).heightInPoints = 16.5f
                rf++
            }
        }

        // ---- larghezze, stampa ----
        ws.setColumnWidth(0, 24 * 256)
        for (c in 1..ultimaCol) ws.setColumnWidth(c, (if (c == ultimaCol && conVar) 11 else 17) * 256)
        ws.fitToPage = true
        ws.printSetup.fitWidth = 1
        ws.printSetup.fitHeight = 0
        if (testoStampa.isNotBlank()) ws.header.center = testoStampa.lines().joinToString("\n")

        val out = ByteArrayOutputStream()
        wb.use { it.write(out) }
        return out.toByteArray()
    }

    private fun colonnaExcel(indice: Int): String {
        var n = indice; var s = ""
        do { s = ('A' + n % 26) + s; n = n / 26 - 1 } while (n >= 0)
        return s
    }
}