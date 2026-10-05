package com.lightningbi.lightning_engine.model

import java.util.UUID

/**
 * Una MISURA di un'analisi: come in Qlik si scrive nell'oggetto (campo,
 * aggregazione, nome) e non si crea al caricamento. Appartiene a una sola
 * analisi ([PivotView]) e il suo id sta in PivotView.pivotValues.
 *
 * Si calcola sul proprio Fatti: [areaTabellaId] è l'occorrenza della tabella
 * nel dataset (AreaTabella.id) e [colonna] il nome FISICO della colonna. Solo
 * il conteggio di righe non ha bisogno di una colonna.
 *
 * @param tipo SUM, AVG, MIN e MAX richiedono un campo numerico, COUNT_DISTINCT
 *   qualunque campo, COUNT nessuno. La regola sul tipo del campo la controlla
 *   chi costruisce l'oggetto, perché dipende dai dati del dataset.
 */
data class MisuraAnalisi(
    val id: UUID,
    val pivotViewId: UUID,
    val nome: String,
    val tipo: TipoAggregazione,
    val areaTabellaId: UUID?,
    val colonna: String?,
    val posizione: Int = 0
) {
    init {
        require(nome.isNotBlank()) { "Il nome della misura non può essere vuoto" }
        require(nome == nome.trim()) { "Il nome della misura '$nome' ha spazi ai bordi" }
        if (tipo == TipoAggregazione.COUNT) {
            require(colonna == null) { "Il conteggio di righe non ha una colonna (misura '$nome')" }
            require(areaTabellaId != null) { "Il conteggio di righe richiede un Fatti (misura '$nome')" }
        } else {
            require(colonna != null && areaTabellaId != null) {
                "La misura '$nome' (${tipo.name}) richiede un campo"
            }
        }
    }

    /**
     * La misura come [AreaMetrica], che è ciò che il motore delle aggregazioni
     * legge. Non si salva: serve solo a interrogare. [areaId] è il dataset
     * dell'analisi.
     */
    fun comeMetrica(areaId: UUID): AreaMetrica = AreaMetrica(
        id = id,
        areaId = areaId,
        nome = nome,
        colonnaFisica = colonna,
        tipoAggregazione = tipo,
        tipoMetrica = TipoMetrica.AGGREGAZIONE_COLONNA,
        espressione = null,
        importedTableId = null,
        areaTabellaId = areaTabellaId
    )
}