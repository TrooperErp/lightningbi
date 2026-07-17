package com.lightningbi.lightning_engine.config

import com.vaadin.flow.component.page.AppShellConfigurator
import com.vaadin.flow.component.page.Push
import com.vaadin.flow.theme.Theme
import com.vaadin.flow.server.PWA

@PWA(name = "LightningBI", shortName = "LightningBI")
@Push
@Theme("associa")
class AppShellConfig : AppShellConfigurator