package com.lightningbi.lightning_engine.repository

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
class RolePermissionRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : RolePermissionRepository {

    override fun findPermissionIdsByRoleId(roleId: UUID): List<UUID> =
        jdbcTemplate.query(
            "SELECT permission_id FROM pg_lbi_system_role_permissions WHERE role_id = ?",
            { rs, _ -> UUID.fromString(rs.getString("permission_id")) },
            roleId
        )

    /**
     * Sostituisce l'intero set di permessi di un ruolo: elimina i
     * collegamenti esistenti e inserisce quelli nuovi, in una
     * transazione - stesso pattern di AreaChartRepository.replaceMetriche,
     * più semplice che calcolare un diff aggiungi/rimuovi quando l'intero
     * form arriva già come lista completa dal dialog.
     */
    @Transactional("postgresTransactionManager")
    override fun replacePermissions(roleId: UUID, permissionIds: List<UUID>) {
        jdbcTemplate.update("DELETE FROM pg_lbi_system_role_permissions WHERE role_id = ?", roleId)
        permissionIds.forEach { permissionId ->
            jdbcTemplate.update(
                "INSERT INTO pg_lbi_system_role_permissions (role_id, permission_id) VALUES (?, ?)",
                roleId, permissionId
            )
        }
    }
}