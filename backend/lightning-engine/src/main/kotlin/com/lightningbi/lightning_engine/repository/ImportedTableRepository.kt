package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.ImportedColumn
import com.lightningbi.lightning_engine.model.ImportedTable
import java.util.UUID

interface ImportedTableRepository {
    fun findByArea(areaId: UUID): List<ImportedTable>
    fun findById(id: UUID): ImportedTable?
    fun save(table: ImportedTable): ImportedTable
    fun updateColonnaChiave(id: UUID, colonnaChiave: String)
    fun deleteByArea(areaId: UUID): Int

    fun findColumnsByTable(importedTableId: UUID): List<ImportedColumn>
    fun saveColumns(columns: List<ImportedColumn>)
    fun deleteColumnsByTable(importedTableId: UUID): Int

    // ---- Ponte dataset <-> tabelle importate (lbi_area_imported_table) ----

    /** Collega una tabella importata a un dataset. Idempotente: se il legame esiste non fa nulla. */
    fun linkToArea(areaId: UUID, importedTableId: UUID)

    /** Toglie tutti i collegamenti di un dataset (mai le tabelle importate). Restituisce quanti ne ha tolti. */
    fun unlinkArea(areaId: UUID): Int

    /** Tabelle importate collegate a un dataset, ordinate per nome logico. */
    fun findLinkedToArea(areaId: UUID): List<ImportedTable>

    /** Dataset che usano una tabella importata (serve in Fase C per ricostruire il loro indice). */
    fun findAreaIdsUsing(importedTableId: UUID): List<UUID>

    /** Nomi fisici di tutte le tabelle importate, a prescindere dal dataset (anche quelle senza dataset). */
    fun findTabelleFisiche(): Set<String>
}

