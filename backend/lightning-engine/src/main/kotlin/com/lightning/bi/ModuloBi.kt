package com.lightning.bi

import com.lightning.piattaforma.ui.ModuloLightning
import com.vaadin.flow.component.icon.VaadinIcon
import org.springframework.stereotype.Component

/** Il modulo BI nella shell di Lightning. */
@Component
class ModuloBi : ModuloLightning {
    override val id = "bi"
    override val nome = "BI"
    override val icona = VaadinIcon.BAR_CHART
    override val ordine = 10
}