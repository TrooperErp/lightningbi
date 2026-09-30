package com.lightningbi.lightning_engine.service

/** Una tabella o view di una sorgente, come la restituisce un connettore. */
data class TableInfo(val schema: String, val name: String)

/** Una colonna di una tabella sorgente: nome e tipo così come li dichiara la sorgente. */
data class ColumnInfo(val name: String, val typeName: String)