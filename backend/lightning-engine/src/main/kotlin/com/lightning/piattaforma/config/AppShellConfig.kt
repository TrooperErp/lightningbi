package com.lightning.piattaforma.config

import com.vaadin.flow.component.dependency.StyleSheet
import com.vaadin.flow.component.page.AppShellConfigurator
import com.vaadin.flow.component.page.ColorScheme
import com.vaadin.flow.component.page.Push
import com.vaadin.flow.server.PWA
import com.vaadin.flow.theme.aura.Aura

/**
 * Guscio comune di Lightning: tema Aura, poi i token della piattaforma, poi il ponte
 * delle pagine vecchie (da togliere quando sono state tutte rifatte). L'ordine conta:
 * Aura deve caricarsi per primo.
 */
@PWA(name = "Lightning", shortName = "Lightning")
@Push
@ColorScheme(ColorScheme.Value.LIGHT)
@StyleSheet(Aura.STYLESHEET)
@StyleSheet("styles/lightning.css")
@StyleSheet("styles/legacy.css")
class AppShellConfig : AppShellConfigurator