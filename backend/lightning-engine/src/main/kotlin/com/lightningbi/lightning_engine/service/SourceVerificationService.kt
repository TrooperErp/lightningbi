package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.AreaSource
import com.lightningbi.lightning_engine.model.SourceStatus
import com.lightningbi.lightning_engine.model.SyncMode
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Verifica che la view sulla sorgente esista e esponga le colonne attese.
 *
 * Una sorgente resta PENDING_VIEW finché questo controllo non passa: è la
 * condizione che EtlOrchestrator richiede per sincronizzare. Il controllo
 * serve a intercettare subito i due casi che altrimenti si manifesterebbero
 * come una tabella piena di zeri: la view non è mai stata creata dal DBA,
 * oppure è stata creata con alias diversi da quelli generati dal wizard.
 *
 * La colonna di sincronizzazione (lbi_updated_at) è attesa SOLO in modalità
 * incrementale. In FULL_RELOAD non serve: si ricarica tutto ogni volta,
 * quindi non c'è bisogno di sapere cosa è cambiato. Questo è il caso comune
 * di view aziendali preesistenti, scritte per altri scopi molto prima che
 * esistesse LightningBI - pretendere che espongano una colonna tecnica in
 * più le renderebbe automaticamente incompatibili con l'analisi, forzando
 * a riscrivere SQL già in produzione da anni solo per collegarle qui.
 */
@Service
class SourceVerificationService(
    private val areaSourceRepository: AreaSourceRepository,
    private val registryRepository: RegistryRepository,
    private val metadataService: MetadataService,
    private val cryptoService: CryptoService
) {
    private val log = LoggerFactory.getLogger(SourceVerificationService::class.java)

    /** Colonna tecnica richiesta solo per il filtro incrementale. */
    val updatedAtAlias = "lbi_updated_at"

    data class VerificationResult(
        val source: AreaSource,
        val ok: Boolean,
        val message: String
    )

    /**
     * Colonne che la view deve esporre per alimentare l'area.
     * Sono le stesse chiavi che TransformService cercherà nelle righe estratte.
     * lbi_updated_at è incluso solo se la sorgente sincronizza in modalità
     * incrementale.
     */
    fun expectedColumns(areaId: UUID, syncMode: SyncMode): List<String> {
        val dimensioni = registryRepository.findDimensioniByArea(areaId).map { it.colonnaFisica }
        val metriche = registryRepository.findMetricheByArea(areaId).mapNotNull { it.colonnaFisica }
        val base = (dimensioni + metriche).distinct()
        return if (syncMode == SyncMode.INCREMENTAL) base + updatedAtAlias else base
    }

    /** Verifica tutte le sorgenti di un'area. */
    fun verifyArea(areaId: UUID): List<VerificationResult> =
        areaSourceRepository.findByArea(areaId).map { verify(it) }

    /**
     * Verifica una sorgente e ne aggiorna lo stato persistito.
     *
     * La password cifrata è già nella configurazione: la verifica non
     * richiede all'utente di ridigitarla, a differenza del wizard, perché
     * qui non si sta configurando nulla di nuovo.
     */
    fun verify(source: AreaSource): VerificationResult {
        val attese = expectedColumns(source.areaId, source.config.syncMode)

        if (attese.isEmpty()) {
            return persist(source, false, "L'analisi non ha filtri né somme configurati")
        }

        return try {
            val password = cryptoService.decrypt(source.config.encryptedPassword)
            metadataService.connect(
                source.config.jdbcUrl, source.config.username, password, source.config.driverClassName
            ).use { conn ->
                val (ok, error) = metadataService.viewExistsWithColumns(
                    conn, source.config.schema, source.config.viewName, attese
                )
                if (ok) {
                    val nota = if (source.config.syncMode == SyncMode.FULL_RELOAD)
                        " (ricarico completo ad ogni sincronizzazione: nessuna colonna di data richiesta)"
                    else ""
                    persist(source, true, "View ${source.config.viewName} verificata: ${attese.size} colonne attese trovate$nota")
                } else {
                    persist(source, false, error ?: "La view non espone tutte le colonne attese")
                }
            }
        } catch (e: Exception) {
            log.warn("Verifica sorgente {} fallita", source.id, e)
            persist(source, false, e.message ?: e::class.qualifiedName ?: "Errore sconosciuto")
        }
    }

    private fun persist(source: AreaSource, ok: Boolean, message: String): VerificationResult {
        val updated = source.copy(
            status = if (ok) SourceStatus.VERIFIED else SourceStatus.ERROR,
            errorDetail = if (ok) null else message
        )
        areaSourceRepository.save(updated)
        return VerificationResult(updated, ok, message)
    }
}