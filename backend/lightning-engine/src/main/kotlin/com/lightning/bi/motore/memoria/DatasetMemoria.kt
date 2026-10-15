package com.lightning.bi.motore.memoria

import com.lightning.bi.service.Collegamento
import com.lightning.bi.service.GrafoDataset
import com.lightning.bi.service.ModelloDataset
import java.util.UUID

/**
 * Un dataset caricato in memoria, immutabile: le sue tabelle, le symbol table delle dimensioni
 * e le chiavi composte. La struttura (tabelle, campi, collegamenti) è quella di ModelloDataset e
 * GrafoDataset, gli stessi del motore ClickHouse: i due motori partono dallo stesso modello.
 *
 * Vale per la coppia di versioni ([registryVersion], [dataVersion]) con cui è stato caricato:
 * se una delle due cambia, va ricaricato.
 */
class DatasetMemoria internal constructor(
    val modello: ModelloDataset,
    val registryVersion: Long,
    val dataVersion: Long,
    val tabelle: Map<UUID, TabellaMemoria>,
    /** Dimensione -> nome del campo (Naming.column del nome della dimensione), come in FiltriService. */
    val campiDimensione: Map<UUID, String>,
    /** Dimensione -> symbol table della sua colonna fisica. */
    private val simboliDimensione: Map<UUID, SimboliMemoria>,
    /** Collegamento composto (per nome) -> tabella -> id della combinazione per riga. */
    private val chiaviComposte: Map<String, Map<UUID, IntArray>>,
    /** Millisecondi del caricamento, per il log. */
    val millisecondiCaricamento: Long
) {
    val areaId: UUID get() = modello.areaId
    val grafo: GrafoDataset get() = modello.grafo

    fun tabella(id: UUID): TabellaMemoria =
        tabelle[id] ?: error("Tabella $id non caricata nel dataset $areaId")

    fun simboli(dimensioneId: UUID): SimboliMemoria? = simboliDimensione[dimensioneId]

    /** La colonna del campo nella tabella, o null se la tabella non ha il campo o non è stata caricata. */
    fun colonna(tabella: UUID, campo: String): CampoMemoria? =
        modello.colonnaDi(tabella, campo)?.let { tabelle[tabella]?.campo(it) }

    /** L'indice (bitmap per valore) del campo nella tabella, se è una dimensione. */
    fun indice(tabella: UUID, campo: String): IndiceValori? = colonna(tabella, campo)?.indice

    /**
     * La chiave del collegamento nella tabella, per riga: la colonna del campo, oppure l'id della
     * combinazione per una chiave composta (al posto del cityHash64 di GrafoDataset).
     */
    fun chiave(c: Collegamento, tabella: UUID): IntArray? =
        if (c.composto) chiaviComposte[c.nome]?.get(tabella)
        else colonna(tabella, c.campi.first())?.ids

    fun byte(): Long =
        tabelle.values.sumOf { it.byte() } + chiaviComposte.values.sumOf { m -> m.values.sumOf { it.size * 4L } }
}