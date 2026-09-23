package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.PivotView
import com.lightningbi.lightning_engine.repository.PivotViewRepository
import com.lightningbi.lightning_engine.repository.UserPivotStateRepository
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Orchestrazione delle PivotView di un'Area: creazione pigra della prima
 * vista, crea/rinomina/elimina, e la nozione di "vista attiva" per un
 * dato utente (letta/scritta su UserPivotState.activeViewId).
 *
 * Le PivotView sono condivise per l'intera Area (come i fogli di
 * un'app Qlik): chiunque apra l'Analisi vede lo stesso elenco. Le
 * selezioni (verde/bianco/grigio) restano invece uniche e condivise
 * trasversalmente su tutte le viste della stessa Area - qui non se ne
 * occupa nessun metodo, vivono in UserPivotState.selections e non
 * cambiano al cambiare di vista attiva.
 */
@Service
class PivotViewService(
    private val pivotViewRepository: PivotViewRepository,
    private val userPivotStateRepository: UserPivotStateRepository
) {

    /**
     * Vista attiva per l'utente su quest'area: se non esiste ancora
     * nessuna PivotView per l'area, ne crea una di default ("Vista 1"),
     * recuperando - se presente - la struttura salvata col vecchio
     * formato mono-vista (findLegacyStructure), per non far perdere il
     * lavoro già fatto dall'utente prima dell'introduzione delle viste
     * multiple. Se esistono già viste ma l'utente non ne ha ancora una
     * attiva (activeViewId null, es. primo accesso di un utente diverso
     * da chi le ha create), viene attivata la prima per posizione.
     */
    fun ensureActiveView(userId: UUID, areaId: UUID): PivotView {
        var views = pivotViewRepository.findByArea(areaId)

        if (views.isEmpty()) {
            val legacy = userPivotStateRepository.findLegacyStructure(userId, areaId)
            val defaultView = PivotView(
                id = UUID.randomUUID(),
                areaId = areaId,
                nome = "Vista 1",
                pivotRows = legacy?.first ?: emptyList(),
                pivotColumns = legacy?.second ?: emptyList(),
                pivotValues = legacy?.third ?: emptyList(),
                posizione = 0
            )
            pivotViewRepository.save(defaultView)
            views = listOf(defaultView)
        }

        val currentState = userPivotStateRepository.find(userId, areaId)
        val activeView = currentState?.activeViewId?.let { id -> views.find { it.id == id } }
        if (activeView != null) return activeView

        val fallback = views.first()
        setActiveView(userId, areaId, fallback.id)
        return fallback
    }

    fun findByArea(areaId: UUID): List<PivotView> = pivotViewRepository.findByArea(areaId)

    fun findById(id: UUID): PivotView? = pivotViewRepository.findById(id)

    /** Imposta la vista attiva per l'utente, preservando le selezioni correnti (uniche, condivise su tutte le viste). */
    fun setActiveView(userId: UUID, areaId: UUID, viewId: UUID) {
        val current = userPivotStateRepository.find(userId, areaId)
        userPivotStateRepository.save(
            com.lightningbi.lightning_engine.model.UserPivotState(
                userId = userId,
                areaId = areaId,
                activeViewId = viewId,
                selections = current?.selections ?: emptyMap()
            )
        )
    }

    /**
     * Crea una nuova vista vuota, la posiziona in coda, e la rende
     * subito attiva per l'utente che l'ha creata.
     */
    fun createView(userId: UUID, areaId: UUID, nome: String): PivotView {
        val view = PivotView(
            id = UUID.randomUUID(),
            areaId = areaId,
            nome = nome.ifBlank { "Nuova vista" },
            posizione = pivotViewRepository.nextPosizione(areaId)
        )
        pivotViewRepository.save(view)
        setActiveView(userId, areaId, view.id)
        return view
    }

    fun renameView(view: PivotView, nuovoNome: String): PivotView {
        val updated = view.copy(nome = nuovoNome.ifBlank { view.nome })
        pivotViewRepository.update(updated)
        return updated
    }

    /**
     * Aggiorna Righe/Colonne/Valori di una vista esistente (salvataggio
     * immediato ad ogni modifica del PivotPanel, non su richiesta
     * esplicita - stesso comportamento Qlik: il foglio riflette subito
     * cosa "vede di là" l'utente).
     */
    fun updatePivot(view: PivotView, pivotRows: List<UUID>, pivotColumns: List<UUID>, pivotValues: List<UUID>): PivotView {
        val updated = view.copy(pivotRows = pivotRows, pivotColumns = pivotColumns, pivotValues = pivotValues)
        pivotViewRepository.update(updated)
        return updated
    }

    /**
     * Elimina una vista. Non permette di eliminare l'ultima vista
     * rimasta di un'area (un'Area deve avere sempre almeno una vista,
     * altrimenti la pagina Analisi non avrebbe nulla da mostrare).
     * Se la vista eliminata era quella attiva per qualche utente, quella
     * situazione si risolve da sola al prossimo ensureActiveView() di
     * quell'utente (activeViewId punterebbe a un id inesistente, quindi
     * la ricerca fallisce e si ricade sulla prima vista disponibile).
     */
    fun deleteView(areaId: UUID, viewId: UUID): Boolean {
        val views = pivotViewRepository.findByArea(areaId)
        if (views.size <= 1) return false
        return pivotViewRepository.delete(viewId)
    }
}