package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.Area
import com.lightningbi.lightning_engine.repository.AreaAccessoRepository
import com.lightningbi.lightning_engine.repository.RegistryRepository
import com.lightningbi.lightning_engine.repository.UserRoleRepository
import com.lightningbi.lightning_engine.view.CurrentUserHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import com.lightningbi.lightning_engine.repository.RoleRepository
import com.lightningbi.lightning_engine.repository.UserRepository

/**
 * Chi vede quale dataset. L'admin vede tutto; gli altri vedono i dataset
 * assegnati al loro ruolo più quelli assegnati a loro direttamente.
 * Un dataset nuovo non è assegnato a nessuno: lo vede solo l'admin.
 *
 * Va chiamato nei thread delle richieste Vaadin (l'utente sta nella sessione).
 */
@Service
class AccessoDatasetService(
    private val areaAccessoRepository: AreaAccessoRepository,
    private val registryRepository: RegistryRepository,
    private val userRoleRepository: UserRoleRepository,
    private val roleRepository: RoleRepository,
    private val userRepository: UserRepository,
    private val adminGuard: AdminGuard
) {
    /** I dataset che l'utente corrente può aprire. Senza utente: nessuno. */
    fun areeVisibili(): List<Area> {
        val utente = CurrentUserHolder.get() ?: return emptyList()
        val tutte = registryRepository.findAllAree()
        if (adminGuard.isAdmin()) return tutte
        val ruoloId = userRoleRepository.findRoleIdByUserId(utente.userId)
        val consentite = areaAccessoRepository.areeVisibili(utente.userId, ruoloId)
        return tutte.filter { it.id in consentite }
    }

    /** @throws SecurityException se l'utente corrente non può aprire il dataset */
    fun controlla(areaId: UUID) {
        if (areeVisibili().none { it.id == areaId }) {
            throw SecurityException("Non hai accesso a questo dataset")
        }
    }

    /** Ruoli e utenti tra cui scegliere nella finestra "Accessi" (id, nome). */
    fun ruoliDisponibili(): List<Pair<UUID, String>> {
        adminGuard.requireAdmin()
        return roleRepository.findAll().sortedBy { it.name.lowercase() }.map { it.id to it.name }
    }

    fun utentiDisponibili(): List<Pair<UUID, String>> {
        adminGuard.requireAdmin()
        return userRepository.findAll().sortedBy { it.username.lowercase() }.map { it.id to it.username }
    }

    fun ruoliDi(areaId: UUID): Set<UUID> {
        adminGuard.requireAdmin()
        return areaAccessoRepository.ruoliDi(areaId)
    }

    fun utentiDi(areaId: UUID): Set<UUID> {
        adminGuard.requireAdmin()
        return areaAccessoRepository.utentiDi(areaId)
    }

    @Transactional("postgresTransactionManager")
    fun imposta(areaId: UUID, ruoli: Set<UUID>, utenti: Set<UUID>) {
        adminGuard.requireAdmin()
        areaAccessoRepository.sostituisci(areaId, ruoli, utenti)
    }
}