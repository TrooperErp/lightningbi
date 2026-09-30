package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.VersionSnapshot
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Stato di una dimensione nel motore associativo:
 * - verdi: valori ammessi dalle selezioni sulle ALTRE dimensioni
 * - grigi: valori del dominio esclusi dalle selezioni
 * - selezionati: valori scelti dall'utente su questa dimensione
 */
data class DimensionState(
    val verdi: Set<Long>,
    val grigi: Set<Long>,
    val selezionati: Set<Long>
)

/**
 * Punto unico da cui l'applicazione chiede gli stati associativi
 * (verde/grigio/selezionato). Il motore è uno solo, basato sull'indice
 * bitmap (BitmapAssociativeStateService): questa classe esiste per tenere
 * stabile l'interfaccia verso chi la usa.
 *
 * dimensioniDaCalcolare limita QUALI dimensioni ricevono uno stato
 * (null = tutte). Le selezioni si applicano sempre tutte.
 */
@Service
class AssociativeStateFacade(
    private val bitmapEngine: BitmapAssociativeStateService
) {
    suspend fun getStates(
        areaId: UUID,
        selections: Map<UUID, Set<Long>>,
        versions: VersionSnapshot,
        dimensioniDaCalcolare: Set<UUID>? = null
    ): Map<UUID, DimensionState> =
        bitmapEngine.getStates(areaId, selections, versions, dimensioniDaCalcolare)
}