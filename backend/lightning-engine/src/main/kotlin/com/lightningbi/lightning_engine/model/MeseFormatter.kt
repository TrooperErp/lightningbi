package com.lightningbi.lightning_engine.model

enum class MeseItaliano(val numero: Int, val label: String) {
    GENNAIO(1, "Gennaio"),
    FEBBRAIO(2, "Febbraio"),
    MARZO(3, "Marzo"),
    APRILE(4, "Aprile"),
    MAGGIO(5, "Maggio"),
    GIUGNO(6, "Giugno"),
    LUGLIO(7, "Luglio"),
    AGOSTO(8, "Agosto"),
    SETTEMBRE(9, "Settembre"),
    OTTOBRE(10, "Ottobre"),
    NOVEMBRE(11, "Novembre"),
    DICEMBRE(12, "Dicembre");

    companion object {
        fun labelFor(numero: Long): String =
            entries.find { it.numero == numero.toInt() }?.label ?: "#$numero"
    }
}