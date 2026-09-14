package com.lightningbi.lightning_engine.etl

import com.lightningbi.lightning_engine.model.AreaDimensione
import com.lightningbi.lightning_engine.model.AreaMetrica
import com.lightningbi.lightning_engine.service.SymbolLookupService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal

@Service
class TransformService(
    private val symbolLookupService: SymbolLookupService
) {
    private val log = LoggerFactory.getLogger(TransformService::class.java)

    /**
     * Id riservato al valore mancante.
     *
     * Le colonne dimensione su ClickHouse sono UInt32 NOT NULL: scriverci
     * null fa fallire l'insert. Serve quindi un id che rappresenti
     * esplicitamente "nessun valore", distinto da qualunque id reale.
     * Le symbol table partono da 1 (max(value_id) + 1 con tabella vuota),
     * quindi lo zero è libero per costruzione.
     *
     * Conseguenza per l'interfaccia: lo zero comparirà fra i valori
     * selezionabili di una dimensione non obbligatoria. Va risolto in
     * etichetta come "(non definito)" al momento della visualizzazione.
     */
    companion object {
        const val NULL_VALUE_ID = 0L
        const val NULL_VALUE_LABEL = "(non definito)"
    }

    /**
     * Converte le righe estratte dalla view in righe pronte per ClickHouse.
     *
     * Le chiavi in ingresso sono già i nomi delle colonne fisiche: la view
     * espone gli alias normalizzati da Naming, che coincidono con
     * AreaDimensione.colonnaFisica e AreaMetrica.colonnaFisica. Non c'è
     * quindi nessun rimappaggio da fare qui, né a monte.
     *
     * @return righe valide, righe scartate
     */
    fun transform(
        rows: List<Map<String, Any?>>,
        dimensioni: List<AreaDimensione>,
        dimensioneNomiById: Map<String, String>, // dimensione_id -> nome logico dimensione
        metriche: List<AreaMetrica>
    ): Pair<List<Map<String, Any?>>, List<Map<String, Any?>>> {

        if (rows.isEmpty()) return emptyList<Map<String, Any?>>() to emptyList()

        // Verifica preventiva: se la view non espone una colonna attesa, tutte
        // le righe risulterebbero null e la tabella si riempirebbe di zeri
        // senza che nessuno se ne accorga. Meglio fallire subito e dire quale
        // colonna manca.
        val colonneDisponibili = rows.first().keys
        val attese = dimensioni.map { it.colonnaFisica } + metriche.map { it.colonnaFisica }
        val mancanti = attese - colonneDisponibili
        require(mancanti.isEmpty()) {
            "La view non espone le colonne attese: ${mancanti.joinToString(", ")}. " +
                    "Colonne trovate: ${colonneDisponibili.joinToString(", ")}"
        }

        // Una stessa dimensione può comparire più volte nella stessa area con
        // ruoli diversi (data ordine e data consegna entrambe sulla dimensione
        // Tempo). I valori vanno quindi raccolti per NOME DIMENSIONE unendo
        // tutte le colonne che vi puntano, non per colonna: altrimenti si
        // farebbero due lookup concorrenti sulla stessa symbol table.
        val colonnePerDimensione: Map<String, List<String>> = dimensioni
            .mapNotNull { ad ->
                val nome = dimensioneNomiById[ad.dimensioneId.toString()] ?: return@mapNotNull null
                nome to ad.colonnaFisica
            }
            .groupBy({ it.first }, { it.second })

        val idMaps: Map<String, Map<String, Long>> = colonnePerDimensione.mapValues { (nome, colonne) ->
            val valori = rows.asSequence()
                .flatMap { row -> colonne.asSequence().map { row[it] } }
                .mapNotNull { it?.toString()?.takeIf { s -> s.isNotBlank() } }
                .toSet()
            symbolLookupService.getOrCreateIds(nome, valori)
        }

        val valid = mutableListOf<Map<String, Any?>>()
        val errors = mutableListOf<Map<String, Any?>>()

        rows.forEach { row ->
            val out = HashMap<String, Any?>(attese.size)
            var rowValid = true

            for (ad in dimensioni) {
                val nome = dimensioneNomiById[ad.dimensioneId.toString()]
                if (nome == null) {
                    rowValid = false
                    break
                }
                val raw = row[ad.colonnaFisica]?.toString()?.takeIf { it.isNotBlank() }

                if (raw == null) {
                    if (ad.obbligatoria) {
                        rowValid = false
                        break
                    }
                    // Mai null: la colonna è UInt32 NOT NULL.
                    out[ad.colonnaFisica] = NULL_VALUE_ID
                } else {
                    val id = idMaps[nome]?.get(raw)
                    if (id == null) {
                        // Il lookup avrebbe dovuto creare l'id: se manca è un
                        // problema della symbol table, non del dato. Va scartata
                        // la riga invece di scriverci uno zero silenzioso.
                        log.warn("Valore '{}' senza id nella dimensione '{}': riga scartata", raw, nome)
                        rowValid = false
                        break
                    }
                    out[ad.colonnaFisica] = id
                }
            }

            if (rowValid) {
                metriche.forEach { m ->
                    // Le metriche sono Decimal(18,4) NOT NULL: un null va
                    // trattato come zero, non propagato.
                    out[m.colonnaFisica] = toDecimal(row[m.colonnaFisica])
                }
                valid += out
            } else {
                errors += row
            }
        }

        if (errors.isNotEmpty()) {
            log.info("Transform: {} righe valide, {} scartate", valid.size, errors.size)
        }
        return valid to errors
    }

    /**
     * Le metriche arrivano dal driver come tipi diversi a seconda del DB
     * (BigDecimal, Double, Long, o String su qualche driver). ClickHouse
     * vuole un Decimal: si normalizza qui invece di sperare che il driver
     * di destinazione accetti qualunque cosa.
     */
    private fun toDecimal(value: Any?): BigDecimal = when (value) {
        null -> BigDecimal.ZERO
        is BigDecimal -> value
        is Int -> BigDecimal(value)
        is Long -> BigDecimal(value)
        is Double -> BigDecimal.valueOf(value)
        is Float -> BigDecimal.valueOf(value.toDouble())
        is Number -> BigDecimal(value.toString())
        is String -> value.trim().takeIf { it.isNotEmpty() }?.toBigDecimalOrNull() ?: BigDecimal.ZERO
        else -> BigDecimal.ZERO
    }
}