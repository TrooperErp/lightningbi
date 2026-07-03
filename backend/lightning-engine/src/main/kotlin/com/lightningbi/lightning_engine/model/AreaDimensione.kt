package com.lightningbi.lightning_engine.model

import java.util.UUID

data class Area(
    val id: UUID,
    val nome: String,
    val tabellaFisica: String
)

data class Dimensione(
    val id: UUID,
    val nome: String,
    val tipo: String,
    val conformata: Boolean,
    val tabellaDimFisica: String?,
    val colonnaChiave: String?
)

data class AreaDimensione(
    val areaId: UUID,
    val dimensioneId: UUID,
    val colonnaFisica: String,
    val obbligatoria: Boolean,
    val cardinalitaStimata: Long?
)

data class AreaMetrica(
    val id: UUID,
    val areaId: UUID,
    val nome: String,
    val colonnaFisica: String,
    val tipoAggregazione: String
)