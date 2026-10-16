package com.lightning.bi.qlik

import com.lightning.bi.model.RuoloTabella
import com.lightning.bi.model.TipoAggregazione
import com.lightning.bi.repository.RegistryRepository
import com.lightning.bi.service.AnalisiService
import com.lightning.bi.service.DatasetBozza
import com.lightning.bi.service.DatasetNonValidoException
import com.lightning.bi.service.DatasetService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/** Cosa è successo a un foglio Qlik. */
data class EsitoFoglioQlik(
    val foglio: String,
    val datasetId: UUID?,
    val analisiCreate: Int,
    val avvisi: List<String>
)

/**
 * Importa un documento Qlik (export XML "-prj") in LBI: per ogni foglio Qlik con analisi
 * crea un dataset con le viste QLK_ che i suoi campi usano, le sue analisi (Righe, Colonne,
 * Misure) e i suoi filtri (le listbox diventano dimensioni del dataset).
 *
 * I campi si traducono col [DizionarioQlik]. Quello che LBI non sa ancora fare (misure calcolate,
 * Before(), TOTAL, campi da definire) si salta e finisce negli avvisi. Un dataset che esiste già
 * con lo stesso nome non si tocca.
 */
@Service
class ImportaQlik(
    private val dizionario: DizionarioQlik,
    private val datasetService: DatasetService,
    private val analisiService: AnalisiService,
    private val registryRepository: RegistryRepository
) {
    private val log = LoggerFactory.getLogger(ImportaQlik::class.java)

    /** Un campo Qlik risolto: la vista e la colonna (nome sulla sorgente) in LBI. */
    private data class Risolto(val campoQlik: String, val vista: String, val colonna: String, val voce: VoceQlik)

    fun importa(documento: DocumentoQlik, userId: UUID): List<EsitoFoglioQlik> =
        documento.fogli.filter { it.analisi.isNotEmpty() }.map { foglio ->
            try {
                importaFoglio(documento, foglio, userId)
            } catch (e: Exception) {
                log.warn("Import del foglio Qlik '{}' fallito", foglio.nome, e)
                EsitoFoglioQlik(foglio.nome, null, 0, listOf("Foglio non importato: ${e.message}"))
            }
        }

    private fun importaFoglio(doc: DocumentoQlik, foglio: FoglioQlik, userId: UUID): EsitoFoglioQlik {
        val avvisi = mutableListOf<String>()
        val nomeDataset = foglio.nome.trim()
        if (registryRepository.findAreaByNome(nomeDataset) != null) {
            return EsitoFoglioQlik(nomeDataset, null, 0, listOf("Esiste già un dataset \"$nomeDataset\": non toccato"))
        }

        // ---- campi usati dal foglio ----
        val dimensioniUsate = linkedSetOf<String>()
        foglio.analisi.forEach { a -> (a.righe + a.colonne).forEach { dimensioniUsate += campiDimensione(doc, it) } }
        dimensioniUsate += foglio.filtri.flatMap { campiDimensione(doc, it) }
        val misureUsate = linkedSetOf<String>()
        foglio.analisi.forEach { a -> a.misure.forEach { m -> misureUsate += campiFormula(m.formula) } }

        val risolti = mutableMapOf<String, Risolto>()
        val calendario = mutableMapOf<String, String>() // campo Qlik -> componente (anno, mese…)
        (dimensioniUsate + misureUsate).forEach { campo ->
            val voce = dizionario.voce(campo)
            when (voce?.tipo) {
                null -> avvisi += "Campo \"$campo\" non è nel dizionario"
                TipoVoceQlik.IGNORA -> {}
                TipoVoceQlik.DA_DEFINIRE -> avvisi += "Campo \"$campo\" da definire: ${voce.nota}"
                TipoVoceQlik.CALENDARIO -> {
                    val comp = voce.colonna.substringAfter("{calendario}", "").trim().lowercase()
                    if (comp.isEmpty()) avvisi += "Campo calendario \"$campo\" non c'è ancora in LBI"
                    else calendario[campo] = comp
                }
                TipoVoceQlik.CAMPO, TipoVoceQlik.MISURA -> {
                    val vista = voce.viste.firstOrNull()
                    if (vista == null) avvisi += "Campo \"$campo\" senza vista nel dizionario"
                    else risolti[campo] = Risolto(campo, vista, voce.colonna, voce)
                }
            }
        }

        // ---- viste del dataset: prima quelle delle misure (Fatti) ----
        val vistePerMisura = misureUsate.mapNotNull { risolti[it]?.vista }.toSet()
        // Un campo che sta in più viste si prende dai Fatti del foglio, se c'è (Codice Articolo
        // in Acquisti viene dai documenti fornitori, non clienti).
        risolti.replaceAll { _, r -> r.copy(vista = r.voce.viste.firstOrNull { it in vistePerMisura } ?: r.vista) }
        val viste = (vistePerMisura + risolti.values.map { it.vista }).distinct()
        if (vistePerMisura.isEmpty()) return EsitoFoglioQlik(nomeDataset, null, 0, avvisi + "Nessuna misura risolta: niente dataset")

        val disponibili = datasetService.tabelleDisponibili()
        var bozza: DatasetBozza = datasetService.nuova().copy(nome = nomeDataset)
        val occPerVista = mutableMapOf<String, UUID>()
        viste.forEach { vista ->
            val tabella = disponibili.firstOrNull { it.nomeOrigine.equals(vista, ignoreCase = true) }
            if (tabella == null) {
                avvisi += "Vista $vista non trovata tra le tabelle importate"
                return@forEach
            }
            val prima = bozza.occorrenze.map { it.id }.toSet()
            bozza = datasetService.aggiungiTabella(bozza, tabella.id)
            val occ = bozza.occorrenze.first { it.id !in prima }
            occPerVista[vista] = occ.id
            bozza = bozza.impostaRuolo(occ.id, if (vista in vistePerMisura) RuoloTabella.FATTI else RuoloTabella.DIMENSIONE)
        }
        if (bozza.occorrenze.none { it.ruolo == RuoloTabella.FATTI }) {
            return EsitoFoglioQlik(nomeDataset, null, 0, avvisi + "Le viste dei Fatti non sono tra le tabelle importate")
        }

        // ---- solo le viste collegate ai Fatti da una chiave _KEY (anche passando per altre viste) ----
        fun chiavi(occ: UUID) = bozza.occorrenza(occ).colonne
            .map { it.nome.lowercase() }.filter { it.startsWith("_key") }.toSet()
        val collegate = bozza.occorrenze.filter { it.ruolo == RuoloTabella.FATTI }.map { it.id }.toMutableSet()
        var cambiato = true
        while (cambiato) {
            cambiato = false
            bozza.occorrenze.filter { it.id !in collegate }.forEach { o ->
                if (collegate.any { c -> chiavi(c).intersect(chiavi(o.id)).isNotEmpty() }) {
                    collegate += o.id
                    cambiato = true
                }
            }
        }
        occPerVista.entries.filter { it.value !in collegate }.toList().forEach { (vista, occ) ->
            val persi = risolti.values.filter { it.vista == vista }.map { it.campoQlik }
            avvisi += "Vista $vista non collegata ai Fatti da una chiave _KEY: tolta (campi persi: ${persi.joinToString(", ")})"
            bozza = bozza.rimuoviTabella(occ)
            occPerVista.remove(vista)
            persi.forEach { risolti.remove(it) }
        }

        // Le tabelle nate come Fatti e messe a Dimensione si portano dietro le metriche proposte: via.
        val fattiIds = bozza.occorrenze.filter { it.ruolo == RuoloTabella.FATTI }.map { it.id }.toSet()
        bozza.metriche.filter { it.areaTabellaId !in fattiIds }.forEach { bozza = bozza.rimuoviMetrica(it.id) }

        // ---- campi con lo stesso nome in più viste: si associano solo le chiavi _KEY ----
        // Il nome resta dove lo vuole il dizionario; nelle altre viste diventa "nome (alias)".
        val proprietario = risolti.values.associate { it.colonna.lowercase() to it.vista }
        bozza.campi().filter { !it.escluso }.groupBy { it.nomeCampo.lowercase() }.values
            .filter { gruppo -> gruppo.map { it.occorrenzaId }.distinct().size > 1 }
            .forEach { gruppo ->
                val nome = gruppo.first().nomeOrigine
                if (nome.startsWith("_KEY", ignoreCase = true)) return@forEach
                val tiene = proprietario[nome.lowercase()]?.let { occPerVista[it] } ?: gruppo.first().occorrenzaId
                gruppo.filter { it.occorrenzaId != tiene }.forEach { c ->
                    bozza = bozza.rinominaCampo(c.occorrenzaId, c.colonna, "$nome (${c.alias})")
                }
            }

        // ---- dimensioni e etichette con i nomi di Qlik ----
        fun campoDi(r: Risolto) = occPerVista[r.vista]?.let { occ ->
            bozza.campi().firstOrNull { it.occorrenzaId == occ && it.nomeOrigine.equals(r.colonna, ignoreCase = true) }
        }
        risolti.values.forEach { r ->
            val c = campoDi(r)
            if (c == null) {
                avvisi += "Colonna \"${r.colonna}\" non trovata in ${r.vista} (campo \"${r.campoQlik}\")"
                return@forEach
            }
            if (!c.etichetta.equals(r.campoQlik, ignoreCase = true)) bozza = bozza.impostaEtichetta(c.occorrenzaId, c.colonna, r.campoQlik)
            if (r.campoQlik in dimensioniUsate) bozza = bozza.impostaDimensione(c.occorrenzaId, c.colonna, true)
        }

        // ---- calendario: la data documento dei Fatti ----
        if (calendario.isNotEmpty()) {
            val fatti = bozza.occorrenze.filter { it.ruolo == RuoloTabella.FATTI }.map { it.id }.toSet()
            val data = bozza.campiData().filter { it.occorrenzaId in fatti }
                .let { d -> d.firstOrNull { it.nomeOrigine.equals("Data Documento", ignoreCase = true) } ?: d.firstOrNull() }
            if (data == null) avvisi += "Nessun campo data nei Fatti: Anno/Mese/Trimestre non disponibili"
            else bozza = bozza.impostaCalendario(data.nomeCampo)
        }

        // ---- salvataggio del dataset ----
        val areaId = try {
            datasetService.salva(bozza)
        } catch (e: DatasetNonValidoException) {
            return EsitoFoglioQlik(nomeDataset, null, 0, avvisi + "Dataset non valido: ${e.message}")
        }

        // ---- analisi ----
        val salvata = datasetService.carica(areaId) ?: error("Dataset $areaId appena salvato non trovato")
        val dimensioniLbi = registryRepository.findDimensioniByArea(areaId)
        val calendarioLbi = salvata.campiCalendario()
        val misurabili = analisiService.campiMisurabili(areaId)
        val aliasPerVista = viste.associateWith { v ->
            salvata.occorrenze.firstOrNull { it.nomeLogico == bozza.occorrenze.firstOrNull { o -> o.id == occPerVista[v] }?.nomeLogico }?.id
        }

        fun dimensioneDi(campoQlik: String): UUID? {
            calendario[campoQlik]?.let { comp ->
                val c = calendarioLbi[comp] ?: return null
                return dimensioniLbi.firstOrNull { it.areaTabellaId == c.occorrenzaId && it.colonnaFisica == c.colonna }?.dimensioneId
            }
            val r = risolti[campoQlik] ?: return null
            val occ = aliasPerVista[r.vista] ?: return null
            val c = salvata.campi().firstOrNull { it.occorrenzaId == occ && it.nomeOrigine.equals(r.colonna, ignoreCase = true) } ?: return null
            return dimensioniLbi.firstOrNull { it.areaTabellaId == occ && it.colonnaFisica == c.colonna }?.dimensioneId
        }

        var create = 0
        foglio.analisi.forEach { a ->
            try {
                if (creaAnalisi(doc, a, areaId, userId, risolti, aliasPerVista, misurabili, salvata, ::dimensioneDi, avvisi)) create++
            } catch (e: Exception) {
                log.warn("Analisi Qlik '{}' non importata", a.titolo, e)
                avvisi += "Analisi \"${a.titolo}\" non importata: ${e.message}"
            }
        }
        return EsitoFoglioQlik(nomeDataset, areaId, create, avvisi.distinct())
    }

    private fun creaAnalisi(
        doc: DocumentoQlik,
        a: AnalisiQlik,
        areaId: UUID,
        userId: UUID,
        risolti: Map<String, Risolto>,
        occPerVista: Map<String, UUID?>,
        misurabili: List<com.lightning.bi.service.CampoMisurabile>,
        salvata: DatasetBozza,
        dimensioneDi: (String) -> UUID?,
        avvisi: MutableList<String>
    ): Boolean {
        val vista = analisiService.crea(userId, areaId, a.titolo.take(100))
        val misure = mutableListOf<UUID>()
        a.misure.forEach { m ->
            // Un campo da solo, "[Rischio Cliente Fido Assegnato]": in Qlik è il valore della riga; qui la somma.
            val formula = m.formula.trim().let { f -> if (CAMPO_SOLO.matches(f)) "SUM($f)" else f }
            val semplice = MISURA_SEMPLICE.matchEntire(formula)
            if (semplice == null) {
                avvisi += "\"${a.titolo}\": misura \"${m.etichetta}\" saltata (calcolata: ${m.formula.take(80)})"
                return@forEach
            }
            val funzione = semplice.groupValues[1].uppercase()
            val distinti = semplice.groupValues[2].isNotBlank()
            val setQlik = semplice.groupValues[3].trim().ifEmpty { null }
            val campo = semplice.groupValues[4].trim().removePrefix("[").removeSuffix("]")
            val tipo = when (funzione) {
                "SUM" -> TipoAggregazione.SUM
                "AVG" -> TipoAggregazione.AVG
                "MIN" -> TipoAggregazione.MIN
                "MAX" -> TipoAggregazione.MAX
                else -> if (distinti) TipoAggregazione.COUNT_DISTINCT else TipoAggregazione.COUNT
            }
            val r = risolti[campo]
            val occ = r?.let { occPerVista[it.vista] }
            if (r == null || occ == null) {
                avvisi += "\"${a.titolo}\": misura \"${m.etichetta}\" saltata (campo \"$campo\" non risolto)"
                return@forEach
            }
            val fisica = salvata.campi().firstOrNull { it.occorrenzaId == occ && it.nomeOrigine.equals(r.colonna, ignoreCase = true) }?.colonna
            val misurabile = misurabili.firstOrNull { it.areaTabellaId == occ && it.colonna == fisica }
            if (misurabile == null && tipo != TipoAggregazione.COUNT) {
                avvisi += "\"${a.titolo}\": misura \"${m.etichetta}\" saltata (\"$campo\" non è nei Fatti)"
                return@forEach
            }
            val condizione = setQlik ?: r.voce.condizione
            val nome = m.etichetta.ifBlank { campo }.take(100)
            val tabella = misurabile?.areaTabellaId ?: occ
            val colonna = if (tipo == TipoAggregazione.COUNT) null else misurabile?.colonna
            val nuova = try {
                analisiService.aggiungiMisura(vista.id, nome, tipo, tabella, colonna, condizione)
            } catch (e: IllegalArgumentException) {
                avvisi += "\"${a.titolo}\": condizione di \"$nome\" non valida in LBI ($condizione), misura creata senza"
                analisiService.aggiungiMisura(vista.id, nome, tipo, tabella, colonna, null)
            }
            misure += nuova.id
        }

        fun dimensioni(lista: List<String>): List<UUID> = lista.flatMap { d ->
            val gruppo = doc.gruppi[d]
            // Gruppo ciclico: in LBI non c'è ancora, si usa il primo campo. Gerarchico: tutti i livelli.
            val campi = when {
                gruppo == null -> listOf(d)
                gruppo.ciclico -> listOf(gruppo.campi.first()).also { avvisi += "\"${a.titolo}\": gruppo ciclico \"$d\" ridotto al primo campo" }
                else -> gruppo.campi
            }
            campi.mapNotNull { c -> dimensioneDi(c).also { if (it == null) avvisi += "\"${a.titolo}\": campo \"$c\" non disponibile" } }
        }.distinct()

        val righe = dimensioni(a.righe)
        val colonne = dimensioni(a.colonne).filter { it !in righe }
        analisiService.impostaDisposizione(vista.id, righe, colonne, misure)
        if (!a.tabella) avvisi += "\"${a.titolo}\": era un grafico Qlik (${a.modo.removePrefix("GRAPH_MODE_").lowercase()}), importato come pivot"
        return true
    }

    private fun campiDimensione(doc: DocumentoQlik, dimensione: String): List<String> = doc.campiDi(dimensione)

    /** I campi tra parentesi quadre di una formula Qlik (fuori dalle set analysis). */
    private fun campiFormula(formula: String): List<String> =
        CAMPO_FORMULA.findAll(formula.replace(SET_ANALYSIS, "")).map { it.groupValues[1].trim() }.toList()

    private companion object {
        /** SUM([x]), Count(distinct [x]), Sum({<…>} [x]), Avg(x)… — una sola aggregazione su un solo campo. */
        val MISURA_SEMPLICE = Regex(
            "(?i)^(SUM|COUNT|AVG|MIN|MAX)\\s*\\(\\s*(DISTINCT\\s+)?(\\{<.*?>\\}\\s*)?(\\[[^\\]]+\\]|[A-Za-z_][A-Za-z0-9_ ]*)\\s*\\)$"
        )
        val CAMPO_SOLO = Regex("^\\[[^\\]]+\\]$")
        val CAMPO_FORMULA = Regex("\\[([^\\]]+)\\]")
        val SET_ANALYSIS = Regex("\\{<.*?>\\}")
    }
}