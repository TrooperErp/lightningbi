package com.lightning.bi.motore.memoria

import com.lightning.bi.model.RuoloTabella
import com.lightning.bi.repository.RegistryRepository
import com.lightning.bi.service.CampoEffettivo
import com.lightning.bi.service.ModelloDataset
import com.lightning.bi.service.ModelloDatasetCache
import com.lightning.bi.service.Naming
import com.lightning.bi.service.RiferimentoCampo
import com.lightning.bi.service.SymbolTableService
import com.lightning.bi.service.VersionService
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool

/**
 * Carica un dataset da ClickHouse in memoria (DatasetMemoria).
 *
 * - Ogni tabella (occorrenza): tutti i campi non esclusi come id; l'indice (bitmap per valore)
 *   solo per i campi delle dimensioni, con la regola di BitmapIndexBuilder; nei Fatti anche le
 *   copie numeriche dei campi numerici non chiave e non derivati (regola di TransformService).
 * - Le symbol table delle dimensioni.
 * - Le chiavi composte: un id per combinazione, comune a tutte le tabelle del collegamento.
 *
 * Le tabelle si leggono in parallelo; gli indici si costruiscono su tutti i core.
 */
@Service
class CaricatoreMemoria(
    private val jdbcTemplate: JdbcTemplate,
    private val modelloDatasetCache: ModelloDatasetCache,
    private val versionService: VersionService,
    private val registryRepository: RegistryRepository,
    private val symbolTableService: SymbolTableService,
    @Value("\${lbi.motore.memoria.thread-caricamento:4}") threadCaricamento: Int
) {
    private val log = LoggerFactory.getLogger(CaricatoreMemoria::class.java)

    private val poolLettura = Executors.newFixedThreadPool(threadCaricamento.coerceAtLeast(1)) { r ->
        Thread(r).apply { isDaemon = true; name = "memoria-carica-${hashCode()}" }
    }
    private val poolIndici = ForkJoinPool(Runtime.getRuntime().availableProcessors())

    @PreDestroy
    fun chiudi() {
        poolLettura.shutdown()
        poolIndici.shutdown()
    }

    fun carica(areaId: UUID): DatasetMemoria {
        val inizio = System.nanoTime()
        val versioni = versionService.snapshotVersions(areaId)
        val modello = modelloDatasetCache.get(areaId, versioni.registryVersion)
        val bozza = modello.bozza
        val campi = bozza.campi().filter { !it.escluso }

        // Come BitmapIndexBuilder: si indicizzano i campi delle dimensioni visibili.
        val dimensioni = campi
            .filter { !it.tecnico && RiferimentoCampo(it.occorrenzaId, it.colonna) in bozza.dimensioni }
            .map { it.nomeCampo }
            .toSet()

        val futuri = bozza.occorrenze.map { occ ->
            val suoi = campi.filter { it.occorrenzaId == occ.id }
            CompletableFuture.supplyAsync({ caricaTabella(modello, occ.id, occ.ruolo, suoi, dimensioni) }, poolLettura)
        }
        val tabelle = futuri.map { attendi(it) }.associateBy { it.id }

        val (campiDimensione, simboli) = caricaSimboli(areaId, modello)
        val chiavi = chiaviComposte(modello, tabelle)

        val ms = (System.nanoTime() - inizio) / 1_000_000
        val dataset = DatasetMemoria(
            modello, versioni.registryVersion, versioni.dataVersion,
            tabelle, campiDimensione, simboli, chiavi, ms
        )
        log.info(
            "Dataset {} in memoria: {} tabelle, {} righe, {} MB, {} ms",
            areaId, tabelle.size, tabelle.values.sumOf { it.numeroRighe.toLong() }, dataset.byte() / (1024 * 1024), ms
        )
        return dataset
    }

    // ---------- tabelle ----------

    private fun caricaTabella(
        modello: ModelloDataset,
        occorrenza: UUID,
        ruolo: RuoloTabella,
        campi: List<CampoEffettivo>,
        dimensioni: Set<String>
    ): TabellaMemoria {
        val inizio = System.nanoTime()
        val fisica = modello.fisica(occorrenza)
        val esistenti = symbolTableService.colonneDi(fisica)
        check(Naming.RID_COLUMN in esistenti) {
            "La tabella '$fisica' non ha i numeri di riga: serve un \"Ricarico completo\""
        }

        val colonneCampi = campi.map { Naming.requirePhysical(it.colonna, "colonna") }.distinct()
        val mancanti = colonneCampi.filter { it !in esistenti }
        check(mancanti.isEmpty()) {
            "La tabella '$fisica' non ha le colonne ${mancanti.joinToString(", ")}: va sincronizzata"
        }
        val indicizzate = campi.filter { it.nomeCampo in dimensioni }.map { it.colonna }.toSet()
        val colonneMisure = if (ruolo != RuoloTabella.FATTI) emptyList() else campi
            .filter { it.derivataDa == null && !it.isChiave && it.numerico }
            .map { it.colonna }
            .distinct()
            .filter { Naming.numericColumn(it) in esistenti }

        val maxRid = jdbcTemplate.queryForObject(
            "SELECT if(count() = 0, -1, toInt64(max(${Naming.RID_COLUMN}))) FROM $fisica", Long::class.java
        ) ?: -1L
        require(maxRid < Int.MAX_VALUE) { "La tabella '$fisica' ha numeri di riga oltre il limite: $maxRid" }

        val c = TabellaMemoria.Costruttore(occorrenza, fisica, maxRid.toInt(), colonneCampi, indicizzate, colonneMisure)
        val ids = colonneCampi.map { c.ids(it) }.toTypedArray()
        val numeri = colonneMisure.map { c.numeri(it) }.toTypedArray()
        val vuoti = colonneMisure.map { c.mancanti(it) }.toTypedArray()
        val primaMisura = ids.size + 2

        val select = (listOf(Naming.RID_COLUMN) + colonneCampi + colonneMisure.map { Naming.numericColumn(it) })
            .joinToString(", ")
        jdbcTemplate.query("SELECT $select FROM $fisica") { rs ->
            val rid = rs.getLong(1).toInt()
            c.presenti.add(rid)
            for (j in ids.indices) ids[j][rid] = rs.getLong(j + 2).toInt()
            for (j in numeri.indices) {
                val v = rs.getBigDecimal(primaMisura + j)
                if (v == null) vuoti[j].add(rid) else numeri[j][rid] = milionesimi(v)
            }
        }
        val lettura = (System.nanoTime() - inizio) / 1_000_000

        val tabella = c.costruisci(poolIndici)
        log.info(
            "Tabella {} in memoria: {} righe, {} colonne ({} indicizzate, {} misure), lettura {} ms, indici {} ms",
            fisica, tabella.numeroRighe, colonneCampi.size, indicizzate.size, colonneMisure.size,
            lettura, (System.nanoTime() - inizio) / 1_000_000 - lettura
        )
        return tabella
    }

    /** Un Decimal(38,6) in milionesimi, esatto: oltre il Long è un errore, mai un valore sbagliato. */
    private fun milionesimi(v: BigDecimal): Long =
        v.movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact()

    // ---------- symbol table delle dimensioni ----------

    private fun caricaSimboli(
        areaId: UUID,
        modello: ModelloDataset
    ): Pair<Map<UUID, String>, Map<UUID, SimboliMemoria>> {
        val dims = registryRepository.findDimensioniByArea(areaId)
        val nomi = registryRepository.findDimensioniByIds(dims.map { it.dimensioneId })
            .associate { it.id to Naming.column(it.nome) }
        // Come FiltriService: solo le dimensioni il cui campo sta in qualche tabella.
        val campi = dims.mapNotNull { d ->
            nomi[d.dimensioneId]?.takeIf { modello.grafo.tabelleCon(it).isNotEmpty() }?.let { d.dimensioneId to it }
        }.toMap()
        val perColonna = HashMap<String, SimboliMemoria>()
        val simboli = dims.filter { it.dimensioneId in campi }.associate { d ->
            d.dimensioneId to perColonna.getOrPut(d.colonnaFisica) { SimboliMemoria.carica(jdbcTemplate, d.colonnaFisica) }
        }
        return campi to simboli
    }

    // ---------- chiavi composte ----------

    private class Tupla(val v: IntArray) {
        override fun equals(other: Any?) = other is Tupla && v.contentEquals(other.v)
        override fun hashCode() = v.contentHashCode()
    }

    /** Per ogni collegamento composto: un id per combinazione, lo stesso in tutte le sue tabelle. */
    private fun chiaviComposte(
        modello: ModelloDataset,
        tabelle: Map<UUID, TabellaMemoria>
    ): Map<String, Map<UUID, IntArray>> =
        modello.grafo.collegamenti.filter { it.composto }.associate { c ->
            val dizionario = HashMap<Tupla, Int>()
            c.nome to c.occorrenze.associateWith { t ->
                val tabella = tabelle.getValue(t)
                val colonne = c.campi.map { campo ->
                    val colonna = modello.colonnaDi(t, campo) ?: error("Il campo '$campo' non è nella tabella $t")
                    tabella.campo(colonna)?.ids ?: error("La colonna '$colonna' non è stata caricata")
                }
                val ids = IntArray(colonne.first().size)
                val scorri = tabella.presenti.intIterator
                while (scorri.hasNext()) {
                    val rid = scorri.next()
                    val tupla = Tupla(IntArray(colonne.size) { colonne[it][rid] })
                    ids[rid] = dizionario.getOrPut(tupla) { dizionario.size + 1 }
                }
                ids
            }
        }

    private fun <T> attendi(f: CompletableFuture<T>): T =
        try {
            f.join()
        } catch (e: CompletionException) {
            throw e.cause ?: e
        }
}