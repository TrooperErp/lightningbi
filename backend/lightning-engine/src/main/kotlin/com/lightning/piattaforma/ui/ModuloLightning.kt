package com.lightning.piattaforma.ui

import com.vaadin.flow.component.icon.VaadinIcon

/**
 * Un modulo della piattaforma (BI, APS, Forecaster, Cashflow…). Ogni modulo è un bean:
 * la shell li trova da sola e li mette nella barra a icone, in ordine.
 *
 * [id] è tecnico e stabile (entra in URL e permessi); [nome] è solo l'etichetta e si può
 * cambiare quando si vuole. Un modulo non [attivo] si vede ma non si apre ("in arrivo").
 */
interface ModuloLightning {
    val id: String
    val nome: String
    val icona: VaadinIcon
    val ordine: Int
    val attivo: Boolean get() = true

    /** Rotta da aprire cliccando il modulo, senza "/" iniziale (es. "bi"). */
    val rotta: String get() = id
}

/** Moduli annunciati ma non ancora disponibili. Si tolgono da qui quando arrivano davvero. */
enum class ModuloInArrivo(
    override val id: String,
    override val nome: String,
    override val icona: VaadinIcon,
    override val ordine: Int
) : ModuloLightning {
    APS("aps", "APS", VaadinIcon.TASKS, 20),
    FORECASTER("forecast", "Forecaster", VaadinIcon.TRENDING_UP, 30),
    CASHFLOW("cashflow", "Cashflow", VaadinIcon.EXCHANGE, 40);

    override val attivo: Boolean get() = false
}