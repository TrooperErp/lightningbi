package com.lightningbi.lightning_engine.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.UUID

@Service
class AuditService(
    private val jdbcTemplate: JdbcTemplate
) {
    fun log(action: String, userId: UUID?, details: String, ipAddress: String) {
        jdbcTemplate.update("""
            INSERT INTO ch_lbi_audit_log 
            (id, user_id, action, details, ip_address, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """,
            UUID.randomUUID().toString(),
            userId?.toString(),
            action,
            details,
            ipAddress,
            LocalDateTime.now()
        )
    }
}