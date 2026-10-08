package com.lightningbi.lightning_engine.service

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

/** Contatori di un campo, come in Qlik: "(possibili/totali)", più quanti valori sono selezionati. */
data class ContatoreCampo(val possibili: Long, val totali: Long, val selezionati: Long)

/** Stato di un valore: verde (selezionato), bianco (possibile), grigio (escluso). */
enum class StatoValore { SELEZIONATO, POSSIBILE, ESCLUSO }

data class ValoreFiltro(val id: Long, val etichetta: String, val stato: StatoValore)

/**
 * Dati per la pagina dei filtri (il foglio "Impostazione filtri"): contatori
 * dei campi ed elenchi dei valori con il loro stato. Si appoggia al motore a
 * propagazione (GrafoDataset) e restituisce SOLO numeri o una finestra di
 * valori, mai insiemi completi: un campo può avere milioni di valori.
 *
 * - [contatori]: per ogni campo (possibili/totali) calcolati dentro ClickHouse.
 * - [valori]: la finestra [offset, offset + limite) dell'elenco di un campo,
 *   ordinata come Qlik: selezionati, poi possibili, poi esclusi, e dentro ogni
 *   gruppo per valore (numeri prima, in ordine numerico) e poi alfabetico.
 *   Con [ricerca] si filtra per testo, senza distinguere maiuscole.
 * - [numeroValori]: quanti valori ha l'elenco (per la barra di scorrimento).
 *
 * Le selezioni arrivano per id di dimensione, come le salva UserPivotState; il
 * valore 0 è "non definito". Le date si mostrano come dd/MM/yyyy.
 */
@Service
class FiltriService(
    private val jdbcTemplate: JdbcTemplate,
    private val registryRepository: com.lightningbi.lightning_engine.repository.RegistryRepository,
    private val modelloDatasetCache: ModelloDatasetCache,
    private val versionService: VersionService,
    private val calendarioService: CalendarioService,
    private val sezioneAccessoService: SezioneAccessoService

) {
    private val log = LoggerFactory.getLogger(FiltriService::class.java)

    private class Contesto(
        val modello: ModelloDataset,
        /** Dimensione -> nome del campo nell'indice. */
        val campi: Map<UUID, String>,
        /** Dimensione -> colonna fisica che ne dà le etichette (symbol table). */
        val colonne: Map<UUID, String>,
        /** Selezioni per nome di campo (compresa quella forzata dell'azienda). */
        val selezioni: Map<String, Set<Long>>,
        /** Selezioni permanenti (section access): riducono l'universo dei campi. Vuoto per l'admin. */
        val universo: Map<String, Set<Long>>,
        /** Campo azienda forzato: l'utente non può togliere né cambiare la sua selezione. */
        val forzato: String?
    )

    private fun contesto(areaId: UUID, selections: Map<UUID, Set<Long>>): Contesto {
        val versioni = versionService.snapshotVersions(areaId)
        val modello = modelloDatasetCache.get(areaId, versioni.registryVersion)
        val dims = registryRepository.findDimensioniByArea(areaId)
        val nomi = registryRepository.findDimensioniByIds(dims.map { it.dimensioneId })
            .associate { it.id to Naming.column(it.nome) }

        // Solo le dimensioni che stanno nell'indice (le altre: dataset da salvare di nuovo).
        val campi = dims.mapNotNull { d ->
            nomi[d.dimensioneId]?.takeIf { modello.grafo.tabelleCon(it).isNotEmpty() }?.let { d.dimensioneId to it }
        }.toMap()
        val colonne = dims.filter { it.dimensioneId in campi }.associate { it.dimensioneId to it.colonnaFisica }

        // Section access: per un non-admin la selezione sull'azienda è forzata, qualunque cosa arrivi dalla vista.
        val vincolo = sezioneAccessoService.vincolo(areaId)
        val forzato = vincolo?.let { v ->
            campi[v.dimensioneId] ?: throw SecurityException("Il campo azienda non è nell'indice del dataset")
        }
        val effettive = if (vincolo == null) selections else selections + (vincolo.dimensioneId to vincolo.ids)

        val selezioni = effettive.filterValues { it.isNotEmpty() }
            .mapNotNull { (id, valori) -> campi[id]?.let { it to valori } }
            .toMap()
        val universo = if (vincolo == null || forzato == null) emptyMap() else mapOf(forzato to vincolo.ids)
        return Contesto(modello, campi, colonne, selezioni, universo, forzato)
    }

    /**
     * Contatori dei campi richiesti (null = tutti). Una sola query per
     * l'intero gruppo. Un campo selezionato si calcola SENZA la propria
     * selezione, come negli stati.
     */
    fun contatori(
        areaId: UUID,
        selections: Map<UUID, Set<Long>>,
        dimensioni: Collection<UUID>? = null
    ): Map<UUID, ContatoreCampo> {
        val c = contesto(areaId, selections)
        val richieste = (dimensioni ?: c.campi.keys).filter { it in c.campi }
        if (richieste.isEmpty()) return emptyMap()

        val sql = richieste.joinToString("\nUNION ALL\n") { dimId ->
            val campo = c.campi.getValue(dimId)
            val scelti = c.selezioni[campo].orEmpty()
            val omesso = if (scelti.isNotEmpty()) campo else null
            val selezionati = if (scelti.isEmpty()) "toUInt64(0)"
            else "countIf(valore_id IN (${scelti.joinToString(",")}))"
            "SELECT '$campo' AS campo, count() AS totali, countIf(verde = 1) AS possibili, $selezionati AS selezionati " +
                    "FROM (${c.modello.grafo.sqlStatoCampo(campo, c.selezioni, omesso, c.universo)})"
        }

        val perCampo = mutableMapOf<String, ContatoreCampo>()
        jdbcTemplate.query(sql) { rs ->
            perCampo[rs.getString("campo")] =
                ContatoreCampo(rs.getLong("possibili"), rs.getLong("totali"), rs.getLong("selezionati"))
        }
        return richieste.mapNotNull { dimId ->
            perCampo[c.campi.getValue(dimId)]?.let { dimId to it }
        }.toMap()
    }

    /** Una finestra dell'elenco dei valori di un campo. */
    fun valori(
        areaId: UUID,
        selections: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        ricerca: String?,
        offset: Int,
        limite: Int
    ): List<ValoreFiltro> {
        val c = contesto(areaId, selections)
        val campo = c.campi[dimensioneId] ?: return emptyList()
        val (stato, simboli, scelti) = parti(c, dimensioneId, campo)
        val selezionato = if (scelti.isEmpty()) "toUInt8(0)" else "toUInt8(st.valore_id IN (${scelti.joinToString(",")}))"

        val sql = """
            SELECT st.valore_id AS id, s.value_string AS etichetta, st.verde AS verde, $selezionato AS sel
            FROM ($stato) AS st
            LEFT JOIN $simboli AS s ON toUInt32(st.valore_id) = s.value_id
            ${filtroRicerca(ricerca)}
            ORDER BY s.value_number ASC, s.value_string ASC, st.valore_id ASC
            LIMIT $limite OFFSET $offset
        """.trimIndent()

        val risultato = mutableListOf<ValoreFiltro>()
        jdbcTemplate.query(sql) { rs ->
            val testo = rs.getString("etichetta")
            risultato += ValoreFiltro(
                id = rs.getLong("id"),
                etichetta = if (testo == null) "(non definito)" else calendarioService.formatta(testo),
                stato = when {
                    rs.getInt("sel") == 1 -> StatoValore.SELEZIONATO
                    rs.getInt("verde") == 1 -> StatoValore.POSSIBILE
                    else -> StatoValore.ESCLUSO
                }
            )
        }
        return risultato
    }

    /** Quanti valori ha l'elenco di un campo (con la ricerca applicata). */
    fun numeroValori(
        areaId: UUID,
        selections: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        ricerca: String?
    ): Int {
        val c = contesto(areaId, selections)
        val campo = c.campi[dimensioneId] ?: return 0
        val (stato, simboli, _) = parti(c, dimensioneId, campo)
        val sql = """
            SELECT count()
            FROM ($stato) AS st
            LEFT JOIN $simboli AS s ON toUInt32(st.valore_id) = s.value_id
            ${filtroRicerca(ricerca)}
        """.trimIndent()
        return jdbcTemplate.queryForObject(sql, Long::class.java)?.toInt() ?: 0
    }

    /** Le etichette dei valori indicati (per "Selezioni correnti"), in ordine numerico poi alfabetico. */
    fun etichette(areaId: UUID, dimensioneId: UUID, ids: Collection<Long>): List<String> {
        if (ids.isEmpty()) return emptyList()
        val colonna = registryRepository.findDimensioniByArea(areaId)
            .firstOrNull { it.dimensioneId == dimensioneId }?.colonnaFisica ?: return emptyList()
        val sql = "SELECT value_id, value_string FROM ${Naming.symbolTable(colonna)} " +
                "WHERE value_id IN (${ids.joinToString(",")}) ORDER BY value_number ASC, value_string ASC"
        val testi = mutableListOf<String>()
        jdbcTemplate.query(sql) { rs -> testi += calendarioService.formatta(rs.getString("value_string")) }
        if (0L in ids) testi += "(non definito)"
        return testi
    }

    /**
     * Selezionare un valore ESCLUSO (grigio): come in Qlik, le selezioni in conflitto con il
     * valore si annullano e il valore diventa selezionato. Restituisce le dimensioni di cui
     * togliere la selezione: il minimo necessario perché il valore diventi possibile (si provano
     * le selezioni una alla volta, poi a coppie, poi a terne; altrimenti si tolgono tutte le
     * altre). Vuoto se il valore è già possibile.
     */
    fun conflitti(areaId: UUID, selections: Map<UUID, Set<Long>>, dimensioneId: UUID, valoreId: Long): Set<UUID> {
        val c = contesto(areaId, selections)
        val campo = c.campi[dimensioneId] ?: return emptySet()
        val altri = c.selezioni.keys.filter { it != campo && it != c.forzato }.sorted()
        if (altri.isEmpty()) return emptySet()

        fun possibile(sel: Map<String, Set<Long>>): Boolean {
            val sql = "SELECT max(verde) FROM (${c.modello.grafo.sqlStatoCampo(campo, sel, campo, c.universo)}) WHERE valore_id = $valoreId"
            return (jdbcTemplate.queryForObject(sql, Number::class.java)?.toInt() ?: 0) == 1
        }
        val dimensionePerCampo = c.campi.entries.associate { it.value to it.key }
        fun comeDimensioni(nomi: Collection<String>): Set<UUID> = nomi.mapNotNull { dimensionePerCampo[it] }.toSet()

        if (possibile(c.selezioni)) return emptySet()
        for (taglia in 1..minOf(3, altri.size)) {
            for (combinazione in combinazioni(altri, taglia)) {
                if (possibile(c.selezioni - combinazione)) return comeDimensioni(combinazione)
            }
        }
        return comeDimensioni(altri)
    }

    private fun combinazioni(elementi: List<String>, taglia: Int): Sequence<List<String>> = sequence {
        if (taglia == 0) {
            yield(emptyList())
            return@sequence
        }
        for (i in elementi.indices) {
            for (resto in combinazioni(elementi.drop(i + 1), taglia - 1)) yield(listOf(elementi[i]) + resto)
        }
    }

    // ---------- interni ----------

    private data class Parti(val stato: String, val simboli: String, val scelti: Set<Long>)

    private fun parti(c: Contesto, dimensioneId: UUID, campo: String): Parti {
        val scelti = c.selezioni[campo].orEmpty()
        val omesso = if (scelti.isNotEmpty()) campo else null
        val stato = c.modello.grafo.sqlStatoCampo(campo, c.selezioni, omesso, c.universo)
        val colonna = c.colonne.getValue(dimensioneId)
        return Parti(stato, Naming.symbolTable(colonna), scelti)
    }

    /** La ricerca per testo, senza distinguere maiuscole; il testo si rende sicuro come letterale SQL. */
    private fun filtroRicerca(ricerca: String?): String {
        val testo = ricerca?.trim().orEmpty()
        if (testo.isEmpty()) return ""
        val sicuro = testo.replace("\\", "\\\\").replace("'", "\\'")
        return "WHERE positionCaseInsensitiveUTF8(ifNull(s.value_string, ''), '$sicuro') > 0"
    }
}