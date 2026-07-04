package com.lightningbi.lightning_engine.etl

import com.lightningbi.lightning_engine.model.AreaDimensione
import com.lightningbi.lightning_engine.model.AreaMetrica
import com.lightningbi.lightning_engine.service.SymbolLookupService
import org.springframework.stereotype.Service

@Service
class TransformService(
    private val symbolLookupService: SymbolLookupService
) {

    fun transform(
        rows: List<Map<String, Any?>>,
        dimensioni: List<AreaDimensione>,
        dimensioneNomiById: Map<String, String>, // dimensione_id -> nome dimensione
        metriche: List<AreaMetrica>
    ): Pair<List<Map<String, Any?>>, List<Map<String, Any?>>> {
        // valid rows, error rows

        // 1. raccogli valori distinti per dimensione (batch lookup)
        val valuesByDim = dimensioni.associate { ad ->
            val nome = dimensioneNomiById[ad.dimensioneId.toString()]!!
            nome to rows.mapNotNull { it[ad.colonnaFisica]?.toString() }.toSet()
        }
        val idMaps = valuesByDim.mapValues { (nome, values) ->
            symbolLookupService.getOrCreateIds(nome, values)
        }

        val valid = mutableListOf<Map<String, Any?>>()
        val errors = mutableListOf<Map<String, Any?>>()

        rows.forEach { row ->
            val out = mutableMapOf<String, Any?>()
            var rowValid = true

            for (ad in dimensioni) {
                val nome = dimensioneNomiById[ad.dimensioneId.toString()]!!
                val raw = row[ad.colonnaFisica]?.toString()
                if (raw == null) {
                    if (ad.obbligatoria) {
                        rowValid = false
                        break
                    }
                    out[ad.colonnaFisica] = null
                } else {
                    out[ad.colonnaFisica] = idMaps[nome]?.get(raw)
                }
            }

            if (rowValid) {
                metriche.forEach { m -> out[m.colonnaFisica] = row[m.colonnaFisica] }
                valid += out
            } else {
                errors += row
            }
        }

        return valid to errors
    }
}