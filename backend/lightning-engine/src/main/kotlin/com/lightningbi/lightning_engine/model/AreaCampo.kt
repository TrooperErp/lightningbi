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
 * Il campo appartiene a una OCCORRENZA di tabella nel dataset
 * ([AreaTabella]), non alla tabella importata: se la stessa tabella è
 * caricata due volte, ogni copia ha i propri campi, rinominabili e
 * escludibili in modo indipendente.
 *
 * Si salvano solo le colonne modificate: una colonna senza AreaCampo usa il
 * nome di default ed è inclusa.
 *
 * @param areaTabellaId occorrenza della tabella nel dataset (AreaTabella.id)
 * @param colonna nome FISICO della colonna (Naming.column), come
 *   AreaDimensione.colonnaFisica
 * @param nomeCampo nuovo nome del campo nel dataset, già normalizzato da chi
 *   costruisce l'oggetto (Naming.column); null se il campo è escluso
 * @param escluso true se il campo non fa parte del dataset
 *
 * Le combinazioni non valide sono rifiutate qui e dai CHECK della migrazione
 * 025: escluso con un nome, nome vuoto, riga senza effetto.
 */
data class AreaCampo(
    val areaTabellaId: UUID,
    val colonna: String,
    val nomeCampo: String? = null,
    val escluso: Boolean = false
) {
    init {
        require(!(escluso && nomeCampo != null)) {
            "Un campo escluso non si rinomina (colonna '$colonna')"
        }
        require(nomeCampo == null || nomeCampo.isNotBlank()) {
            "Il nome del campo non può essere vuoto (colonna '$colonna')"
        }
        require(escluso || nomeCampo != null) {
            "Riga senza effetto per la colonna '$colonna': né esclusa né rinominata"
        }
    }
}