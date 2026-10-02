package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.AreaCampo
import java.util.UUID

interface AreaCampoRepository {

    /** Tutte le eccezioni (rinomine ed esclusioni) di un dataset, di tutte le sue occorrenze di tabella. */
    fun findByArea(areaId: UUID): List<AreaCampo>

    /** Le eccezioni di una sola occorrenza di tabella (AreaTabella.id). */
    fun findByAreaTabella(areaTabellaId: UUID): List<AreaCampo>

    /**
     * Inserisce o aggiorna l'eccezione per (occorrenza, colonna).
     * Richiede che l'occorrenza esista nel ponte lbi_area_imported_table (FK).
     */
    fun save(campo: AreaCampo): AreaCampo

    /** Toglie l'eccezione: la colonna torna al nome di default ed è inclusa. Restituisce true se esisteva. */
    fun delete(areaTabellaId: UUID, colonna: String): Boolean

    /** Toglie tutte le eccezioni di un dataset. Restituisce quante ne ha tolte. */
    fun deleteByArea(areaId: UUID): Int
}