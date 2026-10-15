package com.lightning.bi.ui

import java.util.UUID

/**
 * Le rotte delle pagine della BI, in un posto solo. Oggi alcune puntano ancora alle pagine
 * vecchie: quando una pagina nuova la sostituisce, si cambia solo qui.
 */
object RotteBi {
    const val HOME = "bi"
    fun filtri(areaId: UUID) = "bi/filtri/$areaId"
    fun analisi(areaId: UUID, vistaId: UUID) = "bi/analisi/$areaId,$vistaId"
    fun grafici(areaId: UUID, vistaId: UUID) = "bi/grafici/$areaId,$vistaId"
}