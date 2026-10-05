package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.MisuraAnalisi
import java.util.UUID

/** Misure delle analisi (tabella lbi_pivot_view_misura, migrazione 032). */
interface MisuraAnalisiRepository {

    /** Le misure di un'analisi, nell'ordine in cui sono state create. */
    fun findByView(pivotViewId: UUID): List<MisuraAnalisi>

    /** Le misure con questi id, in qualunque analisi. */
    fun findByIds(ids: Collection<UUID>): List<MisuraAnalisi>

    /**
     * Aggiunge una misura in fondo all'analisi (la posizione si assegna qui).
     * Fallisce se l'analisi ha già una misura con lo stesso nome.
     */
    fun save(misura: MisuraAnalisi): MisuraAnalisi

    /** Cambia nome, aggregazione e campo di una misura. */
    fun update(misura: MisuraAnalisi)

    /** Toglie una misura. Restituisce true se esisteva. */
    fun delete(id: UUID): Boolean
}