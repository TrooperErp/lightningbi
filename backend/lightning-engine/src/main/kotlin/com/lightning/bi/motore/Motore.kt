package com.lightning.bi.motore

import com.lightning.bi.motore.memoria.MotoreMemoria
import com.lightning.bi.repository.RegistryRepository
import com.lightning.bi.service.ContatoreCampo
import com.lightning.bi.service.SezioneAccessoService
import com.lightning.bi.service.ValoreFiltro
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Il motore usato dalle pagine per tutto ciò che dipende dalle selezioni: stati, contatori,
 * elenchi, possibili, conflitti. Stessi metodi di FiltriService, così nelle pagine cambia solo
 * l'oggetto chiamato.
 *
 * Si usa il motore in memoria. ClickHouse risponde solo finché il dataset non è ancora in memoria
 * (avvio del server, circa 45 s per Agenti): l'utente non aspetta il caricamento.
 *
 * All'avvio si precaricano tutti i dataset. I metodi senza vincolo lo calcolano qui: vanno chiamati
 * dal thread della pagina (legge la sessione).
 */
@Service
class Motore(
    private val memoria: MotoreMemoria,
    private val clickHouse: MotoreClickHouse,
    private val registryRepository: RegistryRepository,
    private val sezioneAccessoService: SezioneAccessoService
) {
    private val log = LoggerFactory.getLogger(Motore::class.java)

    @EventListener(ApplicationReadyEvent::class)
    fun precaricaTutti() {
        registryRepository.findAllAree().forEach {
            log.info("Precarico in memoria il dataset '{}'", it.nome)
            memoria.precarica(it.id)
        }
    }

    /** Memoria se il dataset è pronto; altrimenti ClickHouse, e intanto si carica. */
    private fun scegli(areaId: UUID): MotoreAssociativo =
        if (memoria.caricato(areaId)) memoria
        else {
            memoria.precarica(areaId)
            clickHouse
        }

    fun contatori(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioni: Collection<UUID>?,
        vincolo: SezioneAccessoService.Vincolo?
    ): Map<UUID, ContatoreCampo> = scegli(areaId).contatori(areaId, selezioni, dimensioni, vincolo)

    fun valori(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        ricerca: String?,
        offset: Int,
        limite: Int,
        vincolo: SezioneAccessoService.Vincolo?
    ): List<ValoreFiltro> = scegli(areaId).valori(areaId, selezioni, dimensioneId, ricerca, offset, limite, vincolo)

    /** Come sopra; il vincolo si calcola qui (thread della pagina). */
    fun valori(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        ricerca: String?,
        offset: Int,
        limite: Int
    ): List<ValoreFiltro> = valori(areaId, selezioni, dimensioneId, ricerca, offset, limite, sezioneAccessoService.vincolo(areaId))

    fun numeroValori(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        ricerca: String?
    ): Int = scegli(areaId).numeroValori(areaId, selezioni, dimensioneId, ricerca, sezioneAccessoService.vincolo(areaId))

    fun idPossibili(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID
    ): Set<Long> = scegli(areaId).idPossibili(areaId, selezioni, dimensioneId, sezioneAccessoService.vincolo(areaId))

    fun conflitti(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        valoreId: Long
    ): Set<UUID> = scegli(areaId).conflitti(areaId, selezioni, dimensioneId, valoreId, sezioneAccessoService.vincolo(areaId))
}