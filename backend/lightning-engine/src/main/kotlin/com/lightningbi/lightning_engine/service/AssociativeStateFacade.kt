package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.VersionSnapshot
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Punto unico da cui l'applicazione chiede gli stati associativi
 * (verde/grigio). Decide quale motore è primario e, opzionalmente, fa
 * girare l'altro "in ombra" per confrontarne i risultati.
 *
 * Configurazione (application.yml o variabili d'ambiente):
 * - lightningbi.assoc.engine = QUERY | BITMAP  (default QUERY)
 * - lightningbi.assoc.compare = true | false   (default true)
 *
 * Il motore in ombra gira in background su uno scope dedicato: l'utente
 * riceve subito il risultato del primario, il confronto non aggiunge
 * latenza. Le differenze finiscono nel log con prefisso
 * "ASSOC-COMPARE", insieme ai tempi dei due motori.
 *
 * Il motore a query non sa calcolare un sottoinsieme di dimensioni:
 * quando è primario e ne viene richiesto uno, calcola tutto e filtra.
 */
@Service
class AssociativeStateFacade(
    private val queryEngine: AssociativeStateService,
    private val bitmapEngine: BitmapAssociativeStateService,
    @Value("\${lightningbi.assoc.engine:QUERY}") private val engineName: String,
    @Value("\${lightningbi.assoc.compare:true}") private val compareEnabled: Boolean
) {
    private val log = LoggerFactory.getLogger(AssociativeStateFacade::class.java)

    private enum class Engine { QUERY, BITMAP }

    private val primary: Engine =
        runCatching { Engine.valueOf(engineName.trim().uppercase()) }.getOrElse {
            log.warn("lightningbi.assoc.engine='{}' non valido, uso QUERY", engineName)
            Engine.QUERY
        }

    private val shadowScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        log.info("Motore associativo primario: {}, confronto in ombra: {}", primary, compareEnabled)
    }

    suspend fun getStates(
        areaId: UUID,
        selections: Map<UUID, Set<Long>>,
        versions: VersionSnapshot,
        dimensioniDaCalcolare: Set<UUID>? = null
    ): Map<UUID, DimensionState> {
        val start = System.currentTimeMillis()
        val result = runEngine(primary, areaId, selections, versions, dimensioniDaCalcolare)
        val primaryMs = System.currentTimeMillis() - start

        if (compareEnabled) {
            val shadow = if (primary == Engine.QUERY) Engine.BITMAP else Engine.QUERY
            shadowScope.launch {
                try {
                    val shadowStart = System.currentTimeMillis()
                    val shadowResult = runEngine(shadow, areaId, selections, versions, dimensioniDaCalcolare)
                    val shadowMs = System.currentTimeMillis() - shadowStart
                    compare(areaId, primary, result, primaryMs, shadow, shadowResult, shadowMs)
                } catch (e: Exception) {
                    log.warn("ASSOC-COMPARE area={} motore in ombra {} fallito", areaId, shadow, e)
                }
            }
        }

        return result
    }

    private suspend fun runEngine(
        engine: Engine,
        areaId: UUID,
        selections: Map<UUID, Set<Long>>,
        versions: VersionSnapshot,
        dimensioniDaCalcolare: Set<UUID>?
    ): Map<UUID, DimensionState> = when (engine) {
        Engine.QUERY -> {
            val all = queryEngine.getStates(areaId, selections, versions)
            if (dimensioniDaCalcolare == null) all else all.filterKeys { it in dimensioniDaCalcolare }
        }
        Engine.BITMAP -> bitmapEngine.getStates(areaId, selections, versions, dimensioniDaCalcolare)
    }

    /**
     * Confronto dimensione per dimensione su verdi, grigi e selezionati.
     * Per ogni differenza logga quanti valori mancano/avanzano e al
     * massimo 5 esempi, per non inondare il log su dimensioni grandi.
     * I tempi includono eventuali cache hit Redis: vanno letti su più
     * click, non su uno solo.
     */
    private fun compare(
        areaId: UUID,
        primary: Engine, primaryResult: Map<UUID, DimensionState>, primaryMs: Long,
        shadow: Engine, shadowResult: Map<UUID, DimensionState>, shadowMs: Long
    ) {
        val differenze = mutableListOf<String>()

        (primaryResult.keys + shadowResult.keys).forEach { dimId ->
            val p = primaryResult[dimId]
            val s = shadowResult[dimId]
            if (p == null || s == null) {
                differenze += "dim=$dimId presente solo in ${if (p == null) shadow else primary}"
                return@forEach
            }
            fun diff(nome: String, a: Set<Long>, b: Set<Long>) {
                val soloPrimario = a - b
                val soloOmbra = b - a
                if (soloPrimario.isNotEmpty() || soloOmbra.isNotEmpty()) {
                    differenze += "dim=$dimId $nome: solo $primary=${soloPrimario.size} ${soloPrimario.take(5)}, " +
                            "solo $shadow=${soloOmbra.size} ${soloOmbra.take(5)}"
                }
            }
            diff("verdi", p.verdi, s.verdi)
            diff("grigi", p.grigi, s.grigi)
            diff("selezionati", p.selezionati, s.selezionati)
        }

        if (differenze.isEmpty()) {
            log.info(
                "ASSOC-COMPARE area={} OK dimensioni={} {}={}ms {}={}ms",
                areaId, primaryResult.size, primary, primaryMs, shadow, shadowMs
            )
        } else {
            log.warn(
                "ASSOC-COMPARE area={} DIFFERENZE={} {}={}ms {}={}ms\n{}",
                areaId, differenze.size, primary, primaryMs, shadow, shadowMs,
                differenze.joinToString("\n")
            )
        }
    }

    @PreDestroy
    fun shutdown() {
        shadowScope.cancel()
    }
}