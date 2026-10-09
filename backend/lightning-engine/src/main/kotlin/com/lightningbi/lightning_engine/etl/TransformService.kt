// FILE: src/main/kotlin/com/lightningbi/lightning_engine/etl/TransformService.kt
package com.lightningbi.lightning_engine.etl

import com.lightningbi.lightning_engine.model.ImportedColumn
import com.lightningbi.lightning_engine.repository.CampoTestoRepository
import com.lightningbi.lightning_engine.service.CalendarioService
import com.lightningbi.lightning_engine.service.ColumnProposal
import com.lightningbi.lightning_engine.service.ComponenteCalendario
import com.lightningbi.lightning_engine.service.Naming
import com.lightningbi.lightning_engine.service.SymbolLookupService
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ForkJoinPool
import java.util.stream.IntStream

/**
 * Un blocco di righe pronto per ClickHouse, a COLONNE: per ogni colonna fisica
 * un array con un valore per riga (stesso indice in tutte le colonne).
 *
 * - [ids]: colonne id (UInt32), nome fisico -> id per riga;
 * - [numeri]: copie numeriche, nome `<colonna>__n` -> valore per riga (null = mancante).
 */
class BloccoColonne(
    val righe: Int,
    val ids: Map<String, LongArray>,
    val numeri: Map<String, Array<BigDecimal?>>,
    val scartate: Int
) {
    companion object {
        val VUOTO = BloccoColonne(0, emptyMap(), emptyMap(), 0)
    }
}

/**
 * Regole di una sorgente applicate durante la trasformazione (SorgenteTabella).
 *
 * @param campoDitta nome fisico del campo azienda (lbi.azienda.campo)
 * @param dittaForzata valore da scrivere nel campo azienda al posto di quello letto; null = si legge
 * @param prefissoChiavi testo davanti ai valori delle colonne chiave; null = nessuno
 * @param prefissiTecnici prefissi delle colonne chiave della connessione (es. "_KEY"):
 *   oltre alle colonne marcate chiave, prendono il prefisso anche queste
 */
data class RegoleSorgente(
    val campoDitta: String? = null,
    val dittaForzata: Int? = null,
    val prefissoChiavi: String? = null,
    val prefissiTecnici: List<String> = emptyList()
) {
    companion object {
        val NESSUNA = RegoleSorgente()
    }
}

@Service
class TransformService(
    private val symbolLookupService: SymbolLookupService,
    private val calendarioService: CalendarioService,
    private val campoTestoRepository: CampoTestoRepository,
    /** Thread per la trasformazione (colonne in parallelo). */
    @Value("\${lbi.sync.thread-trasformazione:8}") threadTrasformazione: Int
) {
    private val log = LoggerFactory.getLogger(TransformService::class.java)

    /** Pool dedicato: non ruba i thread al pool comune né alle richieste delle pagine. */
    private val pool = ForkJoinPool(threadTrasformazione.coerceAtLeast(1))

    @PreDestroy
    fun chiudi() {
        pool.shutdown()
    }

    companion object {
        const val NULL_VALUE_ID = 0L
        const val NULL_VALUE_LABEL = "(non definito)"

        // Decimal(38, 6): vedi SymbolTableService.createImportedTable.
        private const val SCALA_NUMERICA = 6
        private const val PRECISIONE_NUMERICA = 38

        /** Segnali negli array grezzi: riga da scartare (chiave obbligatoria mancante, id assente). */
        private const val SCARTA_CHIAVE = -1L
        private const val SCARTA_ID = -2L
    }

    /** Una colonna da trasformare, con i nomi già calcolati una volta sola. */
    private class Campo(
        /** Etichetta come la restituisce il connettore (minuscola). */
        val origine: String,
        /** Nome fisico su ClickHouse (Naming.column): è anche il nome della symbol table. */
        val fisica: String,
        val isChiave: Boolean,
        /** Ha una copia numerica `<fisica>__n`. */
        val numerica: Boolean,
        /** Campo derivato da una data: etichetta (minuscola) della colonna data di origine. */
        val derivataDa: String? = null,
        /** Campo derivato da una data: componente del calendario. */
        val componente: ComponenteCalendario? = null
    )

    /** Una colonna trasformata su tutte le righe del blocco, prima di togliere le scartate. */
    private class ColonnaGrezza(
        /** Id per riga, oppure SCARTA_CHIAVE / SCARTA_ID. */
        val ids: LongArray,
        /** Copia numerica per riga, se la colonna è numerica. */
        val numeri: Array<BigDecimal?>?,
        val fuoriScala: Long,
        /** Un valore senza id, per il log (null se non ce ne sono). */
        val esempioSenzaId: String?
    )

    /**
     * Trasforma le righe estratte in un blocco a colonne pronto per ClickHouse.
     * La forma di ogni colonna non dipende dal dataset.
     *
     * - Ogni colonna diventa un id (UInt32) della symbol table del suo CAMPO,
     *   condivisa da tutte le tabelle con una colonna omonima (è ciò che rende
     *   possibile l'associazione per nome).
     * - Le colonne di tipo numerico (non chiave) hanno anche la copia
     *   `<colonna>__n` (Naming.numericColumn), Decimal con null per il valore mancante.
     * - Un valore assente diventa [NULL_VALUE_ID]. Fa eccezione la chiave quando
     *   [chiaveObbligatoria]: una riga di Dimensione senza chiave viene scartata.
     * - I valori si NORMALIZZANO prima della symbol table: spazi tolti e numeri
     *   in forma canonica ("123", "123.00" e 123 sono lo stesso valore).
     * - I campi DERIVATI da una data (calendario) si calcolano dalla colonna data
     *   di origine, con valori duali (testo e numero). Una data assente dà id 0.
     * - Le date si normalizzano in un testo canonico ordinabile (2026-10-01).
     *
     * IN PARALLELO per colonna, su un pool dedicato. L'ordine delle righe in
     * uscita è quello in entrata, senza le scartate.
     *
     * Le righe arrivano come le restituisce il connettore (etichette colonna in minuscolo).
     */
    fun transform(
        rows: List<Map<String, Any?>>,
        colonne: List<ImportedColumn>,
        chiaveObbligatoria: Boolean,
        regole: RegoleSorgente = RegoleSorgente.NESSUNA
    ): BloccoColonne {
        if (rows.isEmpty()) return BloccoColonne.VUOTO

        val campi = colonne.map { c ->
            Campo(
                origine = c.nome.lowercase(),
                fisica = Naming.column(c.nomeCampo),
                isChiave = c.isChiave,
                numerica = !c.derivata && !c.isChiave && ColumnProposal.isNumerico(c.tipo),
                derivataDa = c.derivataDa?.lowercase(),
                componente = c.componente?.let { codice ->
                    ComponenteCalendario.daCodice(codice)
                        ?: throw IllegalStateException("Componente di calendario sconosciuto '$codice' (colonna '${c.nome}')")
                }
            )
        }

        val doppie = campi.groupingBy { it.fisica }.eachCount().filterValues { it > 1 }.keys
        require(doppie.isEmpty()) {
            "Più colonne diventano lo stesso nome fisico: ${doppie.joinToString(", ")}"
        }

        val disponibili = rows.first().keys
        val mancanti = campi.filter { it.componente == null }.map { it.origine }.filter { it !in disponibili }
        require(mancanti.isEmpty()) {
            "La sorgente non espone le colonne attese: ${mancanti.joinToString(", ")}. " +
                    "Colonne trovate: ${disponibili.joinToString(", ")}"
        }

        val n = rows.size

        // ---- 1. colonne in parallelo: normalizzazione, symbol table, array grezzi ----
        val campiTesto = campoTestoRepository.tutti()
        val inizioColonne = System.nanoTime()
        val grezze: List<ColonnaGrezza> = inParallelo(campi.size) { j ->
            colonnaGrezza(campi[j], rows, campi[j].fisica in campiTesto, chiaveObbligatoria, regole)
        }
        val msColonne = (System.nanoTime() - inizioColonne) / 1_000_000

        // ---- 2. righe valide ----
        val inizioRighe = System.nanoTime()
        val valida = BooleanArray(n) { true }
        grezze.forEachIndexed { j, g ->
            g.esempioSenzaId?.let {
                log.warn("Valore '{}' senza id nel campo '{}': righe scartate", it, campi[j].fisica)
            }
            val a = g.ids
            for (i in 0 until n) if (a[i] < 0) valida[i] = false
        }
        var nValide = 0
        for (i in 0 until n) if (valida[i]) nValide++
        val indici = IntArray(nValide)
        var k = 0
        for (i in 0 until n) if (valida[i]) indici[k++] = i

        // ---- 3. compattazione in parallelo: solo le righe valide ----
        val tutte = nValide == n
        val compatte: List<Pair<LongArray, Array<BigDecimal?>?>> = inParallelo(campi.size) { j ->
            val g = grezze[j]
            if (tutte) g.ids to g.numeri
            else LongArray(nValide) { g.ids[indici[it]] } to g.numeri?.let { num -> Array(nValide) { num[indici[it]] } }
        }
        val ids = LinkedHashMap<String, LongArray>(campi.size)
        val numeri = LinkedHashMap<String, Array<BigDecimal?>>()
        campi.forEachIndexed { j, campo ->
            ids[campo.fisica] = compatte[j].first
            compatte[j].second?.let { numeri[Naming.numericColumn(campo.fisica)] = it }
        }
        val msRighe = (System.nanoTime() - inizioRighe) / 1_000_000

        log.info("[sync] colonne (normalizzazione e simboli): {} ms su {} colonne; righe: {} ms", msColonne, campi.size, msRighe)
        val fuoriScala = grezze.sumOf { it.fuoriScala }
        if (fuoriScala > 0) {
            log.warn(
                "{} valori di colonne numeriche non rappresentabili come Decimal({},{}): copia numerica nulla, l'id resta",
                fuoriScala, PRECISIONE_NUMERICA, SCALA_NUMERICA
            )
        }
        return BloccoColonne(nValide, ids, numeri, n - nValide)
    }

    /** Normalizza la colonna, ottiene gli id (creandoli se mancano) e costruisce gli array per riga. */
    private fun colonnaGrezza(
        campo: Campo,
        rows: List<Map<String, Any?>>,
        testo: Boolean,
        chiaveObbligatoria: Boolean,
        regole: RegoleSorgente
    ): ColonnaGrezza {
        val componente = campo.componente
        val numeriDerivati = HashMap<String, BigDecimal>()
        val testi: Array<String?> = if (componente != null) {
            val padre = campo.derivataDa ?: error("Campo derivato senza colonna di origine: ${campo.fisica}")
            Array(rows.size) { i ->
                val data = calendarioService.daValore(rows[i][padre])
                if (data == null) null
                else {
                    val derivato = calendarioService.derivato(componente, data)
                    numeriDerivati[derivato.testo] = derivato.numero
                    derivato.testo
                }
            }
        } else {
            val origine = campo.origine
            Array(rows.size) { i -> normalizza(rows[i][origine]) }
        }

        // Regole della sorgente: ditta forzata sul campo azienda, prefisso sulle colonne chiave.
        val forzata = regole.dittaForzata?.takeIf { campo.componente == null && campo.fisica == regole.campoDitta }
        if (forzata != null) {
            val v = forzata.toString()
            for (i in testi.indices) testi[i] = v
        }
        val prefisso = regole.prefissoChiavi
        if (prefisso != null && campo.componente == null &&
            (campo.isChiave || regole.prefissiTecnici.any { campo.origine.startsWith(it.lowercase()) })
        ) {
            for (i in testi.indices) testi[i]?.let { testi[i] = prefisso + it }
        }

        val valori = testi.asSequence().filterNotNull().toSet()
        val mappa = symbolLookupService.getOrCreateIds(campo.fisica, valori, numeriDerivati, testo)

        var esempioSenzaId: String? = null
        val ids = LongArray(rows.size) { i ->
            val t = testi[i]
            when {
                t == null -> if (campo.isChiave && chiaveObbligatoria) SCARTA_CHIAVE else NULL_VALUE_ID
                else -> mappa[t] ?: run {
                    // Il lookup avrebbe dovuto creare l'id: problema della symbol table, non del dato.
                    if (esempioSenzaId == null) esempioSenzaId = t
                    SCARTA_ID
                }
            }
        }

        var fuoriScala = 0L
        val numeri: Array<BigDecimal?>? = if (!campo.numerica) null else {
            val origine = campo.origine
            Array(rows.size) { i ->
                val numero = if (forzata != null) toNumero(forzata) else toNumero(rows[i][origine])
                if (numero == null && testi[i] != null) fuoriScala++
                numero
            }
        }
        return ColonnaGrezza(ids, numeri, fuoriScala, esempioSenzaId)
    }

    /** Esegue f(0..n-1) sul pool dedicato e restituisce i risultati nell'ordine. Le eccezioni escono come sono. */
    private fun <T> inParallelo(n: Int, f: (Int) -> T): List<T> {
        if (n == 0) return emptyList()
        if (n == 1) return listOf(f(0))
        try {
            return pool.submit(Callable {
                IntStream.range(0, n).parallel().mapToObj { f(it) }.toList()
            }).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    /**
     * Forma canonica di un valore, o null se è assente o vuoto. I numeri
     * perdono gli zeri finali e la notazione scientifica; i testi perdono gli
     * spazi ai bordi.
     */
    fun normalizza(valore: Any?): String? {
        val testo = when (valore) {
            null -> return null
            is BigDecimal -> valore.stripTrailingZeros().toPlainString()
            is Double -> {
                if (valore.isNaN() || valore.isInfinite()) return null
                BigDecimal.valueOf(valore).stripTrailingZeros().toPlainString()
            }
            is Float -> {
                if (valore.isNaN() || valore.isInfinite()) return null
                BigDecimal(valore.toString()).stripTrailingZeros().toPlainString()
            }
            // Date: testo canonico ordinabile (2026-10-01), non il toString del driver.
            is java.util.Date, is java.time.LocalDate, is java.time.LocalDateTime,
            is java.time.OffsetDateTime, is java.time.Instant ->
                calendarioService.daValore(valore)?.let { calendarioService.testoCanonico(it) } ?: valore.toString()
            else -> valore.toString()
        }.trim()
        return testo.ifEmpty { null }
    }

    /**
     * Il valore come numero per la copia numerica: scala 6, al massimo 38
     * cifre. Null se manca, non è un numero o non ci sta (ClickHouse
     * rifiuterebbe l'inserimento e fermerebbe tutto il caricamento).
     */
    private fun toNumero(valore: Any?): BigDecimal? {
        val numero: BigDecimal = when (valore) {
            null -> return null
            is BigDecimal -> valore
            is Double -> {
                if (valore.isNaN() || valore.isInfinite()) return null
                BigDecimal.valueOf(valore)
            }
            is Float -> {
                if (valore.isNaN() || valore.isInfinite()) return null
                BigDecimal(valore.toString())
            }
            is Number -> BigDecimal(valore.toString())
            else -> valore.toString().trim().toBigDecimalOrNull() ?: return null
        }
        val scalato = numero.setScale(SCALA_NUMERICA, RoundingMode.HALF_UP)
        return if (scalato.precision() <= PRECISIONE_NUMERICA) scalato else null
    }
}