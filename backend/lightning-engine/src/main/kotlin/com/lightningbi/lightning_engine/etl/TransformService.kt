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

    companion object {
        const val NULL_VALUE_ID = 0L
        const val NULL_VALUE_LABEL = "(non definito)"
    }

    /**
     * Trasforma le righe estratte in righe pronte per ClickHouse: dimensioni
     * sostituite da id di symbol table, metriche convertite in decimali.
     *
     * Serve sia il modello legacy (una tabella con dimensioni e metriche)
     * sia le tabelle importate dello schema a stella: per i Fatti si
     * passano le dimensioni con importedTableId dei Fatti e le metriche;
     * per una Dimensione le sue dimensioni e nessuna metrica.
     *
     * Le chiavi in ingresso (chiavi di riga e chiavi) sono NOMI FISICI
     * (Naming.column): chi estrae deve aver già normalizzato i nomi colonna
     * della sorgente, altrimenti le colonne risultano "mancanti".
     *
     * @param chiavi colonne chiave di JOIN (es. keycliente), trasformate in
     *   id UInt32 con la symbol table che porta il nome della colonna
     *   chiave. Fatti e Dimensione usano la STESSA symbol table, quindi gli
     *   id combaciano e il JOIN funziona. Vuoto = nessuna chiave (legacy).
     * @param chiaveObbligatoria se true, una riga con chiave vuota viene
     *   scartata (Dimensioni: una riga senza chiave non si collegherebbe a
     *   nulla). Se false la chiave vuota diventa NULL_VALUE_ID (Fatti: la
     *   riga resta, come una dimensione non obbligatoria).
     */
    fun transform(
        rows: List<Map<String, Any?>>,
        dimensioni: List<AreaDimensione>,
        dimensioneNomiById: Map<String, String>,
        metriche: List<AreaMetrica>,
        chiavi: List<String> = emptyList(),
        chiaveObbligatoria: Boolean = false
    ): Pair<List<Map<String, Any?>>, List<Map<String, Any?>>> {

        if (rows.isEmpty()) return emptyList<Map<String, Any?>>() to emptyList()

        val metricheConColonna = metriche.filter { it.colonnaFisica != null }

        val colonneDisponibili = rows.first().keys
        val attese = dimensioni.map { it.colonnaFisica } + chiavi + metricheConColonna.map { it.colonnaFisica!! }
        val mancanti = attese - colonneDisponibili
        require(mancanti.isEmpty()) {
            "La view non espone le colonne attese: ${mancanti.joinToString(", ")}. " +
                    "Colonne trovate: ${colonneDisponibili.joinToString(", ")}"
        }

        // Le dimensioni a valore grezzo (es. mese_numero) non passano da
        // symbol table: il loro valore è già l'id da scrivere. Vanno
        // escluse dal calcolo di idMaps, che serve solo alle dimensioni
        // categoriche (colonnePerDimensione sotto).
        val dimensioniCategoriche = dimensioni.filterNot { it.valoreGrezzo }

        // Una stessa dimensione può comparire più volte nella stessa area con
        // ruoli diversi (data ordine e data consegna entrambe sulla dimensione
        // Tempo). I valori vanno quindi raccolti per NOME DIMENSIONE unendo
        // tutte le colonne che vi puntano, non per colonna: altrimenti si
        // farebbero due lookup concorrenti sulla stessa symbol table.
        val colonnePerDimensione: Map<String, List<String>> = dimensioniCategoriche
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

        // Una symbol table per colonna chiave, identificata dal nome della
        // colonna: è ciò che rende uguali gli id su Fatti e Dimensione.
        val idMapsChiavi: Map<String, Map<String, Long>> = chiavi.associateWith { chiave ->
            val valori = rows.asSequence()
                .mapNotNull { it[chiave]?.toString()?.takeIf { s -> s.isNotBlank() } }
                .toSet()
            symbolLookupService.getOrCreateIds(chiave, valori)
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

                if (ad.valoreGrezzo) {
                    // Valore già numerico: scritto diretto, nessun lookup
                    // in symbol table. Un valore non parsabile come Long
                    // scarta la riga invece di scrivere un numero a caso.
                    if (raw == null) {
                        if (ad.obbligatoria) {
                            rowValid = false
                            break
                        }
                        out[ad.colonnaFisica] = NULL_VALUE_ID
                    } else {
                        val numero = raw.toLongOrNull()
                        if (numero == null) {
                            log.warn(
                                "Valore '{}' non numerico per la dimensione a valore grezzo '{}': riga scartata",
                                raw, nome
                            )
                            rowValid = false
                            break
                        }
                        out[ad.colonnaFisica] = numero
                    }
                    continue
                }

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
                for (chiave in chiavi) {
                    val raw = row[chiave]?.toString()?.takeIf { it.isNotBlank() }
                    if (raw == null) {
                        if (chiaveObbligatoria) {
                            rowValid = false
                            break
                        }
                        // Mai null: la colonna è UInt32 NOT NULL.
                        out[chiave] = NULL_VALUE_ID
                    } else {
                        val id = idMapsChiavi[chiave]?.get(raw)
                        if (id == null) {
                            log.warn("Valore '{}' senza id nella chiave '{}': riga scartata", raw, chiave)
                            rowValid = false
                            break
                        }
                        out[chiave] = id
                    }
                }
            }

            if (rowValid) {
                metricheConColonna.forEach { m ->
                    out[m.colonnaFisica!!] = toDecimal(row[m.colonnaFisica])
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