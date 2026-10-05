package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.AggregateOrder
import com.lightningbi.lightning_engine.model.AggregateRequest
import com.lightningbi.lightning_engine.model.AggregateResult
import com.lightningbi.lightning_engine.model.AreaMetrica
import com.lightningbi.lightning_engine.model.MisuraAnalisi
import com.lightningbi.lightning_engine.model.PivotView
import com.lightningbi.lightning_engine.model.RuoloTabella
import com.lightningbi.lightning_engine.model.TipoAggregazione
import com.lightningbi.lightning_engine.repository.MisuraAnalisiRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Un'analisi con le sue misure. */
data class AnalisiCompleta(val vista: PivotView, val misure: List<MisuraAnalisi>)

/** Un campo usabile come riga o colonna di una pivot (una dimensione del dataset). */
data class DimensioneAnalisi(val dimensioneId: UUID, val nome: String, val tabella: String)

/** Un campo di un Fatti su cui si può scrivere una misura. */
data class CampoMisurabile(
    val areaTabellaId: UUID,
    val tabella: String,
    val colonna: String,
    val nome: String,
    val numerico: Boolean
)

/** Il risultato di un'analisi: righe piatte, albero delle righe e metriche calcolate, nell'ordine. */
data class RisultatoAnalisi(
    val risultato: AggregateResult,
    val gerarchia: List<PivotEngine.PivotNode>,
    val metriche: List<AreaMetrica>
)

/**
 * Le analisi di un dataset (un dataset ha molte analisi, come i fogli di Qlik).
 *
 * Una analisi è una pivot: righe, colonne e MISURE. Come in Qlik le misure si
 * scrivono nell'analisi (campo, aggregazione, nome) e appartengono a lei sola;
 * il loro id sta in PivotView.pivotValues. Le selezioni NON sono dell'analisi:
 * sono dell'utente e del dataset (UserPivotState) e valgono per tutte le analisi.
 *
 * Il calcolo si appoggia al motore delle aggregazioni (AggregateService), a cui
 * le misure arrivano trasformate in metriche.
 */
@Service
class AnalisiService(
    private val pivotViewService: PivotViewService,
    private val misuraAnalisiRepository: MisuraAnalisiRepository,
    private val aggregateService: AggregateService,
    private val registryRepository: RegistryRepository,
    private val modelloDatasetCache: ModelloDatasetCache,
    private val versionService: VersionService
) {

    // ================= Analisi =================

    fun elenco(areaId: UUID): List<PivotView> = pivotViewService.findByArea(areaId)

    fun carica(vistaId: UUID): AnalisiCompleta? {
        val vista = pivotViewService.findById(vistaId) ?: return null
        return AnalisiCompleta(vista, misuraAnalisiRepository.findByView(vistaId))
    }

    fun crea(userId: UUID, areaId: UUID, nome: String): PivotView = pivotViewService.createView(userId, areaId, nome)

    fun rinomina(vistaId: UUID, nuovoNome: String): PivotView {
        val vista = pivotViewService.findById(vistaId) ?: error("Analisi $vistaId non trovata")
        return pivotViewService.renameView(vista, nuovoNome)
    }

    fun elimina(areaId: UUID, vistaId: UUID): Boolean = pivotViewService.deleteView(areaId, vistaId)

    /**
     * Righe, colonne e misure della pivot. Le misure devono essere dell'analisi; righe e
     * colonne dimensioni del dataset; un campo non può stare in entrambe.
     */
    @Transactional("postgresTransactionManager")
    fun impostaDisposizione(
        vistaId: UUID,
        righe: List<UUID>,
        colonne: List<UUID>,
        misureIds: List<UUID>
    ): PivotView {
        val vista = pivotViewService.findById(vistaId) ?: error("Analisi $vistaId non trovata")
        val dimensioniValide = registryRepository.findDimensioniByArea(vista.areaId).map { it.dimensioneId }.toSet()
        require(righe.all { it in dimensioniValide } && colonne.all { it in dimensioniValide }) {
            "Un campo scelto non fa parte del dataset"
        }
        require(righe.none { it in colonne }) { "Un campo non può stare sia nelle righe sia nelle colonne" }
        val misureDellAnalisi = misuraAnalisiRepository.findByView(vistaId).map { it.id }.toSet()
        require(misureIds.all { it in misureDellAnalisi }) { "Una misura scelta non appartiene all'analisi" }
        return pivotViewService.updatePivot(vista, righe.distinct(), colonne.distinct(), misureIds.distinct())
    }

    // ================= Campi =================

    /** I campi usabili come righe o colonne, in ordine di tabella e di nome. */
    fun dimensioniDisponibili(areaId: UUID): List<DimensioneAnalisi> {
        val modello = modelloCorrente(areaId)
        val campi = modello.bozza.campi().filter { !it.escluso }.associateBy { it.occorrenzaId to it.colonna }
        val alias = modello.bozza.occorrenze.associate { it.id to it.alias }
        return registryRepository.findDimensioniByArea(areaId).mapNotNull { d ->
            val tabellaId = d.areaTabellaId ?: return@mapNotNull null
            val campo = campi[tabellaId to d.colonnaFisica] ?: return@mapNotNull null
            DimensioneAnalisi(d.dimensioneId, campo.nomeOrigine, alias[tabellaId].orEmpty())
        }.sortedWith(compareBy({ it.tabella.lowercase() }, { it.nome.lowercase() }))
    }

    /** I campi dei Fatti su cui si può scrivere una misura. */
    fun campiMisurabili(areaId: UUID): List<CampoMisurabile> {
        val modello = modelloCorrente(areaId)
        val fatti = modello.bozza.occorrenze.filter { it.ruolo == RuoloTabella.FATTI }.associate { it.id to it.alias }
        return modello.bozza.campi()
            .filter { !it.escluso && it.occorrenzaId in fatti }
            .map { CampoMisurabile(it.occorrenzaId, fatti.getValue(it.occorrenzaId), it.colonna, it.nomeOrigine, it.numerico) }
            .sortedWith(compareBy({ it.tabella.lowercase() }, { it.nome.lowercase() }))
    }

    /** Le occorrenze dei Fatti del dataset (per il conteggio di righe, che non ha un campo). */
    fun tabelleFatti(areaId: UUID): List<Pair<UUID, String>> =
        modelloCorrente(areaId).bozza.occorrenze.filter { it.ruolo == RuoloTabella.FATTI }.map { it.id to it.alias }

    // ================= Misure =================

    /**
     * Aggiunge una misura all'analisi e la mette tra i valori della pivot.
     * [areaTabellaId] è sempre il Fatti su cui si calcola; [colonna] manca solo per il
     * conteggio di righe.
     */
    @Transactional("postgresTransactionManager")
    fun aggiungiMisura(
        vistaId: UUID,
        nome: String,
        tipo: TipoAggregazione,
        areaTabellaId: UUID,
        colonna: String?
    ): MisuraAnalisi {
        val vista = pivotViewService.findById(vistaId) ?: error("Analisi $vistaId non trovata")
        val misura = costruisciMisura(UUID.randomUUID(), vista, nome, tipo, areaTabellaId, colonna)
        misuraAnalisiRepository.save(misura)
        pivotViewService.updatePivot(vista, vista.pivotRows, vista.pivotColumns, vista.pivotValues + misura.id)
        return misura
    }

    @Transactional("postgresTransactionManager")
    fun modificaMisura(
        misuraId: UUID,
        nome: String,
        tipo: TipoAggregazione,
        areaTabellaId: UUID,
        colonna: String?
    ): MisuraAnalisi {
        val esistente = misuraAnalisiRepository.findByIds(listOf(misuraId)).firstOrNull()
            ?: error("Misura $misuraId non trovata")
        val vista = pivotViewService.findById(esistente.pivotViewId) ?: error("Analisi non trovata")
        val misura = costruisciMisura(misuraId, vista, nome, tipo, areaTabellaId, colonna)
        misuraAnalisiRepository.update(misura.copy(posizione = esistente.posizione))
        return misura
    }

    @Transactional("postgresTransactionManager")
    fun eliminaMisura(vistaId: UUID, misuraId: UUID) {
        val vista = pivotViewService.findById(vistaId) ?: error("Analisi $vistaId non trovata")
        misuraAnalisiRepository.delete(misuraId)
        pivotViewService.updatePivot(vista, vista.pivotRows, vista.pivotColumns, vista.pivotValues.filter { it != misuraId })
    }

    /**
     * Valida e costruisce una misura: il Fatti deve essere del dataset, SUM, AVG, MIN e MAX
     * vogliono un campo numerico, il conteggio di righe non ha campo, il nome è unico
     * nell'analisi.
     */
    private fun costruisciMisura(
        id: UUID,
        vista: PivotView,
        nome: String,
        tipo: TipoAggregazione,
        areaTabellaId: UUID,
        colonna: String?
    ): MisuraAnalisi {
        val modello = modelloCorrente(vista.areaId)
        val occorrenza = modello.bozza.occorrenze.firstOrNull { it.id == areaTabellaId }
        require(occorrenza != null && occorrenza.ruolo == RuoloTabella.FATTI) {
            "La misura deve calcolarsi su una tabella Fatti del dataset"
        }

        val pulito = nome.trim()
        require(pulito.isNotEmpty()) { "Il nome della misura è obbligatorio" }
        val altre = misuraAnalisiRepository.findByView(vista.id).filter { it.id != id }
        require(altre.none { it.nome.equals(pulito, ignoreCase = true) }) {
            "L'analisi ha già una misura chiamata \"$pulito\""
        }

        if (tipo == TipoAggregazione.COUNT) {
            return MisuraAnalisi(id, vista.id, pulito, tipo, areaTabellaId, null)
        }
        requireNotNull(colonna) { "Scegli il campo su cui calcolare la misura" }
        val campo = modello.bozza.campi()
            .firstOrNull { !it.escluso && it.occorrenzaId == areaTabellaId && it.colonna == colonna }
        requireNotNull(campo) { "Il campo scelto non fa parte della tabella" }
        val soloNumerici = tipo in setOf(
            TipoAggregazione.SUM, TipoAggregazione.AVG, TipoAggregazione.MIN, TipoAggregazione.MAX
        )
        require(!soloNumerici || campo.numerico) {
            "${etichetta(tipo)} richiede un campo numerico: '${campo.nomeOrigine}' non lo è"
        }
        return MisuraAnalisi(id, vista.id, pulito, tipo, areaTabellaId, colonna)
    }

    private fun etichetta(tipo: TipoAggregazione): String = when (tipo) {
        TipoAggregazione.SUM -> "La somma"
        TipoAggregazione.AVG -> "La media"
        TipoAggregazione.MIN -> "Il minimo"
        TipoAggregazione.MAX -> "Il massimo"
        TipoAggregazione.COUNT -> "Il conteggio"
        TipoAggregazione.COUNT_DISTINCT -> "Il conteggio distinti"
    }

    // ================= Calcolo =================

    /**
     * Calcola la pivot di un'analisi con le selezioni dell'utente. Senza misure restituisce un
     * risultato vuoto: una pivot senza misure non ha niente da calcolare.
     */
    fun calcola(
        vistaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        ordine: AggregateOrder? = null,
        ordineMisuraId: UUID? = null,
        limite: Int? = null
    ): RisultatoAnalisi {
        val analisi = carica(vistaId) ?: error("Analisi $vistaId non trovata")
        val vista = analisi.vista
        val perId = analisi.misure.associateBy { it.id }
        // Nell'ordine scelto dall'utente; id che non sono misure dell'analisi si ignorano.
        val metriche = vista.pivotValues.mapNotNull { perId[it]?.comeMetrica(vista.areaId) }
        if (metriche.isEmpty()) return RisultatoAnalisi(AggregateResult(emptyList(), false), emptyList(), emptyList())

        val richiesta = AggregateRequest(
            areaId = vista.areaId,
            selections = selezioni,
            groupBy = vista.pivotRows,
            columnBy = vista.pivotColumns,
            metricIds = metriche.map { it.id },
            misure = metriche,
            order = ordine,
            orderMetricId = ordineMisuraId,
            limit = limite,
            resolveLabels = true
        )
        val risultato = aggregateService.getAggregates(richiesta)
        val gerarchia = aggregateService.buildRowHierarchy(
            vista.areaId, risultato, vista.pivotRows, metriche.map { it.id }, metriche
        )
        return RisultatoAnalisi(risultato, gerarchia, metriche)
    }

    private fun modelloCorrente(areaId: UUID): ModelloDataset =
        modelloDatasetCache.get(areaId, versionService.snapshotVersions(areaId).registryVersion)
}