package com.lightningbi.lightning_engine.etl

import com.lightningbi.lightning_engine.model.ImportedColumn
import com.lightningbi.lightning_engine.service.ColumnProposal
import com.lightningbi.lightning_engine.service.Naming
import com.lightningbi.lightning_engine.service.SymbolLookupService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class TransformService(
    private val symbolLookupService: SymbolLookupService
) {
    private val log = LoggerFactory.getLogger(TransformService::class.java)

    companion object {
        const val NULL_VALUE_ID = 0L
        const val NULL_VALUE_LABEL = "(non definito)"

        // Decimal(38, 6): vedi SymbolTableService.createImportedTable.
        private const val SCALA_NUMERICA = 6
        private const val PRECISIONE_NUMERICA = 38
    }

    /** Una colonna da trasformare, con i nomi già calcolati una volta sola. */
    private class Campo(
        /** Etichetta come la restituisce il connettore (minuscola). */
        val origine: String,
        /** Nome fisico su ClickHouse (Naming.column): è anche il nome della symbol table. */
        val fisica: String,
        val isChiave: Boolean,
        /** Ha una copia numerica `<fisica>__n`. */
        val numerica: Boolean
    )

    /**
     * Trasforma le righe estratte in righe pronte per ClickHouse. La forma di
     * ogni colonna non dipende dal dataset: lo stesso schema serve a tutti i
     * dataset che usano la tabella.
     *
     * - Ogni colonna diventa un id (UInt32) della symbol table del suo CAMPO,
     *   che si chiama come la colonna fisica ed è condivisa da tutte le
     *   tabelle con una colonna omonima (è ciò che fa combaciare gli id su
     *   Fatti e Dimensione, e rende possibile l'associazione per nome).
     * - Le colonne di tipo numerico (non chiave) hanno anche la copia
     *   `<colonna>__n` (Naming.numericColumn), Decimal con null per il valore
     *   mancante.
     * - Un valore assente diventa [NULL_VALUE_ID] (le colonne sono UInt32 non
     *   nullable). Fa eccezione la chiave quando [chiaveObbligatoria]: una riga
     *   di Dimensione senza chiave non si collegherebbe a nulla e viene
     *   scartata. Sui Fatti una chiave assente resta (id 0 = non definito).
     * - I valori si NORMALIZZANO prima della symbol table: spazi tolti (le
     *   colonne CHAR di SQL Server arrivano con il riempimento) e numeri in
     *   forma canonica ("123", "123.00" e 123 sono lo stesso valore). Senza,
     *   una chiave int sui Fatti e numeric su una Dimensione darebbe id
     *   diversi e il collegamento resterebbe vuoto senza errori.
     *
     * Le righe arrivano come le restituisce il connettore: le chiavi sono le
     * etichette colonna in minuscolo. Le righe in uscita hanno i nomi FISICI.
     * Le colonne che non sono tra quelle importate non vengono lette.
     *
     * @return (righe valide, righe scartate)
     */
    fun transform(
        rows: List<Map<String, Any?>>,
        colonne: List<ImportedColumn>,
        chiaveObbligatoria: Boolean
    ): Pair<List<Map<String, Any?>>, List<Map<String, Any?>>> {

        if (rows.isEmpty()) return emptyList<Map<String, Any?>>() to emptyList()

        val campi = colonne.map { c ->
            Campo(
                origine = c.nome.lowercase(),
                fisica = Naming.column(c.nome),
                isChiave = c.isChiave,
                numerica = !c.isChiave && ColumnProposal.isNumerico(c.tipo)
            )
        }

        val doppie = campi.groupingBy { it.fisica }.eachCount().filterValues { it > 1 }.keys
        require(doppie.isEmpty()) {
            "Più colonne diventano lo stesso nome fisico: ${doppie.joinToString(", ")}"
        }

        val disponibili = rows.first().keys
        val mancanti = campi.map { it.origine }.filter { it !in disponibili }
        require(mancanti.isEmpty()) {
            "La sorgente non espone le colonne attese: ${mancanti.joinToString(", ")}. " +
                    "Colonne trovate: ${disponibili.joinToString(", ")}"
        }

        // Valori normalizzati, calcolati una volta sola: [colonna][riga].
        val testi: Array<Array<String?>> = Array(campi.size) { j ->
            val origine = campi[j].origine
            Array(rows.size) { i -> normalizza(rows[i][origine]) }
        }

        // Una symbol table per campo, un solo giro di lookup per colonna.
        val idMaps: List<Map<String, Long>> = campi.indices.map { j ->
            val valori = testi[j].asSequence().filterNotNull().toSet()
            symbolLookupService.getOrCreateIds(campi[j].fisica, valori)
        }

        val valid = ArrayList<Map<String, Any?>>(rows.size)
        val errors = ArrayList<Map<String, Any?>>()
        var fuoriScala = 0L

        for (i in rows.indices) {
            val out = HashMap<String, Any?>(campi.size * 2)
            var rowValid = true

            for (j in campi.indices) {
                val campo = campi[j]
                val testo = testi[j][i]

                if (testo == null) {
                    if (campo.isChiave && chiaveObbligatoria) {
                        rowValid = false
                        break
                    }
                    // Mai null: la colonna è UInt32 NOT NULL.
                    out[campo.fisica] = NULL_VALUE_ID
                } else {
                    val id = idMaps[j][testo]
                    if (id == null) {
                        // Il lookup avrebbe dovuto creare l'id: se manca è un
                        // problema della symbol table, non del dato. Va
                        // scartata la riga invece di scriverci uno zero.
                        log.warn("Valore '{}' senza id nel campo '{}': riga scartata", testo, campo.fisica)
                        rowValid = false
                        break
                    }
                    out[campo.fisica] = id
                }

                if (campo.numerica) {
                    val numero = toNumero(rows[i][campo.origine])
                    if (numero == null && testo != null) fuoriScala++
                    out[Naming.numericColumn(campo.fisica)] = numero
                }
            }

            if (rowValid) valid.add(out) else errors.add(rows[i])
        }

        if (fuoriScala > 0) {
            log.warn(
                "{} valori di colonne numeriche non rappresentabili come Decimal({},{}): copia numerica nulla, l'id resta",
                fuoriScala, PRECISIONE_NUMERICA, SCALA_NUMERICA
            )
        }
        return valid to errors
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