package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.AggregateRequest
import com.lightningbi.lightning_engine.model.AggregateResult
import com.lightningbi.lightning_engine.model.Area
import com.lightningbi.lightning_engine.model.AreaDimensione
import com.lightningbi.lightning_engine.model.AreaSource
import com.lightningbi.lightning_engine.model.ChartData
import com.lightningbi.lightning_engine.model.Dimensione
import com.lightningbi.lightning_engine.model.SourceStatus
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.service.AggregateService
import com.lightningbi.lightning_engine.service.AssociativeStateService
import com.lightningbi.lightning_engine.service.ChartService
import com.lightningbi.lightning_engine.service.DimensionState
import com.lightningbi.lightning_engine.service.SourceVerificationService
import com.lightningbi.lightning_engine.service.SymbolLookupService
import com.lightningbi.lightning_engine.service.VersionService
import com.lightningbi.lightning_engine.etl.EtlOrchestrator
import com.lightningbi.lightning_engine.service.PivotEngine

import java.util.UUID

/**
 * Model: ogni lettura/scrittura di dati per AssociativeExplorerView passa
 * da qui. Non conosce nessun componente Vaadin - riceve id e mappe,
 * restituisce oggetti di dominio. Se un domani questa vista dovesse
 * cambiare completamente aspetto (altra libreria UI, altro framework),
 * questa classe non cambierebbe di una riga.
 *
 * cryptoService, metadataService, viewSqlGenerator, symbolTableService,
 * registryService NON sono qui: servono solo dentro NewAnalysisWizardDialog
 * e EditMetricsDialog, dialog a sé che restano indipendenti da questo
 * refactoring.
 */
class AssociativeExplorerData(
    private val registryRepository: RegistryRepository,
    private val areaSourceRepository: AreaSourceRepository,
    private val sourceVerificationService: SourceVerificationService,
    private val associativeStateService: AssociativeStateService,
    private val aggregateService: AggregateService,
    private val chartService: ChartService,
    private val versionService: VersionService,
    private val symbolLookupService: SymbolLookupService,
    private val etlOrchestrator: EtlOrchestrator
) {
    /** Il risultato completo di un ricalcolo: stati, aggregati, grafici, etichette. */
    data class RefreshResult(
        val states: Map<UUID, DimensionState>,
        val aggregates: AggregateResult,
        val rowHierarchy: List<PivotEngine.PivotNode>,
        val chartsData: List<ChartData>,
        val labels: Map<UUID, Map<Long, String>>
    )

    fun findAllAree(): List<Area> = registryRepository.findAllAree()

    fun findAreaById(id: UUID): Area? = registryRepository.findAreaById(id)

    fun findDimensioniByArea(areaId: UUID): List<AreaDimensione> =
        registryRepository.findDimensioniByArea(areaId)

    fun findDimensione(id: UUID): Dimensione? = registryRepository.findDimensione(id)

    fun findMetricheByArea(areaId: UUID) = registryRepository.findMetricheByArea(areaId)

    fun findSourceByArea(areaId: UUID): AreaSource? =
        areaSourceRepository.findByArea(areaId).firstOrNull()

    fun findSourcesByArea(areaId: UUID): List<AreaSource> =
        areaSourceRepository.findByArea(areaId)

    fun expectedColumns(areaId: UUID, syncMode: com.lightningbi.lightning_engine.model.SyncMode): List<String> =
        sourceVerificationService.expectedColumns(areaId, syncMode)

    suspend fun verifySource(areaId: UUID): List<SourceVerificationService.VerificationResult> =
        sourceVerificationService.verifyArea(areaId)

    suspend fun runEtl(areaId: UUID, source: AreaSource) =
        etlOrchestrator.runForArea(areaId, source)

    /**
     * Esegue il ricalcolo completo per un'area: stati associativi (solo
     * se ci sono dimensioni in Righe), aggregati, grafici, etichette
     * risolte. È il punto unico che AssociativeExplorerView chiama da
     * dentro una coroutine - questa funzione stessa è sospendibile, la
     * gestione del contesto (IO, ui.access) resta a carico del chiamante.
     */
    suspend fun refresh(
        areaId: UUID,
        pivotRows: List<UUID>,
        pivotColumns: List<UUID>,
        pivotValues: List<UUID>,
        selections: Map<UUID, Set<Long>>,
        dimensionNames: Map<UUID, String>
    ): RefreshResult {
        val versions = versionService.snapshotVersions(areaId)

        val states = if (pivotRows.isEmpty() && pivotColumns.isEmpty()) {
            emptyMap()
        } else {
            associativeStateService.getStates(areaId, selections, versions)
                .filterKeys { it in pivotRows || it in pivotColumns }
        }

        val aggregates = aggregateService.getAggregates(
            AggregateRequest(
                areaId = areaId,
                selections = selections,
                groupBy = pivotRows,
                columnBy = pivotColumns,
                metricIds = pivotValues,
                resolveLabels = true
            ),
            versions
        )
        val rowHierarchy = aggregateService.buildRowHierarchy(areaId, aggregates, pivotRows, pivotValues)
        val chartsData = chartService.getChartsData(areaId, pivotRows, pivotColumns, selections)

        val labels = resolveLabels(states, dimensionNames)

        return RefreshResult(
            states = states,
            aggregates = aggregates,
            rowHierarchy = rowHierarchy,
            chartsData = chartsData,
            labels = labels
        )
    }

    private fun resolveLabels(
        states: Map<UUID, DimensionState>,
        dimensionNames: Map<UUID, String>
    ): Map<UUID, Map<Long, String>> =
        states.mapNotNull { (dimId, state) ->
            val dimName = dimensionNames[dimId] ?: return@mapNotNull null
            val ids = state.verdi + state.grigi + state.selezionati
            dimId to symbolLookupService.resolveLabels(dimName, ids)
        }.toMap()

    fun labelOrFallback(labels: Map<Long, String>, id: Long?): String =
        symbolLookupService.labelOrFallback(labels, id)
}