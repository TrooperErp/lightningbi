package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.AreaTabella
import java.util.UUID

/**
 * Occorrenze di tabelle importate dentro un dataset (tabella ponte
 * lbi_area_imported_table, migrazione 030).
 */
interface AreaTabellaRepository {

    /** Le occorrenze di un dataset, in ordine di alias. */
    fun findByArea(areaId: UUID): List<AreaTabella>

    fun findById(id: UUID): AreaTabella?

    /**
     * Aggiunge un'occorrenza. Fallisce se nel dataset esiste già un alias
     * uguale (vincolo uq_area_tabella_alias).
     */
    fun save(tabella: AreaTabella): AreaTabella

    /** Cambia l'alias di un'occorrenza. Fallisce se l'alias è già in uso nel dataset. */
    fun updateAlias(id: UUID, alias: String)

    /**
     * Toglie un'occorrenza. I suoi campi cadono in cascata; dimensioni e
     * metriche che la usano vanno tolte prima da chi chiama. Restituisce true
     * se esisteva.
     */
    fun delete(id: UUID): Boolean

    /** Toglie tutte le occorrenze di un dataset. Restituisce quante ne ha tolte. */
    fun deleteByArea(areaId: UUID): Int
    fun updateDisconnessa(id: UUID, disconnessa: Boolean)
}