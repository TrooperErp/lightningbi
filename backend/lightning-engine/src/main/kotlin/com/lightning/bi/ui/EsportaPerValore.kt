package com.lightning.bi.ui

import com.lightning.bi.motore.Motore
import com.lightning.bi.service.AnalisiService
import com.lightning.bi.service.EsportaService
import com.lightning.bi.service.FiltriService
import com.lightning.bi.service.PivotEngine
import com.lightning.bi.service.RisultatoAnalisi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

data class EsitoSalvataggio(val cartella: String, val salvati: List<String>, val errori: List<String>)

/**
 * "Un file per…": per ogni valore di un campo ricalcola la pivot con quel solo valore,
 * genera l'xlsx e lo salva su rete in <cartella>/<anno>/Fatturati Agenti/<Mese>/<valore>.xlsx.
 * Va chiamato dal thread della pagina (section access e selezioni dell'utente).
 */
@Service
class EsportaPerValore(
    private val motore: Motore,
    private val analisiService: AnalisiService,
    private val filtriService: FiltriService,
    private val esportaService: EsportaService
) {
    private val log = LoggerFactory.getLogger(EsportaPerValore::class.java)

    private val mesi = listOf(
        "Gennaio", "Febbraio", "Marzo", "Aprile", "Maggio", "Giugno",
        "Luglio", "Agosto", "Settembre", "Ottobre", "Novembre", "Dicembre"
    )

    fun salva(
        areaId: UUID,
        vistaId: UUID,
        nomeVista: String,
        campoId: UUID,
        nomeCampo: String,
        righe: List<UUID>,
        nomiCampi: Map<UUID, String>,
        selezioni: Map<UUID, Set<Long>>,
        risposte: RisposteExcel
    ): EsitoSalvataggio {
        val misura = risposte.misura ?: error("Nessuna misura scelta")

        // Anno e mese della cartella: dalla pivot completa (senza il campo fissato a un valore).
        val base = analisiService.calcola(vistaId, selezioni)
        val (anno, mese) = annoEMese(base, misura)

        val ids = selezioni[campoId]?.takeIf { it.isNotEmpty() }
            ?: motore.idPossibili(areaId, selezioni, campoId)
        val voci = ids.mapNotNull { id ->
            filtriService.etichette(areaId, campoId, listOf(id)).firstOrNull()?.let { id to it }
        }.sortedBy { it.second.lowercase() }

        val salvati = mutableListOf<String>()
        val errori = mutableListOf<String>()
        voci.forEach { (id, etichetta) ->
            try {
                val sel = selezioni + (campoId to setOf(id))
                var esito = analisiService.calcola(vistaId, sel)
                // Se il campo è la prima voce delle Righe, la tabella parte dai suoi figli (i mesi).
                if (righe.size > 1 && righe.first() == campoId) {
                    esito = esito.copy(gerarchia = esito.gerarchia.flatMap { it.children })
                }
                val valoriCampo = sel.mapNotNull { (campo, valori) ->
                    nomiCampi[campo]?.let { it to filtriService.etichette(areaId, campo, valori).joinToString(", ") }
                }.toMap()
                val r = risposte.copy(
                    unFilePer = null,
                    campiIntestazione = risposte.campiIntestazione + nomeCampo
                )
                val dati = EsportaExcel.genera(r, esito, valoriCampo, nomeFoglio = nomeVista)
                esportaService.salva(anno, mese, etichetta, dati)
                salvati += etichetta
            } catch (e: Exception) {
                log.warn("Export di '{}' fallito", etichetta, e)
                errori += "$etichetta (${e.message})"
            }
        }
        return EsitoSalvataggio(esportaService.cartellaDi(anno, mese).toString(), salvati, errori)
    }

    /** Anno più recente tra le colonne della pivot e ultimo mese con un valore in quell'anno. */
    private fun annoEMese(esito: RisultatoAnalisi, misura: String): Pair<String, String> {
        val oggi = LocalDate.now()
        val chiavi = LinkedHashSet<String>()
        fun raccogli(nodi: List<PivotEngine.PivotNode>) {
            nodi.forEach { chiavi.addAll(it.values.keys); raccogli(it.children) }
        }
        raccogli(esito.gerarchia)
        val anni = chiavi.filter { it.startsWith("$misura|") }
            .mapNotNull { it.split("|").last().toIntOrNull() }
            .filter { it in 1900..2200 }
        val anno = anni.maxOrNull()?.toString() ?: oggi.year.toString()

        val chiaveAnno = chiavi.firstOrNull { it.startsWith("$misura|") && it.endsWith("|$anno") }
        var ultimo = -1
        if (chiaveAnno != null) {
            esito.gerarchia.forEach { nodo ->
                val i = mesi.indexOf(nodo.label)
                val v = nodo.values[chiaveAnno]
                if (i >= 0 && v != null && v.signum() != 0 && i > ultimo) ultimo = i
            }
        }
        val mese = if (ultimo >= 0) mesi[ultimo] else mesi[oggi.monthValue - 1]
        return anno to mese
    }
}