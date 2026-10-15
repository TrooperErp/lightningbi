package com.lightning.bi.service.setanalysis

import java.math.BigDecimal

/*
 * Set analysis in sintassi Qlik: il testo, la struttura neutra e il parser.
 *
 * Questo file non sa niente di ClickHouse né di dataset: trasforma il testo in una struttura che
 * ogni motore traduce a modo suo (oggi ClickHouse, domani il motore in memoria).
 *
 * LIVELLO 1 (supportato):
 *   {$<Campo -= {"TRASP*"}, [Campo con spazi] = {'A', 'B'}, Anno = {">=2025"}>}
 *   - identificatore: $ (selezioni correnti, anche omesso) oppure 1 (ignora tutte le selezioni);
 *   - più campi, separati da virgola;
 *   - operatori: =  -=  +=  *=
 *   - elementi: 'letterale' o numero (valore esatto), "ricerca" con jolly * e ?
 *     (senza distinguere maiuscole), "confronto" numerico: ">=2025", "<10", ">=1<=9";
 *   - insieme vuoto {} ammesso (con = vuol dire "nessun valore").
 *
 * LIVELLI SUCCESSIVI (non ancora): operatori tra insiemi ($ - 1), P() / E(),
 * ricerche per espressione {"=Sum(x)>0"}, stati alternativi, segnalibri. Il parser li
 * riconosce e li rifiuta con un messaggio chiaro, invece di interpretarli male.
 */

/** Quali selezioni sono il punto di partenza. */
enum class Identificatore(val testo: String) {
    /** $: le selezioni correnti dell'utente. */
    CORRENTI("$"),

    /** 1: nessuna selezione (tutto il dataset, salvo il section access). */
    TUTTO("1")
}

/** Come un modificatore cambia la selezione di un campo. */
enum class Operatore(val testo: String) {
    /** =  la selezione del campo diventa l'insieme indicato (quella dell'utente si ignora). */
    SOSTITUISCI("="),

    /** -= la selezione del campo, meno l'insieme indicato. */
    ESCLUDI("-="),

    /** += la selezione del campo, più l'insieme indicato. */
    AGGIUNGI("+="),

    /** *= la selezione del campo, intersecata con l'insieme indicato. */
    INTERSECA("*=")
}

/** Un elemento di un insieme di valori: {'A', "TRASP*", ">=2025", 12}. */
sealed interface Elemento {
    fun testo(): String

    /** 'A' oppure A: un valore preciso, confrontato con il testo del valore. */
    data class Letterale(val valore: String) : Elemento {
        override fun testo() = "'" + valore.replace("'", "''") + "'"
    }

    /** 12 oppure 12,5: un valore numerico preciso. */
    data class Numero(val valore: BigDecimal) : Elemento {
        override fun testo() = valore.toPlainString()
    }

    /** "TRASP*": ricerca per testo con jolly * (qualsiasi sequenza) e ? (un carattere), senza maiuscole. */
    data class Ricerca(val modello: String) : Elemento {
        override fun testo() = "\"" + modello + "\""
    }

    /** ">=2025<=2026": intervallo numerico; un estremo nullo = aperto. */
    data class Confronto(
        val minimo: BigDecimal?,
        val minimoIncluso: Boolean,
        val massimo: BigDecimal?,
        val massimoIncluso: Boolean
    ) : Elemento {
        override fun testo(): String = buildString {
            append('"')
            minimo?.let { append(if (minimoIncluso) ">=" else ">").append(it.toPlainString()) }
            massimo?.let { append(if (massimoIncluso) "<=" else "<").append(it.toPlainString()) }
            append('"')
        }
    }
}

/** Campo -= {…}: il nome come scritto (senza parentesi quadre), da risolvere sul dataset. */
data class Modificatore(val campo: String, val operatore: Operatore, val elementi: List<Elemento>) {
    fun testo(): String {
        val nome = if (campo.matches(NOME_SEMPLICE)) campo else "[${campo.replace("]", "]]")}]"
        return "$nome ${operatore.testo} {${elementi.joinToString(", ") { it.testo() }}}"
    }

    private companion object {
        val NOME_SEMPLICE = Regex("[A-Za-z_][A-Za-z0-9_]*")
    }
}

/** Una espressione di set completa: {$<…>}. */
data class EspressioneSet(val identificatore: Identificatore, val modificatori: List<Modificatore>) {
    /** Il testo canonico (quello che si salva e si mostra). */
    fun testo(): String {
        val id = identificatore.testo
        if (modificatori.isEmpty()) return "{$id}"
        return "{$id<${modificatori.joinToString(", ") { it.testo() }}>}"
    }

    /** I campi usati, per controllare che esistano nel dataset. */
    fun campi(): List<String> = modificatori.map { it.campo }.distinct()
}

/** Errore di sintassi, con la posizione (da 0) nel testo. */
class ErroreSetAnalysis(messaggio: String, val posizione: Int) :
    IllegalArgumentException("$messaggio (carattere ${posizione + 1})")

/**
 * Parser a discesa ricorsiva della sintassi descritta in testa al file. Senza stato tra una
 * chiamata e l'altra: si può usare da più thread.
 */
object SetAnalysisParser {

    fun analizza(testo: String): EspressioneSet = Lettore(testo).espressione()

    private class Lettore(private val s: String) {
        private var i = 0

        fun espressione(): EspressioneSet {
            spazi()
            atteso('{')
            spazi()
            val id = when {
                guarda('$') -> { i++; Identificatore.CORRENTI }
                guarda('1') -> { i++; Identificatore.TUTTO }
                else -> Identificatore.CORRENTI
            }
            spazi()
            if (guarda('+') || guarda('-') || guarda('*') || guarda('/')) {
                errore("Gli operatori tra insiemi interi (\$ - 1, …) non sono ancora supportati")
            }
            if (guarda(':') || s.startsWith("BM", i)) errore("Stati alternativi e segnalibri non sono ancora supportati")

            val modificatori = mutableListOf<Modificatore>()
            if (guarda('<')) {
                i++
                spazi()
                if (!guarda('>')) {
                    modificatori += modificatore()
                    spazi()
                    while (guarda(',')) {
                        i++
                        spazi()
                        modificatori += modificatore()
                        spazi()
                    }
                }
                atteso('>')
                spazi()
            }
            atteso('}')
            spazi()
            if (i < s.length) errore("Testo in più dopo la fine dell'espressione")

            val doppi = modificatori.groupBy { it.campo.lowercase() }.filterValues { it.size > 1 }.keys
            if (doppi.isNotEmpty()) errore("Lo stesso campo compare due volte: ${doppi.first()}", 0)
            return EspressioneSet(id, modificatori)
        }

        private fun modificatore(): Modificatore {
            val campo = nomeCampo()
            spazi()
            val operatore = when {
                s.startsWith("-=", i) -> { i += 2; Operatore.ESCLUDI }
                s.startsWith("+=", i) -> { i += 2; Operatore.AGGIUNGI }
                s.startsWith("*=", i) -> { i += 2; Operatore.INTERSECA }
                s.startsWith("/=", i) -> errore("L'operatore /= non è ancora supportato")
                guarda('=') -> { i++; Operatore.SOSTITUISCI }
                else -> errore("Atteso un operatore (=, -=, +=, *=) dopo il campo '$campo'")
            }
            spazi()
            if (s.startsWith("P(", i, ignoreCase = true) || s.startsWith("E(", i, ignoreCase = true)) {
                errore("P() ed E() non sono ancora supportati")
            }
            if (guarda('$')) errore("Le espansioni con \$ non sono supportate")
            return Modificatore(campo, operatore, insieme())
        }

        private fun nomeCampo(): String {
            if (guarda('[')) {
                i++
                val inizio = i
                val sb = StringBuilder()
                while (true) {
                    if (i >= s.length) errore("Parentesi quadra non chiusa", inizio - 1)
                    val c = s[i]
                    if (c == ']') {
                        if (i + 1 < s.length && s[i + 1] == ']') { sb.append(']'); i += 2; continue }
                        i++
                        break
                    }
                    sb.append(c); i++
                }
                val nome = sb.toString().trim()
                if (nome.isEmpty()) errore("Nome di campo vuoto", inizio)
                return nome
            }
            val inizio = i
            while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_' || s[i] == '.')) i++
            if (i == inizio) errore("Atteso il nome di un campo (con spazi: tra parentesi quadre)")
            return s.substring(inizio, i)
        }

        private fun insieme(): List<Elemento> {
            atteso('{')
            spazi()
            val elementi = mutableListOf<Elemento>()
            if (!guarda('}')) {
                elementi += elemento()
                spazi()
                while (guarda(',')) {
                    i++
                    spazi()
                    elementi += elemento()
                    spazi()
                }
            }
            atteso('}')
            return elementi
        }

        private fun elemento(): Elemento = when {
            guarda('\'') -> Elemento.Letterale(citato('\''))
            guarda('"') -> {
                val inizio = i
                ricercaOConfronto(citato('"'), inizio)
            }
            else -> {
                val inizio = i
                while (i < s.length && s[i] !in ",}" && !s[i].isWhitespace()) i++
                val parola = s.substring(inizio, i)
                if (parola.isEmpty()) errore("Atteso un valore")
                numero(parola)?.let { Elemento.Numero(it) } ?: Elemento.Letterale(parola)
            }
        }

        /** Testo tra apici: il delimitatore si raddoppia per scriverlo dentro ('' o ""). */
        private fun citato(delimitatore: Char): String {
            val inizio = i
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) errore("Virgolette non chiuse", inizio)
                val c = s[i]
                if (c == delimitatore) {
                    if (i + 1 < s.length && s[i + 1] == delimitatore) { sb.append(c); i += 2; continue }
                    i++
                    return sb.toString()
                }
                sb.append(c); i++
            }
        }

        /** "…" tra virgolette doppie: confronto numerico se inizia con < > ; espressione = rifiutata; altrimenti ricerca. */
        private fun ricercaOConfronto(testo: String, inizio: Int): Elemento {
            val t = testo.trim()
            if (t.startsWith("=")) errore("Le ricerche per espressione {\"=…\"} non sono ancora supportate", inizio)
            if (!t.startsWith("<") && !t.startsWith(">")) {
                if (t.isEmpty()) errore("Ricerca vuota", inizio)
                return Elemento.Ricerca(t)
            }
            var minimo: BigDecimal? = null
            var minimoIncluso = false
            var massimo: BigDecimal? = null
            var massimoIncluso = false
            val parti = Regex("(>=|<=|>|<)\\s*([^<>]+)").findAll(t).toList()
            if (parti.isEmpty() || parti.joinToString("") { it.value }.replace(" ", "") != t.replace(" ", "")) {
                errore("Confronto non valido: \"$testo\" (esempi: \">=2025\", \"<10\", \">=1<=9\")", inizio)
            }
            parti.forEach { p ->
                val valore = numero(p.groupValues[2].trim()) ?: errore("Nel confronto serve un numero: \"$testo\"", inizio)
                when (p.groupValues[1]) {
                    ">=" -> { minimo = valore; minimoIncluso = true }
                    ">" -> { minimo = valore; minimoIncluso = false }
                    "<=" -> { massimo = valore; massimoIncluso = true }
                    "<" -> { massimo = valore; massimoIncluso = false }
                }
            }
            return Elemento.Confronto(minimo, minimoIncluso, massimo, massimoIncluso)
        }

        /** Numero con il punto o con la virgola decimale (12, 12.5, 12,5). */
        private fun numero(testo: String): BigDecimal? =
            testo.trim().replace(',', '.').takeIf { it.matches(Regex("-?\\d+(\\.\\d+)?")) }?.let { BigDecimal(it) }

        private fun guarda(c: Char) = i < s.length && s[i] == c

        private fun atteso(c: Char) {
            if (!guarda(c)) errore(if (i >= s.length) "Atteso '$c' ma il testo è finito" else "Atteso '$c', trovato '${s[i]}'")
            i++
        }

        private fun spazi() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun errore(messaggio: String, posizione: Int = i): Nothing = throw ErroreSetAnalysis(messaggio, posizione)
    }

}

/**
 * Chiave per riconoscere un campo scritto in modi diversi: senza maiuscole, spazi e simboli.
 * "Codice Articolo", "[Codice Articolo]" e "Codice_Articolo" sono lo stesso campo.
 */
fun chiaveCampo(nome: String): String = nome.lowercase().filter { it.isLetterOrDigit() }