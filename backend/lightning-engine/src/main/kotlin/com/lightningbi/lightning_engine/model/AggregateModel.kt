package com.lightningbi.lightning_engine.model

import java.math.BigDecimal
import java.util.UUID

data class AggregateRequest(
    val areaId: UUID,
    val selections: Map<UUID, Set<Long>>,
    val groupBy: List<UUID> = emptyList()
)

data class AggregateRow(
    val groupKeys: Map<UUID, Long>,
    val values: Map<String, BigDecimal>
)

data class AggregateResult(
    val rows: List<AggregateRow>,
    val truncated: Boolean
)