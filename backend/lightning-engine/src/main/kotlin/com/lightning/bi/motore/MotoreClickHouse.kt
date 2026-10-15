package com.lightning.bi.motore

import com.lightning.bi.model.AggregateRequest
import com.lightning.bi.model.AggregateResult
import com.lightning.bi.model.VersionSnapshot
import com.lightning.bi.service.AggregateService
import com.lightning.bi.service.ContatoreCampo
import com.lightning.bi.service.FiltriService
import com.lightning.bi.service.SezioneAccessoService
import com.lightning.bi.service.ValoreFiltro
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Il motore di oggi: propagazione e aggregati calcolati da ClickHouse. Delega al codice
 * esistente (FiltriService, AggregateService) senza cambiarne la logica.
 *
 * numeroValori, idPossibili e conflitti ricalcolano il vincolo dalla sessione: vanno chiamati
 * dal thread della pagina, come oggi.
 */
@Service
class MotoreClickHouse(
    private val filtriService: FiltriService,
    private val aggregateService: AggregateService
) : MotoreAssociativo {

    override val nome = "clickhouse"

    override fun contatori(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioni: Collection<UUID>?,
        vincolo: SezioneAccessoService.Vincolo?
    ): Map<UUID, ContatoreCampo> = filtriService.contatori(areaId, selezioni, dimensioni, vincolo)

    override fun valori(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        ricerca: String?,
        offset: Int,
        limite: Int,
        vincolo: SezioneAccessoService.Vincolo?
    ): List<ValoreFiltro> = filtriService.valori(areaId, selezioni, dimensioneId, ricerca, offset, limite, vincolo)

    override fun numeroValori(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        ricerca: String?,
        vincolo: SezioneAccessoService.Vincolo?
    ): Int = filtriService.numeroValori(areaId, selezioni, dimensioneId, ricerca)

    override fun idPossibili(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        vincolo: SezioneAccessoService.Vincolo?
    ): Set<Long> = filtriService.idPossibili(areaId, selezioni, dimensioneId)

    override fun conflitti(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        valoreId: Long,
        vincolo: SezioneAccessoService.Vincolo?
    ): Set<UUID> = filtriService.conflitti(areaId, selezioni, dimensioneId, valoreId)

    override fun aggrega(
        richiesta: AggregateRequest,
        versioni: VersionSnapshot,
        vincolo: SezioneAccessoService.Vincolo?
    ): AggregateResult = aggregateService.getAggregates(richiesta, versioni, vincolo)
}