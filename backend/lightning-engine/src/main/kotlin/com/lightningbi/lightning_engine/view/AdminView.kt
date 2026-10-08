package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.Role
import com.lightningbi.lightning_engine.model.User
import com.lightningbi.lightning_engine.repository.PermissionRepository
import com.lightningbi.lightning_engine.repository.RolePermissionRepository
import com.lightningbi.lightning_engine.repository.RoleRepository
import com.lightningbi.lightning_engine.service.AuthService
import com.lightningbi.lightning_engine.service.PermissionCheckService
import com.lightningbi.lightning_engine.service.UserService
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dependency.Uses
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.icon.Icon
import com.vaadin.flow.component.icon.VaadinIcon
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
import com.vaadin.flow.component.textfield.IntegerField
import com.vaadin.flow.component.textfield.PasswordField
import com.vaadin.flow.component.textfield.TextField
import com.vaadin.flow.router.BeforeEnterEvent
import com.vaadin.flow.router.BeforeEnterObserver
import com.vaadin.flow.router.Route
import com.vaadin.flow.server.VaadinServletRequest
import java.util.UUID

/**
 * Console di amministrazione: gestione utenti e gestione ruoli/permessi.
 * Accesso riservato a chi ha il permesso MANAGE_USERS (non a un nome di
 * ruolo specifico: qualunque ruolo, presente o futuro, con quel permesso
 * può entrare). Il controllo si applica anche qui perché l'URL /admin resta
 * raggiungibile a mano anche se la voce di menu è nascosta.
 *
 * Azienda: ogni utente non amministratore ha una sola azienda (section
 * access); l'amministratore non ne ha e vede tutto.
 */
@Route("admin")
@Uses(Icon::class)
class AdminView(
    private val roleRepository: RoleRepository,
    private val permissionRepository: PermissionRepository,
    private val rolePermissionRepository: RolePermissionRepository,
    private val userService: UserService,
    private val permissionCheckService: PermissionCheckService,
    private val authService: AuthService
) : VerticalLayout(), BeforeEnterObserver {

    private val usersGrid = Grid<User>()
    private val rolesGrid = Grid<Role>()
    private var paginaCostruita = false

    override fun beforeEnter(event: BeforeEnterEvent) {
        val user = CurrentUserHolder.get()
        if (user == null || !permissionCheckService.hasPermission(user.roleName, "MANAGE_USERS")) {
            event.forwardTo(DatasetFiltriView::class.java)
            return
        }
        if (!paginaCostruita) {
            buildPage()
            paginaCostruita = true
        }
    }

    private fun buildPage() {
        removeAll()
        setSizeFull()
        isPadding = false

        val content = buildContent()

        val menuGroups = listOf(
            LbiSidebarMenu.MenuGroup(
                label = "Analisi",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Torna alle analisi", icon = VaadinIcon.ARROW_LEFT) {
                        getUI().ifPresent { it.navigate(DatasetFiltriView::class.java) }
                    }
                ),
                icon = VaadinIcon.CHART
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Amministrazione",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Tabelle importate", icon = VaadinIcon.DATABASE) {
                        getUI().ifPresent { it.navigate(TabelleImportateView::class.java) }
                    },
                    LbiSidebarMenu.MenuEntry("Gestione utenti", icon = VaadinIcon.USERS) { }
                ),
                active = true,
                icon = VaadinIcon.COG
            )
        )

        val shell = LbiAppShell(menuGroups, content, authService)
        add(shell)
        setFlexGrow(1.0, shell)
    }

    private fun buildContent(): Component {
        reloadUsers()
        reloadRoles()

        usersGrid.apply {
            setWidthFull()
            height = "320px"
            addColumn { it.username }.setHeader("Username").setAutoWidth(true)
            addColumn { it.email }.setHeader("Email").setAutoWidth(true)
            addColumn { u -> nomeRuolo(u) }.setHeader("Ruolo").setAutoWidth(true)
            addColumn { it.codiceDittaAssegnata?.toString() ?: "Tutte" }.setHeader("Azienda").setAutoWidth(true)
            addColumn { if (it.active) "Attivo" else "Disattivato" }.setHeader("Stato").setAutoWidth(true)
            addComponentColumn { user ->
                val modifica = Button("Modifica") { openEditUserDialog(user) }.apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL)
                }
                val stato = if (user.active) {
                    Button("Disattiva") { confirmDeactivateUser(user) }.apply {
                        isEnabled = user.id != CurrentUserHolder.get()?.userId
                        addThemeVariants(ButtonVariant.LUMO_SMALL)
                    }
                } else {
                    Button("Riattiva") { riattiva(user) }.apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL)
                    }
                }
                HorizontalLayout(modifica, stato).apply { isPadding = false }
            }.setHeader("")
        }

        rolesGrid.apply {
            setWidthFull()
            height = "220px"
            addColumn { it.name }.setHeader("Ruolo").setAutoWidth(true)
            addColumn { it.description }.setHeader("Descrizione").setAutoWidth(true)
            addColumn { rolePermissionRepository.findPermissionIdsByRoleId(it.id).size }
                .setHeader("N° permessi").setAutoWidth(true)
            addComponentColumn { role ->
                Button("Permessi") { openPermissionsDialog(role) }.apply {
                    addThemeVariants(ButtonVariant.LUMO_SMALL)
                }
            }.setHeader("")
        }

        val addUserButton = Button("+ Nuovo utente") { openCreateUserDialog() }
            .apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        return VerticalLayout(
            Span("Amministrazione").apply { className = "lbi-section-title" },

            Span("Utenti").apply { className = "lbi-section-title" },
            usersGrid,
            addUserButton,

            Span("Ruoli e permessi").apply { className = "lbi-section-title" },
            rolesGrid
        ).apply {
            className = "lbi-center"
            isPadding = true
            setSizeFull()
        }
    }

    private fun nomeRuolo(u: User): String =
        userService.roleIdOf(u.id)?.let { roleRepository.findById(it)?.name } ?: "—"

    private fun reloadUsers() {
        usersGrid.setItems(userService.listUsers().sortedBy { it.username.lowercase() })
    }

    private fun reloadRoles() {
        rolesGrid.setItems(roleRepository.findAll())
    }

    /** Mostra all'admin il motivo di un rifiuto del servizio (input non valido o operazione non consentita). */
    private fun segnala(e: Exception) {
        val messaggio = when (e) {
            is IllegalArgumentException, is IllegalStateException -> e.message ?: "Operazione non riuscita"
            else -> "Errore: ${e.message}"
        }
        Notification.show(messaggio, 6000, Notification.Position.MIDDLE)
    }

    private fun campoAzienda(valore: Int?) = IntegerField("Azienda").apply {
        setWidthFull()
        isClearButtonVisible = true
        helperText = "Obbligatoria per gli utenti non amministratori. Vuota = vede tutte le aziende."
        value = valore
    }

    // ================= Dialog: nuovo utente =================

    private fun openCreateUserDialog() {
        val dialog = Dialog().apply {
            className = "lbi-wizard-dialog"
            headerTitle = "Nuovo utente"
            width = "440px"
        }

        val usernameField = TextField("Username").apply { setWidthFull() }
        val emailField = TextField("Email").apply { setWidthFull() }
        val passwordField = PasswordField("Password temporanea").apply {
            setWidthFull()
            helperText = "Almeno 8 caratteri"
        }
        val roleCombo = ComboBox<Role>("Ruolo").apply {
            setItems(roleRepository.findAll())
            setItemLabelGenerator { it.name }
            setWidthFull()
        }
        val aziendaField = campoAzienda(null)

        dialog.add(
            VerticalLayout(usernameField, emailField, passwordField, roleCombo, aziendaField).apply { isPadding = false }
        )

        val cancelButton = Button("Annulla") { dialog.close() }
        val createButton = Button("Crea") {
            val username = usernameField.value?.trim()
            val email = emailField.value?.trim()
            val password = passwordField.value
            val role = roleCombo.value

            if (username.isNullOrBlank() || email.isNullOrBlank() || password.isNullOrBlank() || role == null) {
                Notification.show("Compila username, email, password e ruolo")
                return@Button
            }

            val currentUser = CurrentUserHolder.get() ?: return@Button
            val request = VaadinServletRequest.getCurrent().httpServletRequest

            try {
                userService.createUser(
                    username, email, password, role.id, currentUser.userId, request.remoteAddr, aziendaField.value
                )
                reloadUsers()
                dialog.close()
                Notification.show("Utente creato", 3000, Notification.Position.BOTTOM_END)
            } catch (e: Exception) {
                segnala(e)
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        dialog.footer.add(cancelButton, createButton)
        dialog.open()
    }

    // ================= Disattivazione / riattivazione =================

    private fun confirmDeactivateUser(user: User) {
        val dialog = Dialog().apply {
            headerTitle = "Disattivare \"${user.username}\"?"
            width = "440px"
        }
        dialog.add(
            Span(
                "L'utente non potrà più accedere e le sue sessioni aperte si chiudono subito. " +
                        "Non è una cancellazione: lo storico (audit, analisi salvate) resta intatto."
            )
        )

        val cancelButton = Button("Annulla") { dialog.close() }
        val confirmButton = Button("Disattiva") {
            val currentUser = CurrentUserHolder.get() ?: return@Button
            val request = VaadinServletRequest.getCurrent().httpServletRequest
            try {
                userService.deactivateUser(user.id, currentUser.userId, request.remoteAddr)
                reloadUsers()
                dialog.close()
            } catch (e: Exception) {
                segnala(e)
            }
        }
        dialog.footer.add(cancelButton, confirmButton)
        dialog.open()
    }

    private fun riattiva(user: User) {
        val currentUser = CurrentUserHolder.get() ?: return
        val request = VaadinServletRequest.getCurrent().httpServletRequest
        try {
            userService.activateUser(user.id, currentUser.userId, request.remoteAddr)
            reloadUsers()
            Notification.show("Utente riattivato", 3000, Notification.Position.BOTTOM_END)
        } catch (e: Exception) {
            segnala(e)
        }
    }

    // ================= Dialog: permessi di un ruolo =================

    /**
     * Una checkbox per permesso, raggruppate per categoria. Salvare sostituisce
     * l'intero set di permessi del ruolo (RolePermissionRepository.replacePermissions).
     */
    private fun openPermissionsDialog(role: Role) {
        val dialog = Dialog().apply {
            className = "lbi-wizard-dialog"
            headerTitle = "Permessi di \"${role.name}\""
            width = "480px"
        }

        val allPermissions = permissionRepository.findAll()
        val assignedIds = rolePermissionRepository.findPermissionIdsByRoleId(role.id).toSet()
        val checkboxes = mutableMapOf<UUID, Checkbox>()

        val content = VerticalLayout().apply { isPadding = false }

        allPermissions.groupBy { it.category }.forEach { (category, permissions) ->
            content.add(Span(category).apply { className = "lbi-section-title" })
            permissions.forEach { permission ->
                val checkbox = Checkbox(permission.description).apply {
                    value = permission.id in assignedIds
                }
                checkboxes[permission.id] = checkbox
                content.add(checkbox)
            }
        }

        dialog.add(content)

        val cancelButton = Button("Annulla") { dialog.close() }
        val saveButton = Button("Salva") {
            val selectedIds = checkboxes.filterValues { it.value }.keys.toList()
            rolePermissionRepository.replacePermissions(role.id, selectedIds)
            reloadRoles()
            dialog.close()
            Notification.show("Permessi aggiornati", 3000, Notification.Position.BOTTOM_END)
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        dialog.footer.add(cancelButton, saveButton)
        dialog.open()
    }

    // ================= Dialog: modifica utente =================

    private fun openEditUserDialog(user: User) {
        val dialog = Dialog().apply {
            className = "lbi-wizard-dialog"
            headerTitle = "Modifica \"${user.username}\""
            width = "460px"
        }

        val ruoli = roleRepository.findAll()
        val ruoloAttuale = userService.roleIdOf(user.id)

        val emailField = TextField("Email").apply {
            setWidthFull()
            value = user.email
        }
        val roleCombo = ComboBox<Role>("Ruolo").apply {
            setItems(ruoli)
            setItemLabelGenerator { it.name }
            setWidthFull()
            value = ruoli.firstOrNull { it.id == ruoloAttuale }
        }
        val aziendaField = campoAzienda(user.codiceDittaAssegnata)

        val passwordField = PasswordField("Nuova password").apply {
            setWidthFull()
            helperText = "Almeno 8 caratteri. Chiude le sessioni aperte dell'utente."
        }
        val resetButton = Button("Reimposta password") {
            val nuova = passwordField.value
            if (nuova.isNullOrBlank()) {
                Notification.show("Scrivi la nuova password")
                return@Button
            }
            val currentUser = CurrentUserHolder.get() ?: return@Button
            val request = VaadinServletRequest.getCurrent().httpServletRequest
            try {
                userService.resetPassword(user.id, nuova, currentUser.userId, request.remoteAddr)
                passwordField.clear()
                Notification.show("Password reimpostata", 3000, Notification.Position.BOTTOM_END)
            } catch (e: Exception) {
                segnala(e)
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_SMALL) }

        dialog.add(
            VerticalLayout(
                emailField, roleCombo, aziendaField,
                Span("Password").apply { className = "lbi-section-title" },
                passwordField, resetButton
            ).apply { isPadding = false }
        )

        val cancelButton = Button("Chiudi") { dialog.close() }
        val saveButton = Button("Salva") {
            val ruolo = roleCombo.value
            if (ruolo == null) {
                Notification.show("Scegli il ruolo")
                return@Button
            }
            val currentUser = CurrentUserHolder.get() ?: return@Button
            val request = VaadinServletRequest.getCurrent().httpServletRequest
            try {
                userService.updateEmail(user.id, emailField.value ?: "", currentUser.userId, request.remoteAddr)
                userService.changeRole(user.id, ruolo.id, aziendaField.value, currentUser.userId, request.remoteAddr)
                reloadUsers()
                dialog.close()
                Notification.show("Utente aggiornato", 3000, Notification.Position.BOTTOM_END)
            } catch (e: Exception) {
                segnala(e)
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        dialog.footer.add(cancelButton, saveButton)
        dialog.open()
    }
}