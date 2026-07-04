package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.AreaDimensione
import com.lightningbi.lightning_engine.repository.RegistryRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

data class DimensionState(
    val verdi: Set<Long>,
    val grigi: Set<Long>,
    val selezionati: Set<Long>
)

@Service
class AssociativeStateService(
    private val jdbcTemplate: JdbcTemplate,
    private val registryRepository: RegistryRepository
) {
    private val validIdentifier = Regex("^[a-z0-9_]+$")

    fun getStates(areaId: UUID, selections: Map<UUID, Set<Long>>): Map<UUID, DimensionState> {
        val area = registryRepository.findAreaById(areaId) ?: error("Area not found")
        val dims = registryRepository.findDimensioniByArea(areaId)
        val dimById = dims.associateBy { it.dimensioneId }

        return dims.associate { dim ->
            val omitSelf = selections.filterKeys { it != dim.dimensioneId }
            val tutti = allValues(dim)

            val verdi = if (omitSelf.isEmpty()) tutti
            else queryDistinct(area.tabellaFisica, dim.colonnaFisica, omitSelf, dimById)

            dim.dimensioneId to DimensionState(
                verdi = verdi,
                grigi = tutti - verdi,
                selezionati = selections[dim.dimensioneId] ?: emptySet()
            )
        }
    }

    private fun queryDistinct(
        table: String,
        column: String,
        filters: Map<UUID, Set<Long>>,
        dimById: Map<UUID, AreaDimensione>
    ): Set<Long> {
        require(validIdentifier.matches(table)) { "Invalid table: $table" }
        require(validIdentifier.matches(column)) { "Invalid column: $column" }

        val whereClauses = mutableListOf<String>()
        val args = mutableListOf<Any>()

        filters.forEach { (dimId, values) ->
            if (values.isEmpty()) return@forEach
            val col = dimById[dimId]?.colonnaFisica ?: return@forEach
            require(validIdentifier.matches(col)) { "Invalid column: $col" }
            whereClauses += "$col IN (${values.joinToString(",") { "?" }})"
            args.addAll(values)
        }

        val where = if (whereClauses.isEmpty()) "" else "WHERE ${whereClauses.joinToString(" AND ")}"
        val sql = "SELECT DISTINCT $column FROM $table $where"

        return jdbcTemplate.query(sql, { rs, _ -> rs.getLong(1) }, *args.toTypedArray()).toSet()
    }

    private fun allValues(dim: AreaDimensione): Set<Long> {
        val dimensione = registryRepository.findDimensione(dim.dimensioneId) ?: return emptySet()
        val nome = dimensione.nome
        require(validIdentifier.matches(nome)) { "Invalid dimension name: $nome" }
        val table = "ch_lbi_symbol_$nome"
        return jdbcTemplate.query("SELECT value_id FROM $table", { rs, _ -> rs.getLong(1) }).toSet()
    }
}