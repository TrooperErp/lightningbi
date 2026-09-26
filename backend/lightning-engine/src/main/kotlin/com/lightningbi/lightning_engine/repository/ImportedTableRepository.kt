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
}

