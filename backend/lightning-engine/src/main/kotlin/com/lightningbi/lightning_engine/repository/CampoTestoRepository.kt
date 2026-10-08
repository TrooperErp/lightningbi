package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.service.Naming
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/** I campi da leggere come testo (equivalente di text() in Qlik): nomi fisici. */
@Repository
class CampoTestoRepository(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) {
    fun tutti(): Set<String> =
        jdbcTemplate.queryForList("SELECT nome_campo FROM lbi_campo_testo", String::class.java).toSet()

    fun imposta(nomeCampo: String, testo: Boolean) {
        val fisico = Naming.column(nomeCampo)
        if (testo) {
            jdbcTemplate.update(
                "INSERT INTO lbi_campo_testo (nome_campo) VALUES (?) ON CONFLICT DO NOTHING", fisico
            )
        } else {
            jdbcTemplate.update("DELETE FROM lbi_campo_testo WHERE nome_campo = ?", fisico)
        }
    }
}