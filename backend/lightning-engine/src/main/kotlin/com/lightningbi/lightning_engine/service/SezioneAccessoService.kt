package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.repository.CampoTestoRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.view.CurrentUserHolder
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Section access sull'azienda, come in Qlik: un utente non-admin vede SOLO i dati
 * della propria azienda, e il resto non esiste per lui. L'admin non ha vincoli.
 *
 * La selezione sul campo azienda è forzata qui, lato server: la vista non può
 * toglierla né cambiarla. Il codice azienda dell'utente si converte nell'id del
 * valore con la stessa regola dei dati (2, 002 e 2.0 sono lo stesso valore).
 *
 * Fail-closed: senza il campo azienda nel dataset, o senza azienda assegnata
 * all'utente, un non-admin non vede niente.
 *
 * Va chiamato nei thread delle richieste Vaadin (l'utente sta nella sessione).
 */
@Service
class SezioneAccessoService(
    private val registryRepository: RegistryRepository,
    private val symbolLookupService: SymbolLookupService,
    private val campoTestoRepository: CampoTestoRepository,
    private val adminGuard: AdminGuard,
    /** Nome del campo azienda nei dati (maiuscole comprese). */
    @Value("\${lbi.azienda.campo:CODICE_DITTA}") private val campoAzienda: String
) {
    /** Id mai assegnato a un valore (UInt32 massimo): una selezione su di esso non trova nessuna riga. */
    private val idNessuno = 4294967295L

    /** La selezione forzata di un non-admin: dimensione del campo azienda e id del suo valore. */
    data class Vincolo(val dimensioneId: UUID, val campo: String, val ids: Set<Long>)

    /** True se il dataset contiene il campo azienda. */
    fun haCampoAzienda(areaId: UUID): Boolean = dimensioneAzienda(areaId) != null

    /**
     * Il vincolo dell'utente corrente sul dataset, o null se non ne ha (admin).
     * @throws SecurityException se un non-admin non ha un'azienda assegnata o il dataset non ha il campo azienda
     */
    fun vincolo(areaId: UUID): Vincolo? {
        if (adminGuard.isAdmin()) return null
        val utente = CurrentUserHolder.get() ?: throw SecurityException("Utente non autenticato")
        val codice = utente.codiceDittaAssegnata
            ?: throw SecurityException("Nessuna azienda assegnata all'utente")
        val dimensioneId = dimensioneAzienda(areaId)
            ?: throw SecurityException("Il dataset non contiene il campo azienda")
        val colonna = Naming.column(campoAzienda)
        val testo = colonna in campoTestoRepository.tutti()
        val id = symbolLookupService.findIds(colonna, setOf(codice.toString()), testo)[codice.toString()]
        return Vincolo(dimensioneId, colonna, setOf(id ?: idNessuno))
    }

    /** Le selezioni dell'utente con quella dell'azienda forzata (sovrascrive qualunque altra sul campo). */
    fun applica(areaId: UUID, selezioni: Map<UUID, Set<Long>>): Map<UUID, Set<Long>> {
        val v = vincolo(areaId) ?: return selezioni
        return selezioni + (v.dimensioneId to v.ids)
    }

    private fun dimensioneAzienda(areaId: UUID): UUID? {
        val dims = registryRepository.findDimensioniByArea(areaId)
        val nomi = registryRepository.findDimensioniByIds(dims.map { it.dimensioneId }).associate { it.id to it.nome }
        val voluto = Naming.column(campoAzienda)
        return dims.firstOrNull { d -> nomi[d.dimensioneId]?.let { Naming.column(it) } == voluto }?.dimensioneId
    }
}