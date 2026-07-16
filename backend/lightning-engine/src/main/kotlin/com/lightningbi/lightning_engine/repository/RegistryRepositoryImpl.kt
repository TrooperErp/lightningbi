package com.lightningbi.lightning_engine.repository

import com.lightningbi.lightning_engine.model.*
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Repository
class RegistryRepositoryImpl(
    @Qualifier("postgresJdbcTemplate") private val jdbcTemplate: JdbcTemplate
) : RegistryRepository {

    override fun findAreaByNome(nome: String): Area? =
        jdbcTemplate.query(
            "SELECT * FROM lbi_area WHERE nome = ?",
            { rs, _ -> Area(UUID.fromString(rs.getString("id")), rs.getString("nome"), rs.getString("tabella_fisica")) },
            nome
        ).firstOrNull()

    override fun findAreaById(id: UUID): Area? =
        jdbcTemplate.query(
            "SELECT * FROM lbi_area WHERE id = ?",
            { rs, _ -> Area(UUID.fromString(rs.getString("id")), rs.getString("nome"), rs.getString("tabella_fisica")) },
            id
        ).firstOrNull()

    override fun findDimensioniByArea(areaId: UUID): List<AreaDimensione> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_area_dimensione WHERE area_id = ?",
            { rs, _ -> AreaDimensione(
                UUID.fromString(rs.getString("area_id")),
                UUID.fromString(rs.getString("dimensione_id")),
                rs.getString("colonna_fisica"),
                rs.getBoolean("obbligatoria"),
                rs.getObject("cardinalita_stimata") as Long?
            ) },
            areaId
        )

    override fun findDimensioniByIds(ids: List<UUID>): List<Dimensione> {
        if (ids.isEmpty()) return emptyList()
        val placeholders = ids.joinToString(",") { "?" }
        return jdbcTemplate.query(
            "SELECT * FROM lbi_dimensione WHERE id IN ($placeholders)",
            { rs, _ -> Dimensione(
                UUID.fromString(rs.getString("id")), rs.getString("nome"), rs.getString("tipo"),
                rs.getBoolean("conformata"), rs.getString("tabella_dim_fisica"), rs.getString("colonna_chiave")
            ) },
            *ids.toTypedArray()
        )
    }

    override fun findMetricheByArea(areaId: UUID): List<AreaMetrica> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_area_metrica WHERE area_id = ?",
            { rs, _ -> AreaMetrica(
                UUID.fromString(rs.getString("id")),
                UUID.fromString(rs.getString("area_id")),
                rs.getString("nome"),
                rs.getString("colonna_fisica"),
                rs.getString("tipo_aggregazione")
            ) },
            areaId
        )

    override fun findDimensione(id: UUID): Dimensione? =
        jdbcTemplate.query(
            "SELECT * FROM lbi_dimensione WHERE id = ?",
            { rs, _ -> Dimensione(
                UUID.fromString(rs.getString("id")),
                rs.getString("nome"),
                rs.getString("tipo"),
                rs.getBoolean("conformata"),
                rs.getString("tabella_dim_fisica"),
                rs.getString("colonna_chiave")
            ) },
            id
        ).firstOrNull()

    override fun getVersion(): Long =
        jdbcTemplate.queryForObject("SELECT version FROM lbi_registry_version WHERE id = 1", Long::class.java)!!

    @Transactional("postgresTransactionManager")
    override fun bumpVersion() {
        jdbcTemplate.update("UPDATE lbi_registry_version SET version = version + 1 WHERE id = 1")
    }

    override fun saveArea(area: Area) {
        jdbcTemplate.update(
            "INSERT INTO lbi_area (id, nome, tabella_fisica) VALUES (?, ?, ?)",
            area.id, area.nome, area.tabellaFisica
        )
    }

    override fun saveDimensione(dimensione: Dimensione) {
        jdbcTemplate.update(
            "INSERT INTO lbi_dimensione (id, nome, tipo, conformata, tabella_dim_fisica, colonna_chiave) VALUES (?, ?, ?, ?, ?, ?)",
            dimensione.id, dimensione.nome, dimensione.tipo, dimensione.conformata,
            dimensione.tabellaDimFisica, dimensione.colonnaChiave
        )
    }

    override fun saveAreaDimensione(ad: AreaDimensione) {
        jdbcTemplate.update(
            "INSERT INTO lbi_area_dimensione (area_id, dimensione_id, colonna_fisica, obbligatoria, cardinalita_stimata) VALUES (?, ?, ?, ?, ?)",
            ad.areaId, ad.dimensioneId, ad.colonnaFisica, ad.obbligatoria, ad.cardinalitaStimata
        )
    }

    override fun saveAreaMetrica(am: AreaMetrica) {
        jdbcTemplate.update(
            "INSERT INTO lbi_area_metrica (id, area_id, nome, colonna_fisica, tipo_aggregazione) VALUES (?, ?, ?, ?, ?)",
            am.id, am.areaId, am.nome, am.colonnaFisica, am.tipoAggregazione
        )
    }

    override fun findAllAree(): List<Area> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_area ORDER BY nome",
            { rs, _ -> Area(UUID.fromString(rs.getString("id")), rs.getString("nome"), rs.getString("tabella_fisica")) }
        )

    override fun findAllDimensioni(): List<Dimensione> =
        jdbcTemplate.query(
            "SELECT * FROM lbi_dimensione ORDER BY nome",
            { rs, _ -> Dimensione(
                UUID.fromString(rs.getString("id")), rs.getString("nome"), rs.getString("tipo"),
                rs.getBoolean("conformata"), rs.getString("tabella_dim_fisica"), rs.getString("colonna_chiave")
            ) }
        )
}