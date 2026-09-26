package com.lightningbi.lightning_engine.model

import java.time.Instant
import java.util.UUID

enum class SourceStatus {
    PENDING_VIEW,
    VERIFIED,
    ERROR
}

enum class SyncMode {
    FULL_RELOAD,
    INCREMENTAL
}

/**
 * @deprecated Modello a view singola pre-denormalizzata (JOIN scritti a
 * mano in SQL). Sostituito da ImportedSourceTable: una AreaSource ora
 * importa N tabelle (Fatti + Dimensioni) come schema a stella nativo,
 * senza generare una view aggregata sul DB sorgente. Le Aree create prima
 * di questo refactor restano leggibili (il JSON in lbi_area_source.config
 * deserializza ancora su questi campi), ma nessun codice nuovo li deve
 * scrivere.
 */
@Deprecated("Sostituito da AreaSource.tabelle (ImportedSourceTable)")
data class LookupConfig(
    val targetFieldId: UUID,
    val lookupTable: String,
    val joinColumnMain: String,
    val joinColumnLookup: String,
    val valueColumn: String
)

@Deprecated("Sostituito da AreaSource.tabelle (ImportedSourceTable)")
data class DirectMapping(
    val targetFieldId: UUID,
    val sourceColumn: String
)

/**
 * Una singola tabella da importare da questa sorgente: diventerà una
 * ImportedTable a fine wizard/ETL. viewName è il nome della view/tabella
 * sul DB sorgente (es. QLK_VISTADOCUMENTICLIENTI), letta per intero da
 * JdbcExtractor - nessun JOIN SQL scritto qui, il JOIN avviene a
 * query-time (o nell'indice bitmap) sulla chiave condivisa.
 *
 * colonnaChiave è la colonna candidata per il JOIN coi Fatti, proposta da
 * MetadataService (nome colonna condiviso) e confermata nel wizard: null
 * finché non confermata, obbligatoria per ruolo DIMENSIONE prima che la
 * sorgente possa passare a VERIFIED.
 */
data class ImportedSourceTable(
    val nomeLogico: String,
    val viewName: String,
    val ruolo: RuoloTabella,
    val colonnaChiave: String? = null
)

/**
 * Config di connessione al DB sorgente più l'elenco delle tabelle da
 * importare come schema a stella nativo (Fatti + Dimensioni).
 *
 * Un'unica connessione (jdbcUrl/username/password/driver/schema) può
 * alimentare più tabelle: è così che TeamSystem espone QLK_* (una vista
 * Fatti e N viste Dimensione sullo stesso DB SEM).
 */
data class SourceConfig(
    val jdbcUrl: String,
    val username: String,
    val encryptedPassword: String,
    val driverClassName: String,
    val schema: String?,
    val tabelle: List<ImportedSourceTable>,
    val syncMode: SyncMode = SyncMode.FULL_RELOAD,
    // Campi legacy, letti solo per compatibilità con Aree create prima del
    // refactor TBS. Popolati insieme quando tabelle è vuota.
    @Deprecated("Usa tabelle") val mainTable: String? = null,
    @Deprecated("Usa tabelle") val viewName: String? = null,
    @Deprecated("Usa tabelle") val directMappings: List<DirectMapping> = emptyList(),
    @Deprecated("Usa tabelle") val lookups: List<LookupConfig> = emptyList()
) {
    /** true se questa sorgente usa ancora il vecchio modello a view singola. */
    fun isLegacy(): Boolean = tabelle.isEmpty() && viewName != null
}

data class AreaSource(
    val id: UUID,
    val areaId: UUID,
    val tipoSorgente: String,
    val config: SourceConfig,
    val status: SourceStatus,
    val errorDetail: String?,
    val createdAt: Instant
)