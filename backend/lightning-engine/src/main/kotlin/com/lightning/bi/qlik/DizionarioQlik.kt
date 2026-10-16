package com.lightning.bi.qlik

import org.springframework.stereotype.Component
import java.io.InputStream

/** Che cosa è un campo di Qlik in LBI. */
enum class TipoVoceQlik { CAMPO, MISURA, CALENDARIO, IGNORA, DA_DEFINIRE }

/**
 * Una riga del dizionario: il campo di Qlik e dove sta in LBI.
 *
 * - CAMPO: la colonna [colonna] di una delle [viste] (si usa quella che c'è nel dataset);
 * - MISURA: la colonna [colonna] di [viste] con la [condizione] di set analysis (es. tipo documento);
 * - CALENDARIO: il campo derivato del calendario, "{calendario} Anno" con il campo data del dataset;
 * - IGNORA: campi di sistema di Qlik ($Field, $Table…);
 * - DA_DEFINIRE: non ancora risolto, l'import lo segnala e lo salta.
 */
data class VoceQlik(
    val campoQlik: String,
    val tipo: TipoVoceQlik,
    val viste: List<String>,
    val colonna: String,
    val condizione: String?,
    val nota: String
)

/**
 * Il dizionario dei campi Qlik → LBI, letto da `qlik/dizionario-campi.csv` nelle risorse
 * (separatore ";", prima riga di intestazione). Si corregge il file, non il codice.
 */
@Component
class DizionarioQlik {

    private val voci: Map<String, VoceQlik> =
        (javaClass.classLoader.getResourceAsStream("qlik/dizionario-campi.csv")
            ?: error("Manca la risorsa qlik/dizionario-campi.csv"))
            .use { leggi(it) }
            .associateBy { it.campoQlik.lowercase() }

    /** La voce di un campo Qlik (le parentesi quadre si tolgono); null se il campo non è nel dizionario. */
    fun voce(campoQlik: String): VoceQlik? = voci[campoQlik.trim().removePrefix("[").removeSuffix("]").lowercase()]

    fun tutte(): Collection<VoceQlik> = voci.values

    /** Le voci che l'import non sa ancora risolvere. */
    fun daDefinire(): List<VoceQlik> = voci.values.filter { it.tipo == TipoVoceQlik.DA_DEFINIRE }

    companion object {
        fun leggi(input: InputStream): List<VoceQlik> =
            input.bufferedReader(Charsets.UTF_8).readLines()
                .drop(1)
                .filter { it.isNotBlank() }
                .map { riga ->
                    val p = riga.split(';').map { it.trim() } + List(6) { "" }
                    VoceQlik(
                        campoQlik = p[0].removePrefix("﻿"),
                        tipo = TipoVoceQlik.valueOf(p[1].uppercase()),
                        viste = p[2].split(',').map { it.trim() }.filter { it.isNotEmpty() },
                        colonna = p[3],
                        condizione = p[4].ifEmpty { null },
                        nota = p[5]
                    )
                }
    }
}