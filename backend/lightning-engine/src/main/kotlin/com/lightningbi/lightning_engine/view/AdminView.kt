package com.lightningbi.lightning_engine.view

import com.lightningbi.lightning_engine.model.Role
import com.lightningbi.lightning_engine.model.User
import com.lightningbi.lightning_engine.repository.PermissionRepository
import com.lightningbi.lightning_engine.repository.RolePermissionRepository
import com.lightningbi.lightning_engine.repository.RoleRepository
import com.lightningbi.lightning_engine.repository.UserRepository
import com.lightningbi.lightning_engine.repository.UserRoleRepository
import com.lightningbi.lightning_engine.service.PermissionCheckService
import com.lightningbi.lightning_engine.service.UserService
import com.vaadin.flow.component.Component
import com.vaadin.flow.component.button.Button
import com.vaadin.flow.component.button.ButtonVariant
import com.vaadin.flow.component.checkbox.Checkbox
import com.vaadin.flow.component.combobox.ComboBox
import com.vaadin.flow.component.dialog.Dialog
import com.vaadin.flow.component.grid.Grid
import com.vaadin.flow.component.html.Span
import com.vaadin.flow.component.notification.Notification
import com.vaadin.flow.component.orderedlayout.HorizontalLayout
import com.vaadin.flow.component.orderedlayout.VerticalLayout
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
 * può entrare - coerente col modello a permessi granulari già esistente
 * nello schema, invece di un controllo rigido su "ADMIN"). Lo stesso
 * controllo decide anche se la voce "Amministrazione" compare nel menu
 * delle altre view (vedi AssociativeExplorerView.buildMenuGroups): qui
 * si applica di nuovo perché l'URL /admin resta raggiungibile a mano
 * anche se la voce di menu è nascosta.
 */
@Route("admin")
class AdminView(
    private val userRepository: UserRepository,
    private val roleRepository: RoleRepository,
    private val permissionRepository: PermissionRepository,
    private val rolePermissionRepository: RolePermissionRepository,
    private val userRoleRepository: UserRoleRepository,
    private val userService: UserService,
    private val permissionCheckService: PermissionCheckService,
    private val passwordEncoder: org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
) : VerticalLayout(), BeforeEnterObserver {

    private val usersGrid = Grid<User>()
    private val rolesGrid = Grid<Role>()


    override fun beforeEnter(event: BeforeEnterEvent) {
        val currentUser = CurrentUserHolder.get()
        if (currentUser == null) {
            event.forwardTo(LoginView::class.java)
            return
        }
        if (!permissionCheckService.hasPermission(currentUser.roleName, "MANAGE_USERS")) {
            Notification.show("Accesso non autorizzato", 4000, Notification.Position.MIDDLE)
            event.forwardTo(AssociativeExplorerView::class.java)
            return
        }
        buildPage()
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
                    LbiSidebarMenu.MenuEntry("Torna alle analisi") {
                        getUI().ifPresent { it.navigate(AssociativeExplorerView::class.java) }
                    }
                )
            ),
            LbiSidebarMenu.MenuGroup(
                label = "Amministrazione",
                entries = listOf(
                    LbiSidebarMenu.MenuEntry("Gestione utenti") { }
                ),
                active = true
            )
        )

        val shell = LbiAppShell(menuGroups, content)
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
            addColumn { u -> userRoleRepository.findRoleIdByUserId(u.id)?.let { roleRepository.findById(it)?.name } ?: "—" }
                .setHeader("Ruolo").setAutoWidth(true)
            addColumn { if (it.active) "Attivo" else "Disattivato" }.setHeader("Stato").setAutoWidth(true)
            addComponentColumn { user ->
                HorizontalLayout(
                    Button("Modifica") { openEditUserDialog(user) }.apply {
                        addThemeVariants(ButtonVariant.LUMO_SMALL)
                    },
                    Button("Disattiva") { confirmDeactivateUser(user) }.apply {
                        isEnabled = user.active
                        addThemeVariants(ButtonVariant.LUMO_SMALL)
                    }
                ).apply { isPadding = false }
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

    private fun reloadUsers() {
        usersGrid.setItems(userRepository.findAll())
    }

    private fun reloadRoles() {
        rolesGrid.setItems(roleRepository.findAll())
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
        val passwordField = PasswordField("Password temporanea").apply { setWidthFull() }
        val roleCombo = ComboBox<Role>("Ruolo").apply {
            setItems(roleRepository.findAll())
            setItemLabelGenerator { it.name }
            setWidthFull()
        }

        dialog.add(
            VerticalLayout(usernameField, emailField, passwordField, roleCombo).apply { isPadding = false }
        )

        val cancelButton = Button("Annulla") { dialog.close() }
        val createButton = Button("Crea") {
            val username = usernameField.value?.trim()
            val email = emailField.value?.trim()
            val password = passwordField.value
            val role = roleCombo.value

            if (username.isNullOrBlank() || email.isNullOrBlank() || password.isNullOrBlank() || role == null) {
                Notification.show("Compila tutti i campi")
                return@Button
            }

            val currentUser = CurrentUserHolder.get() ?: return@Button
            val request = VaadinServletRequest.getCurrent().httpServletRequest

            try {
                userService.createUser(username, email, password, role.id, currentUser.userId, request.remoteAddr)
                reloadUsers()
                dialog.close()
                Notification.show("Utente creato", 3000, Notification.Position.BOTTOM_END)
            } catch (e: Exception) {
                Notification.show("Errore: ${e.message}", 5000, Notification.Position.MIDDLE)
            }
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        dialog.footer.add(cancelButton, createButton)
        dialog.open()
    }

    // ================= Disattivazione utente =================

    private fun confirmDeactivateUser(user: User) {
        val dialog = Dialog().apply {
            headerTitle = "Disattivare \"${user.username}\"?"
            width = "440px"
        }
        dialog.add(Span("L'utente non potrà più accedere. Non è una cancellazione: lo storico (audit, analisi salvate) resta intatto."))

        val cancelButton = Button("Annulla") { dialog.close() }
        val confirmButton = Button("Disattiva") {
            val currentUser = CurrentUserHolder.get() ?: return@Button
            val request = VaadinServletRequest.getCurrent().httpServletRequest
            userService.deactivateUser(user.id, currentUser.userId, request.remoteAddr)
            reloadUsers()
            dialog.close()
        }
        dialog.footer.add(cancelButton, confirmButton)
        dialog.open()
    }

    // ================= Dialog: permessi di un ruolo =================

    /**
     * Una checkbox per permesso, raggruppate per categoria
     * (DATA_ACCESS, EXPORT, ADMIN...) così come definite nello schema.
     * Salvare sostituisce l'intero set di permessi del ruolo
     * (RolePermissionRepository.replacePermissions), non calcola un
     * diff aggiungi/rimuovi: il form arriva già come lista completa.
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

    private fun openEditUserDialog(user: User) {
        val dialog = Dialog().apply {
            className = "lbi-wizard-dialog"
            headerTitle = "Modifica \"${user.username}\""
            width = "440px"
        }

        val emailField = TextField("Email").apply {
            setWidthFull()
            value = user.email
        }
        val passwordField = PasswordField("Nuova password (lascia vuoto per non cambiarla)").apply {
            setWidthFull()
        }

        dialog.add(
            VerticalLayout(emailField, passwordField).apply { isPadding = false }
        )

        val cancelButton = Button("Annulla") { dialog.close() }
        val saveButton = Button("Salva") {
            val email = emailField.value?.trim() ?: ""
            if (email.isBlank()) {
                Notification.show("L'email non può essere vuota")
                return@Button
            }

            val updatedUser = user.copy(email = email, updatedAt = java.time.LocalDateTime.now())
            userRepository.update(updatedUser)

            val newPassword = passwordField.value ?: ""
            if (newPassword.isNotBlank()) {
                val hashedPassword: String = passwordEncoder.encode(newPassword)!!
                userRepository.update(updatedUser.copy(passwordHash = hashedPassword))
            }

            reloadUsers()
            dialog.close()
            Notification.show("Utente aggiornato", 3000, Notification.Position.BOTTOM_END)
        }.apply { addThemeVariants(ButtonVariant.LUMO_PRIMARY) }

        dialog.footer.add(cancelButton, saveButton)
        dialog.open()
    }
}