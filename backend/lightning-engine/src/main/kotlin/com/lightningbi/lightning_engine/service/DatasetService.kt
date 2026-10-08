package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.Area
import com.lightningbi.lightning_engine.model.AreaCampo
import com.lightningbi.lightning_engine.model.AreaDimensione
import com.lightningbi.lightning_engine.model.AreaMetrica
import com.lightningbi.lightning_engine.model.AreaSource
import com.lightningbi.lightning_engine.model.AreaTabella
import com.lightningbi.lightning_engine.model.ImportedTable
import com.lightningbi.lightning_engine.model.RuoloTabella
import com.lightningbi.lightning_engine.model.SourceConfig
import com.lightningbi.lightning_engine.model.SourceStatus
import com.lightningbi.lightning_engine.model.SyncMode
import com.lightningbi.lightning_engine.model.TipoMetrica
import com.lightningbi.lightning_engine.repository.AreaCampoRepository
import com.lightningbi.lightning_engine.repository.AreaSourceRepository
import com.lightningbi.lightning_engine.repository.AreaTabellaRepository
import com.lightningbi.lightning_engine.repository.ImportedTableRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.repository.SourceConnectionRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** Il modello ha errori che bloccano il salvataggio (vedi [esito]). */
class DatasetNonValidoException(val esito: EsitoValidazione) : RuntimeException(
    "Il dataset non può essere salvato: " + esito.errori.joinToString(" | ") { it.messaggio }
)

/**
 * Costruzione e salvataggio dei dataset, nello stile del Data manager di Qlik.
 *
 * Si lavora su una bozza in memoria ([DatasetBozza]); solo [salva] scrive sul
 * database, in una transazione, dopo aver validato il modello (loop = errore).
 * Su un dataset già esistente il salvataggio CONFRONTA e aggiorna: le metriche
 * mantengono il loro id, da cui dipendono i grafici.
 *
 * Una tabella importata può comparire più volte (AreaTabella). Le tabelle
 * importate non si toccano mai da qui: si importano e sincronizzano a parte.
 */
@Service
class DatasetService(
    private val registryRepository: RegistryRepository,
    private val registryService: RegistryService,
    private val importedTableRepository: ImportedTableRepository,
    private val areaTabellaRepository: AreaTabellaRepository,
    private val areaCampoRepository: AreaCampoRepository,
    private val areaSourceRepository: AreaSourceRepository,
    private val sourceConnectionRepository: SourceConnectionRepository
) {

    // ---------- bozza ----------

    fun nuova(): DatasetBozza = DatasetBozza()

    /** Le tabelle importate tra cui scegliere, in ordine di nome. */
    fun tabelleDisponibili(): List<ImportedTable> =
        importedTableRepository.findAll().sortedBy { it.nomeLogico.lowercase() }

    /** Aggiunge alla bozza una tabella importata (anche una seconda volta, con un altro alias). */
    fun aggiungiTabella(bozza: DatasetBozza, importedTableId: UUID): DatasetBozza {
        val tabella = importedTableRepository.findById(importedTableId)
            ?: throw IllegalArgumentException("Tabella importata non trovata")
        return bozza.aggiungiTabella(
            tabella,
            importedTableRepository.findColumnsByTable(tabella.id),
            prefissiTecniciDi(tabella)
        )
    }

    /**
     * I prefissi dei campi TECNICI (chiavi) dalle proprietà della connessione della tabella,
     * ad esempio "_KEY". Un campo con questo prefisso lega le tabelle ma non si mostra
     * all'utente (Dataset, Analisi, Grafici).
     */
    private fun prefissiTecniciDi(tabella: ImportedTable): List<String> {
        val connessione = tabella.connectionId?.let { sourceConnectionRepository.findById(it) } ?: return emptyList()
        return ColumnProposal.lista(connessione.parametri, ColumnProposal.PREFISSI_COLONNA_CHIAVE)
    }

    /**
     * Ricostruisce la bozza di un dataset salvato. Dimensioni e metriche
     * precedenti alla migrazione 030, senza occorrenza, non sono caricate.
     */
    fun carica(areaId: UUID): DatasetBozza? {
        val area = registryRepository.findAreaById(areaId) ?: return null

        val occorrenze = areaTabellaRepository.findByArea(areaId).map { at ->
            val tabella = importedTableRepository.findById(at.importedTableId)
                ?: error("Tabella importata ${at.importedTableId} non trovata")
            val eccezioni = areaCampoRepository.findByAreaTabella(at.id)
                .associate { it.colonna to EccezioneCampo(it.nomeCampo, it.escluso) }
            OccorrenzaBozza(
                id = at.id,
                importedTableId = tabella.id,
                nomeLogico = tabella.nomeLogico,
                alias = at.alias,
                ruolo = tabella.ruolo,
                colonne = importedTableRepository.findColumnsByTable(tabella.id),
                eccezioni = eccezioni,
                prefissiTecnici = prefissiTecniciDi(tabella),
                disconnessa = at.disconnessa
            )
        }

        val dimensioni = registryRepository.findDimensioniByArea(areaId)
            .mapNotNull { d -> d.areaTabellaId?.let { RiferimentoCampo(it, d.colonnaFisica) } }
            .toSet()

        val metriche = registryRepository.findMetricheByArea(areaId)
            .filter { it.tipoMetrica == TipoMetrica.AGGREGAZIONE_COLONNA }
            .mapNotNull { m ->
                m.areaTabellaId?.let { MetricaBozza(m.id, m.nome, it, m.colonnaFisica, m.tipoAggregazione) }
            }

        return DatasetBozza(areaId, area.nome, occorrenze, dimensioni, metriche, area.campoCalendario)
    }

    // ---------- salvataggio ----------

    /**
     * Valida e salva. Restituisce l'id del dataset. Lancia
     * [DatasetNonValidoException] se il modello ha errori (loop compresi).
     *
     * Ordine (per le chiavi esterne): dimensioni e metriche tolte o cambiate,
     * occorrenze, eccezioni dei campi, dimensioni, metriche.
     */
    @Transactional("postgresTransactionManager")
    fun salva(bozza: DatasetBozza): UUID {
        val esito = bozza.valida()
        if (!esito.valido) throw DatasetNonValidoException(esito)

        val nome = bozza.nome.trim()
        val areaIdEsistente = bozza.areaId
        val altra = registryRepository.findAreaByNome(nome)
        require(altra == null || altra.id == areaIdEsistente) { "Esiste già un dataset chiamato \"$nome\"" }

        val primoFatti = bozza.occorrenze.first { it.ruolo == RuoloTabella.FATTI }
        val tabellaFatti = importedTableRepository.findById(primoFatti.importedTableId)
            ?: error("Tabella importata ${primoFatti.importedTableId} non trovata")

        val area = salvaArea(areaIdEsistente, nome, tabellaFatti.tabellaFisica)
        if (area.campoCalendario != bozza.campoCalendario) {
            registryRepository.updateArea(area.copy(campoCalendario = bozza.campoCalendario))
        }
        assicuraSorgente(area.id, tabellaFatti)

        // 1. via tutte le dimensioni (si riscrivono) e le metriche tolte o cambiate
        registryRepository.deleteAreaDimensioniByArea(area.id)
        val metricheRimaste = eliminaMetricheNonPiuValide(area.id, bozza)

        // 2. occorrenze
        sincronizzaOccorrenze(area.id, bozza)

        // 3. eccezioni sui campi (rinomine ed esclusioni)
        sincronizzaCampi(bozza)

        // 4. dimensioni e 5. metriche
        scriviDimensioni(area.id, bozza)
        scriviMetriche(area.id, bozza, metricheRimaste)

        registryRepository.bumpVersion()
        return area.id
    }

    private fun salvaArea(areaId: UUID?, nome: String, tabellaFisica: String): Area {
        if (areaId == null) return registryService.createArea(nome, tabellaFisica)
        val attuale = registryRepository.findAreaById(areaId) ?: error("Dataset $areaId non trovato")
        val aggiornata = attuale.copy(nome = nome, tabellaFisica = tabellaFisica)
        if (aggiornata != attuale) registryRepository.updateArea(aggiornata)
        return aggiornata
    }

    /**
     * Passo ponte: AssociativeExplorerView cerca ancora la sorgente del
     * dataset per lanciare "Sincronizza". Si crea una sola volta, dalla
     * connessione dei Fatti. Sparisce con il blocco distruttivo.
     */
    private fun assicuraSorgente(areaId: UUID, fatti: ImportedTable) {
        if (areaSourceRepository.findByArea(areaId).isNotEmpty()) return
        val connessioneId = fatti.connectionId
            ?: throw IllegalArgumentException("La tabella '${fatti.nomeLogico}' non ha una connessione")
        val connessione = sourceConnectionRepository.findById(connessioneId)
            ?: throw IllegalArgumentException("Connessione della tabella '${fatti.nomeLogico}' non trovata")
        areaSourceRepository.save(
            AreaSource(
                id = UUID.randomUUID(),
                areaId = areaId,
                tipoSorgente = connessione.tipo,
                connectionId = connessione.id,
                config = SourceConfig(schema = fatti.schemaOrigine, tabelle = emptyList(), syncMode = SyncMode.FULL_RELOAD),
                status = SourceStatus.VERIFIED,
                errorDetail = null,
                createdAt = Instant.now()
            )
        )
    }

    /**
     * Toglie le metriche non più nella bozza o con colonna/Fatti cambiati
     * (queste si ricreano con lo stesso id). Restituisce quelle rimaste.
     */
    private fun eliminaMetricheNonPiuValide(areaId: UUID, bozza: DatasetBozza): Map<UUID, AreaMetrica> {
        val volute = bozza.metriche.associateBy { it.id }
        val esistenti = registryRepository.findMetricheByArea(areaId)
            .filter { it.tipoMetrica == TipoMetrica.AGGREGAZIONE_COLONNA }
            .associateBy { it.id }
        val rimaste = mutableMapOf<UUID, AreaMetrica>()
        esistenti.values.forEach { e ->
            val v = volute[e.id]
            if (v == null || v.colonna != e.colonnaFisica || v.areaTabellaId != e.areaTabellaId) {
                registryRepository.deleteAreaMetrica(e.id)
            } else {
                rimaste[e.id] = e
            }
        }
        return rimaste
    }

    private fun sincronizzaOccorrenze(areaId: UUID, bozza: DatasetBozza) {
        val esistenti = areaTabellaRepository.findByArea(areaId).associateBy { it.id }
        val nuoviId = bozza.occorrenze.map { it.id }.toSet()

        esistenti.keys.filter { it !in nuoviId }.forEach { areaTabellaRepository.delete(it) }

        // Alias cambiati: due fasi, per non violare l'unicità se due alias si scambiano.
        val daRinominare = bozza.occorrenze.filter { o -> esistenti[o.id]?.let { it.alias != o.alias } == true }
        daRinominare.forEach { areaTabellaRepository.updateAlias(it.id, "tmp_${it.id}") }
        daRinominare.forEach { areaTabellaRepository.updateAlias(it.id, it.alias) }
        bozza.occorrenze
            .filter { o -> esistenti[o.id]?.let { it.disconnessa != o.disconnessa } == true }
            .forEach { areaTabellaRepository.updateDisconnessa(it.id, it.disconnessa) }
        bozza.occorrenze.filter { it.id !in esistenti }.forEach {
            areaTabellaRepository.save(AreaTabella(it.id, areaId, it.importedTableId, it.alias, it.disconnessa))
        }
    }

    private fun sincronizzaCampi(bozza: DatasetBozza) {
        bozza.occorrenze.forEach { o ->
            areaCampoRepository.findByAreaTabella(o.id)
                .filter { it.colonna !in o.eccezioni }
                .forEach { areaCampoRepository.delete(o.id, it.colonna) }
            o.eccezioni.forEach { (colonna, ecc) ->
                areaCampoRepository.save(AreaCampo(o.id, colonna, ecc.nomeCampo, ecc.escluso))
            }
        }
    }

    /**
     * Una dimensione per nome di campo (la chiave del registry è
     * (dataset, dimensione)). Un campo condiviso da più tabelle ha come
     * proprietario la tabella Dimensione, se c'è, altrimenti la prima.
     * Il nome della dimensione è il nome del campo nel dataset.
     */
    private fun scriviDimensioni(areaId: UUID, bozza: DatasetBozza) {
        val scelti = bozza.campi().filter {
            !it.escluso && RiferimentoCampo(it.occorrenzaId, it.colonna) in bozza.dimensioni
        }
        scelti.groupBy { it.nomeCampo }.forEach { (nomeCampo, lista) ->
            val proprietario = lista.firstOrNull { it.ruolo == RuoloTabella.DIMENSIONE } ?: lista.first()
            val occorrenza = bozza.occorrenza(proprietario.occorrenzaId)
            val dimensione = registryService.findOrCreateDimensione(nomeCampo)
            registryRepository.saveAreaDimensione(
                AreaDimensione(
                    areaId = areaId,
                    dimensioneId = dimensione.id,
                    colonnaFisica = proprietario.colonna,
                    obbligatoria = false,
                    cardinalitaStimata = null,
                    valoreGrezzo = false,
                    importedTableId = occorrenza.importedTableId,
                    areaTabellaId = occorrenza.id
                )
            )
        }
    }

    private fun scriviMetriche(areaId: UUID, bozza: DatasetBozza, rimaste: Map<UUID, AreaMetrica>) {
        bozza.metriche.forEach { m ->
            val occorrenza = bozza.occorrenza(m.areaTabellaId)
            val esistente = rimaste[m.id]
            if (esistente == null) {
                registryRepository.saveAreaMetrica(
                    AreaMetrica(
                        id = m.id,
                        areaId = areaId,
                        nome = m.nome.trim(),
                        colonnaFisica = m.colonna,
                        tipoAggregazione = m.tipo,
                        tipoMetrica = TipoMetrica.AGGREGAZIONE_COLONNA,
                        espressione = null,
                        importedTableId = occorrenza.importedTableId,
                        areaTabellaId = occorrenza.id
                    )
                )
            } else if (esistente.nome != m.nome.trim() || esistente.tipoAggregazione != m.tipo) {
                registryRepository.updateAreaMetrica(
                    esistente.copy(nome = m.nome.trim(), tipoAggregazione = m.tipo)
                )
            }
        }
    }
}