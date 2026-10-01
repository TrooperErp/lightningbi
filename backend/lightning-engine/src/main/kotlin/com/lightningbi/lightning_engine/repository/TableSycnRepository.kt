package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.TableSync
import java.time.LocalDateTime
import java.util.UUID

interface TableSyncRepository {

    /** Configurazione di sincronizzazione di una tabella importata, null se non ne ha una. */
    fun findByTable(importedTableId: UUID): TableSync?

    /**
     * Inserisce o aggiorna la configurazione. In aggiornamento NON tocca
     * ultima_sync_inizio: quella cambia solo con [updateUltimaSync], così
     * salvare la configurazione non azzera lo stato dell'ultima sincronizzazione.
     */
    fun save(sync: TableSync): TableSync

    /** Registra l'ora di inizio (nell'ora della sorgente) dell'ultima sincronizzazione riuscita. */
    fun updateUltimaSync(importedTableId: UUID, ultimaSyncInizio: LocalDateTime)

    /** Toglie la configurazione. Restituisce true se esisteva. */
    fun delete(importedTableId: UUID): Boolean
}