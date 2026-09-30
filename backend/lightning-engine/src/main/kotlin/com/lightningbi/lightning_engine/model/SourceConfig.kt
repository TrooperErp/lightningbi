package com.lightningbi.lightning_engine.model

import java.time.Instant
import java.util.UUID

enum class SourceStatus {
    VERIFIED,
    ERROR
}

enum class SyncMode {
    FULL_RELOAD,
    INCREMENTAL
}

/**
 * Una singola tabella da importare da questa sorgente: diventa una
 * ImportedTable a fine wizard/ETL. viewName è il nome della view/tabella
 * sul DB sorgente (es. QLK_VISTADOCUMENTICLIENTI), letta così com'è da
 * ConnectionOrchestrator - nessun JOIN SQL scritto qui, il JOIN avviene a
 * query-time (o nell'indice bitmap) sulla chiave condivisa.
 *
 * colonnaChiave è la colonna di JOIN verso i Fatti, proposta da nome
 * colonna condiviso e confermata nel wizard: null per i Fatti,
 * obbligatoria per le Dimensioni.
 */
data class ImportedSourceTable(
    val nomeLogico: String,
    val viewName: String,
    val ruolo: RuoloTabella,
    val colonnaChiave: String? = null
)

/**
 * Cosa importare dalla connessione: schema di lettura ed elenco delle
 * tabelle (Fatti + Dimensioni). Le credenziali NON stanno qui: vivono
 * nella connessione (SourceConnection), a cui AreaSource punta con
 * connectionId.
 */
data class SourceConfig(
    val schema: String?,
    val tabelle: List<ImportedSourceTable>,
    val syncMode: SyncMode = SyncMode.FULL_RELOAD
)

data class AreaSource(
    val id: UUID,
    val areaId: UUID,
    val tipoSorgente: String,
    val connectionId: UUID,
    val config: SourceConfig,
    val status: SourceStatus,
    val errorDetail: String?,
    val createdAt: Instant
)