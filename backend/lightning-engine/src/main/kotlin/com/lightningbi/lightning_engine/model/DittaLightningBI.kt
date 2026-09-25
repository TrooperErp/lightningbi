package com.lightningbi.lightning_engine.model

enum class DittaLightningBI(val codice: Int, val label: String) {
    SEM(1, "SEM"),
    UNIVERSAL_PAINT(2, "Universal Paint"),
    SEI_S(3, "6S"),
    LDT(4, "LDT"),
    IMET(5, "IMET");

    companion object {
        fun labelFor(codice: Long): String =
            entries.find { it.codice == codice.toInt() }?.label ?: "#$codice"
    }
}