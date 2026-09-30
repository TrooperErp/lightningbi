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
    /**
     * Confronto "naturale" tra etichette: le sequenze di cifre si confrontano
     * come numeri (9 < 10), il resto carattere per carattere senza distinguere
     * maiuscole e minuscole.
     */
    fun confrontoNaturale(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            if (a[i].isDigit() && b[j].isDigit()) {
                var ie = i
                while (ie < a.length && a[ie].isDigit()) ie++
                var je = j
                while (je < b.length && b[je].isDigit()) je++
                val na = a.substring(i, ie).trimStart('0')
                val nb = b.substring(j, je).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return c
                i = ie
                j = je
            } else {
                val c = a[i].lowercaseChar().compareTo(b[j].lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}