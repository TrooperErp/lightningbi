// FILE: src/main/kotlin/com/lightningbi/lightning_engine/model/AreaCampo.kt
package com.lightningbi.lightning_engine.model

import java.util.UUID

/**
 * Eccezione al nome di default di un campo, valida solo in UN dataset.
 *
 * Come in Qlik, ogni colonna di una tabella importata è un campo e il suo
 * nome di default è quello della colonna; lo stesso nome in due tabelle crea
 * un'associazione. Per rompere un loop si RINOMINA o si ESCLUDE un campo nel
 * dataset, senza toccare la tabella importata né gli altri dataset.
 *
 * L'[etichetta] è diversa: è solo il nome MOSTRATO all'utente (Dataset, Analisi,
 * Grafici), come l'etichetta di un oggetto in Qlik. Non cambia il nome del campo,
 * né le associazioni, né le dimensioni.
 *
 * Il campo appartiene a una OCCORRENZA di tabella nel dataset ([AreaTabella]).
 * Si salvano solo le colonne modificate.
 *
 * @param areaTabellaId occorrenza della tabella nel dataset (AreaTabella.id)
 * @param colonna nome FISICO della colonna (Naming.column)
 * @param nomeCampo nuovo nome del campo nel dataset, già normalizzato; null = nome di default
 * @param escluso true se il campo non fa parte del dataset
 * @param etichetta nome mostrato all'utente; null = quello di default
 *
 * Le combinazioni non valide sono rifiutate qui e dai CHECK delle migrazioni 025 e 041:
 * escluso con un nome o un'etichetta, nome o etichetta vuoti, riga senza effetto.
 */
data class AreaCampo(
    val areaTabellaId: UUID,
    val colonna: String,
    val nomeCampo: String? = null,
    val escluso: Boolean = false,
    val etichetta: String? = null
) {
    init {
        require(!(escluso && nomeCampo != null)) {
            "Un campo escluso non si rinomina (colonna '$colonna')"
        }
        require(!(escluso && etichetta != null)) {
            "Un campo escluso non ha etichetta (colonna '$colonna')"
        }
        require(nomeCampo == null || nomeCampo.isNotBlank()) {
            "Il nome del campo non può essere vuoto (colonna '$colonna')"
        }
        require(etichetta == null || etichetta.isNotBlank()) {
            "L'etichetta non può essere vuota (colonna '$colonna')"
        }
        require(escluso || nomeCampo != null || etichetta != null) {
            "Riga senza effetto per la colonna '$colonna': né esclusa, né rinominata, né con etichetta"
        }
    }
}