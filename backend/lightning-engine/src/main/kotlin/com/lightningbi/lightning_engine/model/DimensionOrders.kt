package com.lightningbi.lightning_engine.service

/**
 * Dimensioni il cui ordinamento corretto è per valore grezzo (id
 * numerico), non per label alfabetica - es. mese_numero: "Aprile" prima
 * di "Agosto" alfabeticamente è sbagliato, va Gennaio...Dicembre.
 *
 * Indipendente da DimensionFormatters: una dimensione può avere l'uno,
 * l'altro, entrambi o nessuno dei due - tenerli in un solo registro
 * forzerebbe ad avere sempre entrambe le regole insieme anche quando ne
 * serve solo una.
 */
object DimensionSortOrders {
    private val naturalOrderColumns = setOf("mese_numero")

    fun usesNaturalOrder(colonnaFisica: String): Boolean = colonnaFisica in naturalOrderColumns
}