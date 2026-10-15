package com.lightning.bi.ui

import com.lightning.bi.model.UserPivotState
import com.lightning.bi.repository.RegistryRepository
import com.lightning.bi.repository.UserPivotStateRepository
import com.lightning.bi.service.FiltriService
import com.lightning.bi.service.StatoValore
import com.lightning.bi.service.ValoreFiltro
import com.lightning.bi.view.CronologiaSelezioni
import com.lightning.piattaforma.view.CurrentUserHolder
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Le selezioni dell'utente su un dataset, come in Qlik: valgono per tutti i fogli (Filtri,
 * analisi, grafici) e restano salvate. Unico punto in cui cambiano: clic sui valori, conflitti
 * con i valori grigi, blocchi, cronologia Indietro/Avanti, salvataggio.
 *
 * Una per pagina aperta. Si usa nel thread della pagina (legge l'utente dalla sessione).
 * Chi la usa si registra con [alCambio]: viene chiamato dopo ogni modifica, con [Cambio.DATI]
 * se sono cambiate le selezioni (bisogna ricalcolare) o [Cambio.BLOCCHI] se solo i blocchi.
 */
class StatoSelezioni(
    val areaId: UUID,
    private val filtriService: FiltriService,
    private val userPivotStateRepository: UserPivotStateRepository,
    private val registryRepository: RegistryRepository
) {
    enum class Cambio { DATI, BLOCCHI }

    private val log = LoggerFactory.getLogger(StatoSelezioni::class.java)

    private val correnti = mutableMapOf<UUID, Set<Long>>()
    private val blocchi = mutableSetOf<UUID>()
    private val ascoltatori = mutableListOf<(Cambio) -> Unit>()

    val selezioni: Map<UUID, Set<Long>> get() = correnti.toMap()
    val bloccati: Set<UUID> get() = blocchi.toSet()

    fun alCambio(ascoltatore: (Cambio) -> Unit) {
        ascoltatori += ascoltatore
    }


    // ================= Caricamento =================

    /**
     * Legge le selezioni salvate e i loro blocchi. Quelle su campi che il dataset non ha più
     * (campo tolto o rinominato) si scartano, e lo stato pulito si salva.
     */
    fun carica() {
        correnti.clear()
        blocchi.clear()
        val utente = CurrentUserHolder.get() ?: return
        val stato = userPivotStateRepository.find(utente.userId, areaId) ?: return
        val valide = registryRepository.findDimensioniByArea(areaId).map { it.dimensioneId }.toSet()
        stato.selections.forEach { (dimId, valori) ->
            if (valori.isNotEmpty() && dimId in valide) correnti[dimId] = valori
        }
        blocchi.addAll(stato.bloccati.filter { it in correnti })
        val orfane = stato.selections.filterValues { it.isNotEmpty() }.keys.any { it !in valide }
        if (orfane || stato.bloccati.any { it !in correnti }) salva()
    }

    // ================= Selezioni =================

    /**
     * Clic su un valore: lo aggiunge o lo toglie (più valori dello stesso campo in OR, campi
     * diversi in AND). Un valore grigio si può selezionare: come in Qlik le selezioni in
     * conflitto si tolgono, ma non quelle bloccate.
     *
     * @return null se fatto, altrimenti il motivo del rifiuto (da mostrare all'utente)
     */
    fun clic(dimId: UUID, valore: ValoreFiltro): String? {
        if (dimId in blocchi) return MSG_BLOCCATO
        val nuove = correnti.toMutableMap()
        val attuali = nuove[dimId].orEmpty()
        if (valore.id in attuali) {
            val resto = attuali - valore.id
            if (resto.isEmpty()) nuove.remove(dimId) else nuove[dimId] = resto
            applica(nuove)
            return null
        }
        if (valore.stato == StatoValore.ESCLUSO) {
            val daTogliere = try {
                filtriService.conflitti(areaId, correnti, dimId, valore.id)
            } catch (e: Exception) {
                log.warn("Calcolo dei conflitti fallito per la dimensione {}", dimId, e)
                emptySet()
            }
            if (daTogliere.any { it in blocchi }) return "Valore in conflitto con una selezione bloccata"
            daTogliere.forEach { nuove.remove(it) }
        }
        nuove[dimId] = attuali + valore.id
        applica(nuove)
        return null
    }

    /** Sostituisce la selezione di un campo (vuoto = la toglie). */
    fun imposta(dimId: UUID, valori: Set<Long>): String? {
        if (dimId in blocchi) return MSG_BLOCCATO
        val nuove = correnti.toMutableMap()
        if (valori.isEmpty()) nuove.remove(dimId) else nuove[dimId] = valori
        applica(nuove)
        return null
    }

    /**
     * "Seleziona tutti" i valori possibili di un campo, meno [tranne]: una lista esplicita, come in
     * Qlik (se poi cambiano le altre selezioni, la lista resta quella). Oltre [MAX_TUTTI] valori si
     * rifiuta: prima si restringe con altre selezioni.
     */
    fun tutti(dimId: UUID, tranne: Long? = null): String? {
        if (dimId in blocchi) return MSG_BLOCCATO
        val possibili = try {
            filtriService.idPossibili(areaId, correnti, dimId)
        } catch (e: Exception) {
            log.warn("Lettura dei valori possibili fallita per la dimensione {}", dimId, e)
            return "Impossibile leggere i valori del campo"
        }
        if (possibili.size > MAX_TUTTI) {
            return "Troppi valori possibili (${possibili.size}): restringi prima con altre selezioni"
        }
        val scelti = if (tranne == null) possibili else possibili - tranne
        if (scelti.isEmpty()) return "Nessun altro valore possibile da selezionare"
        return imposta(dimId, scelti)
    }

    /**
     * Clic su un grafico: ogni valore cliccato diventa la selezione del suo campo. Un secondo
     * clic sullo stesso valore, già unico selezionato, la toglie.
     */
    fun daGrafico(chiavi: Map<UUID, Long>): String? {
        if (chiavi.isEmpty()) return null
        if (chiavi.keys.any { it in blocchi }) return MSG_BLOCCATO
        val giaSelezionato = chiavi.all { (dimId, valore) -> correnti[dimId] == setOf(valore) }
        val nuove = correnti.toMutableMap()
        if (giaSelezionato) chiavi.keys.forEach { nuove.remove(it) }
        else chiavi.forEach { (dimId, valore) -> nuove[dimId] = setOf(valore) }
        applica(nuove)
        return null
    }

    /** Come "Cancella selezioni" di Qlik: tutte tranne le bloccate. */
    fun cancella() = applica(emptyMap())

    // ================= Cronologia =================

    fun puoIndietro() = CronologiaSelezioni.puoIndietro(areaId)
    fun puoAvanti() = CronologiaSelezioni.puoAvanti(areaId)

    fun indietro() {
        CronologiaSelezioni.indietro(areaId, correnti.toMap())?.let { applica(it, registra = false) }
    }

    fun avanti() {
        CronologiaSelezioni.avanti(areaId, correnti.toMap())?.let { applica(it, registra = false) }
    }

    // ================= Blocchi =================

    fun bloccaTutto() {
        if (correnti.isEmpty()) return
        blocchi.addAll(correnti.keys)
        dopoBlocchi()
    }

    fun sbloccaTutto() {
        if (blocchi.isEmpty()) return
        blocchi.clear()
        dopoBlocchi()
    }

    /** Lucchetto di un campo: blocca o sblocca (solo un campo con una selezione si blocca). */
    fun cambiaBlocco(dimId: UUID) {
        if (!blocchi.remove(dimId) && dimId in correnti) blocchi.add(dimId)
        dopoBlocchi()
    }

    // ================= Interni =================

    /** Unico punto in cui cambiano le selezioni: le bloccate restano com'erano. */
    private fun applica(nuove: Map<UUID, Set<Long>>, registra: Boolean = true) {
        val conBlocchi = nuove.toMutableMap().apply { blocchi.forEach { d -> correnti[d]?.let { put(d, it) } } }
        val pulite = conBlocchi.filterValues { it.isNotEmpty() }
        if (pulite == correnti.toMap()) {
            notifica(Cambio.BLOCCHI)
            return
        }
        if (registra) CronologiaSelezioni.registra(areaId, correnti.toMap())
        correnti.clear()
        correnti.putAll(pulite)
        blocchi.retainAll(correnti.keys)
        salva()
        notifica(Cambio.DATI)
    }

    private fun dopoBlocchi() {
        salva()
        notifica(Cambio.BLOCCHI)
    }

    /** Salva selezioni e blocchi; la vista attiva dell'utente non cambia. */
    private fun salva() {
        val utente = CurrentUserHolder.get() ?: return
        val attuale = userPivotStateRepository.find(utente.userId, areaId)
        val sel = correnti.toMap()
        val blo = blocchi.filter { it in correnti }.toSet()
        userPivotStateRepository.save(
            attuale?.copy(selections = sel, bloccati = blo, updatedAt = Instant.now())
                ?: UserPivotState(utente.userId, areaId, null, sel, blo)
        )
    }

    private fun notifica(cambio: Cambio) {
        ascoltatori.toList().forEach { it(cambio) }
    }

    private companion object {
        const val MSG_BLOCCATO = "La selezione di questo campo è bloccata: sbloccala per cambiarla"
        const val MAX_TUTTI = 500
    }
}