package com.lightningbi.lightning_engine.service

import com.lightningbi.lightning_engine.model.ImportedColumn
import com.lightningbi.lightning_engine.model.ImportedTable
import com.lightningbi.lightning_engine.model.RuoloTabella
import com.lightningbi.lightning_engine.model.AreaTabella
import com.lightningbi.lightning_engine.model.TipoAggregazione
import java.util.UUID

/**
 * Modello in memoria di un dataset mentre lo si costruisce (bozza), nello
 * stile della vista Associations del Data manager di Qlik: tabelle, campi
 * associati per nome, associazioni consigliate, chiavi sintetiche e loop.
 *
 * Kotlin puro, senza Spring né database: DatasetService lo legge e lo scrive,
 * le viste lo modificano. Ogni operazione restituisce una NUOVA bozza.
 *
 * Tutto ciò che dipende dalla sorgente (nomi di campo, ruoli, tipi) arriva
 * dai dati delle tabelle importate: qui non c'è nessun nome di gestionale.
 */

enum class Confidenza { ALTA, MEDIA }

/** Eccezione al nome di default di un campo, valida solo in questa bozza (AreaCampo). */
data class EccezioneCampo(val nomeCampo: String? = null, val escluso: Boolean = false)

/** Una colonna di un'occorrenza, per nome FISICO (Naming.column). */
data class RiferimentoCampo(val occorrenzaId: UUID, val colonna: String)

/** Una tabella importata dentro la bozza (AreaTabella). [eccezioni] ha per chiave il nome fisico della colonna. */
data class OccorrenzaBozza(
    val id: UUID,
    val importedTableId: UUID,
    val nomeLogico: String,
    val alias: String,
    val ruolo: RuoloTabella,
    val colonne: List<ImportedColumn>,
    val eccezioni: Map<String, EccezioneCampo> = emptyMap(),
    /** Prefissi dei campi tecnici (chiavi) della connessione della tabella, ad esempio "_KEY". */
    val prefissiTecnici: List<String> = emptyList(),
    /** Disconnessa logicamente a mano (come Qlik): le selezioni non entrano né escono da questa tabella. */
    val disconnessa: Boolean = false
)



data class MetricaBozza(
    val id: UUID,
    val nome: String,
    /** Occorrenza dei Fatti su cui si calcola. */
    val areaTabellaId: UUID,
    /** Nome fisico della colonna; null solo per COUNT(*). */
    val colonna: String?,
    val tipo: TipoAggregazione
)

/** Un campo del dataset: una colonna di un'occorrenza, con il nome che ha nel dataset. */
data class CampoEffettivo(
    val occorrenzaId: UUID,
    val alias: String,
    val ruolo: RuoloTabella,
    /** Nome della colonna sulla sorgente, per mostrarlo. */
    val nomeOrigine: String,
    /** Nome fisico della colonna sulla tabella ClickHouse. */
    val colonna: String,
    val tipo: String,
    val isChiave: Boolean,
    /** Nome del campo nel dataset: due campi con lo stesso nome sono associati. */
    val nomeCampo: String,
    val escluso: Boolean,
    /** Campo derivato da una data (calendario): nome della colonna data di origine; null per gli altri. */
    val derivataDa: String? = null,
    /** Componente del calendario (anno, mese, giorno...); presente solo con [derivataDa]. */
    /** Componente del calendario (anno, mese, giorno...); presente solo con [derivataDa]. */
    val componente: String? = null,
    /**
     * Campo TECNICO: marcato come chiave, oppure con un nome che inizia con un prefisso tecnico
     * della connessione. Lega le tabelle ma non è informativo per l'utente: Dataset, Analisi e
     * Grafici non lo mostrano; resta nel Modello dati e nel conteggio distinti.
     */
    val tecnico: Boolean = false
) {
    val numerico: Boolean get() = ColumnProposal.isNumerico(tipo)
}

/**
 * Un campo presente in più occorrenze. [perValore] è true se le colonne fisiche
 * hanno nomi diversi: gli id delle symbol table non sono confrontabili e il
 * motore (fase E) unifica i valori attraverso value_string / value_number.
 */
data class Associazione(
    val nomeCampo: String,
    val occorrenze: List<UUID>,
    val confidenza: Confidenza,
    val perValore: Boolean
)

/**
 * Chiave composta (la "chiave sintetica" di Qlik): due o più campi condivisi
 * dalle STESSE tabelle. Nell'indice è una voce a sé, che ha per valore
 * l'hash a 64 bit della combinazione degli id dei campi, e fa da collegamento
 * nella propagazione. Il nome è deterministico: "chiave__" + i campi in ordine.
 */
data class ChiaveComposta(
    val nome: String,
    val campi: List<String>,
    val occorrenze: Set<UUID>
)

data class Problema(
    val messaggio: String,
    val campi: List<String> = emptyList(),
    val tabelle: List<String> = emptyList()
)

/** Gli errori bloccano il salvataggio; gli avvisi no. */
data class EsitoValidazione(val errori: List<Problema>, val avvisi: List<Problema>) {
    val valido: Boolean get() = errori.isEmpty()
}
/** Esito della ricerca dei loop: le tabelle disconnesse (a mano e automatiche) e, per ogni automatica, il ciclo che rompe. */
private class RisoluzioneLoop(
    val disconnesse: Set<UUID>,
    val automatiche: List<Pair<UUID, List<UUID>>>
)

data class DatasetBozza(
    /** Null finché il dataset non è salvato. */
    val areaId: UUID? = null,
    val nome: String = "",
    val occorrenze: List<OccorrenzaBozza> = emptyList(),
    /** Campi usati come dimensione (filtri). Uno per nome di campo; vedi DatasetService. */
    val dimensioni: Set<RiferimentoCampo> = emptySet(),
    val metriche: List<MetricaBozza> = emptyList(),
    /**
     * Nome del campo data del calendario: i suoi derivati (Anno, Mese, Giorno) compaiono nella
     * barra in cima alla pagina dei filtri. Null finché non si sceglie.
     */
    val campoCalendario: String? = null
) {


    // ---------- campi ----------

    /**
     * Tutti i campi, anche quelli esclusi. La prima occorrenza di una tabella
     * importata mantiene i nomi delle colonne; dalla seconda i nomi si
     * qualificano con l'alias (come Qlik con le tabelle caricate due volte).
     * Un'eccezione (rinomina) ha la precedenza.
     */
    fun campi(): List<CampoEffettivo> {
        val viste = mutableSetOf<UUID>()
        return occorrenze.flatMap { o ->
            val qualificata = !viste.add(o.importedTableId)
            o.colonne.map { c ->
                val fisica = Naming.column(c.nomeCampo)
                val ecc = o.eccezioni[fisica]
                val nome = ecc?.nomeCampo
                    ?: if (qualificata) Naming.column("${o.alias} ${c.nomeCampo}") else fisica
                CampoEffettivo(
                    occorrenzaId = o.id,
                    alias = o.alias,
                    ruolo = o.ruolo,
                    nomeOrigine = c.nome,
                    colonna = fisica,
                    tipo = c.tipo,
                    isChiave = c.isChiave,
                    nomeCampo = nome,
                    escluso = ecc?.escluso == true,
                    derivataDa = c.derivataDa,
                    componente = c.componente,
                    tecnico = c.isChiave || o.prefissiTecnici.any { c.nome.startsWith(it, ignoreCase = true) }


                )
            }
        }
    }

    fun occorrenza(id: UUID): OccorrenzaBozza =
        occorrenze.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Tabella non presente nel dataset")

    // ---------- modifiche ----------

    /**
     * Aggiunge una tabella importata (anche una seconda volta). L'alias parte
     * dal nome logico e, se già usato, diventa nome_2, nome_3... Propone anche
     * le dimensioni e, per i Fatti, le metriche di default.
     */
    fun aggiungiTabella(
        tabella: ImportedTable,
        colonne: List<ImportedColumn>,
        prefissiTecnici: List<String> = emptyList()
    ): DatasetBozza {
        val usati = occorrenze.map { it.alias.lowercase() }.toSet()
        var alias = tabella.nomeLogico
        var n = 2
        while (alias.lowercase() in usati) {
            alias = "${tabella.nomeLogico}_$n"
            n++
        }
        val occ = OccorrenzaBozza(
            id = UUID.randomUUID(),
            importedTableId = tabella.id,
            nomeLogico = tabella.nomeLogico,
            alias = alias.take(AreaTabella.MAX_ALIAS),
            ruolo = tabella.ruolo,
            colonne = colonne,
            prefissiTecnici = prefissiTecnici

        )
        return copy(occorrenze = occorrenze + occ).conProposte(occ.id)
    }

    fun rimuoviTabella(occorrenzaId: UUID): DatasetBozza = copy(
        occorrenze = occorrenze.filterNot { it.id == occorrenzaId },
        dimensioni = dimensioni.filterNot { it.occorrenzaId == occorrenzaId }.toSet(),
        metriche = metriche.filterNot { it.areaTabellaId == occorrenzaId }
    )

    fun rinominaAlias(occorrenzaId: UUID, alias: String): DatasetBozza {
        val nuovo = alias.trim()
        return copy(occorrenze = occorrenze.map { if (it.id == occorrenzaId) it.copy(alias = nuovo) else it })
    }

    /** Rinomina un campo solo in questo dataset. Il nuovo nome è normalizzato (Naming.column). */
    fun rinominaCampo(occorrenzaId: UUID, colonna: String, nuovoNome: String): DatasetBozza =
        conEccezione(occorrenzaId, colonna, EccezioneCampo(nomeCampo = Naming.column(nuovoNome)))

    /** Esclude un campo dal dataset; toglie anche le dimensioni e le metriche che lo usano. */
    fun escludiCampo(occorrenzaId: UUID, colonna: String): DatasetBozza =
        conEccezione(occorrenzaId, colonna, EccezioneCampo(escluso = true)).copy(
            dimensioni = dimensioni.filterNot { it.occorrenzaId == occorrenzaId && it.colonna == colonna }.toSet(),
            metriche = metriche.filterNot { it.areaTabellaId == occorrenzaId && it.colonna == colonna }
        )

    /** Torna al nome di default e al campo incluso. */
    fun ripristinaCampo(occorrenzaId: UUID, colonna: String): DatasetBozza = conEccezione(occorrenzaId, colonna, null)

    /**
     * "Associa questi due campi" (come nel Data manager): il campo B prende il
     * nome del campo A. Se le colonne fisiche hanno nomi diversi l'associazione
     * è per valore (vedi [Associazione.perValore]).
     */
    fun associa(occorrenzaA: UUID, colonnaA: String, occorrenzaB: UUID, colonnaB: String): DatasetBozza {
        val a = campi().firstOrNull { it.occorrenzaId == occorrenzaA && it.colonna == colonnaA }
            ?: throw IllegalArgumentException("Campo '$colonnaA' non trovato")
        return rinominaCampo(occorrenzaB, colonnaB, a.nomeCampo)
    }

    fun impostaDimensione(occorrenzaId: UUID, colonna: String, attiva: Boolean): DatasetBozza {
        val rif = RiferimentoCampo(occorrenzaId, colonna)
        return copy(dimensioni = if (attiva) dimensioni + rif else dimensioni - rif)
    }

    fun aggiungiMetrica(metrica: MetricaBozza): DatasetBozza = copy(metriche = metriche + metrica)

    fun rimuoviMetrica(metricaId: UUID): DatasetBozza = copy(metriche = metriche.filterNot { it.id == metricaId })

    // ---------- calendario ----------

    /** I campi data che hanno campi derivati (anno, mese, giorno...): quelli tra cui scegliere per il calendario. */
    fun campiData(): List<CampoEffettivo> {
        val inclusi = campi().filter { !it.escluso }
        val padri = inclusi.filter { it.derivataDa != null }
            .map { it.occorrenzaId to it.derivataDa!!.lowercase() }
            .toSet()
        return inclusi.filter { it.derivataDa == null && (it.occorrenzaId to it.nomeOrigine.lowercase()) in padri }
    }

    /** I campi derivati del campo data del calendario, per componente ("anno", "mese", "giorno"...). */
    fun campiCalendario(): Map<String, CampoEffettivo> {
        val nome = campoCalendario ?: return emptyMap()
        val data = campiData().firstOrNull { it.nomeCampo == nome } ?: return emptyMap()
        return campi()
            .filter { !it.escluso && it.occorrenzaId == data.occorrenzaId && it.derivataDa?.lowercase() == data.nomeOrigine.lowercase() }
            .associateBy { it.componente ?: "" }
    }

    /** Sceglie il campo data del calendario; i suoi derivati diventano dimensioni (la barra li legge). */
    fun impostaCalendario(nomeCampo: String?): DatasetBozza {
        val scelto = copy(campoCalendario = nomeCampo)
        val derivati = scelto.campiCalendario().values.map { RiferimentoCampo(it.occorrenzaId, it.colonna) }
        return scelto.copy(dimensioni = scelto.dimensioni + derivati)
    }

    private fun conEccezione(occorrenzaId: UUID, colonna: String, ecc: EccezioneCampo?): DatasetBozza {
        occorrenza(occorrenzaId)
        return copy(occorrenze = occorrenze.map { o ->
            if (o.id != occorrenzaId) o
            else o.copy(eccezioni = if (ecc == null) o.eccezioni - colonna else o.eccezioni + (colonna to ecc))
        })
    }

    /**
     * Proposte di default per una tabella appena aggiunta. Dimensioni: ogni
     * campo incluso tranne i numerici non chiave (di norma misure; si possono
     * attivare a mano). Metriche (solo Fatti): "Numero righe" e una somma per
     * ogni colonna numerica non chiave, da togliere a mano se non servono.
     */
    private fun conProposte(occorrenzaId: UUID): DatasetBozza {
        val occ = occorrenza(occorrenzaId)
        val suoi = campi().filter { it.occorrenzaId == occorrenzaId && !it.escluso }
        val nuoveDim = suoi
            .filter { !it.isChiave && !it.numerico && it.derivataDa == null }
            .map { RiferimentoCampo(occorrenzaId, it.colonna) }
        var risultato = copy(dimensioni = dimensioni + nuoveDim)
        if (occ.ruolo == RuoloTabella.FATTI) {
            risultato = risultato.proposteMetriche(occ, suoi)
        }
        return risultato
    }

    private fun proposteMetriche(occ: OccorrenzaBozza, suoi: List<CampoEffettivo>): DatasetBozza {
        val usati = metriche.map { it.nome.lowercase() }.toMutableSet()
        fun nomeLibero(base: String): String {
            var nome = base
            if (nome.lowercase() in usati) nome = "$base (${occ.alias})"
            var n = 2
            while (nome.lowercase() in usati) { nome = "$base (${occ.alias}) $n"; n++ }
            usati += nome.lowercase()
            return nome
        }
        val nuove = mutableListOf<MetricaBozza>()
        nuove += MetricaBozza(UUID.randomUUID(), nomeLibero("Numero righe"), occ.id, null, TipoAggregazione.COUNT)
        suoi.filter { it.numerico && !it.isChiave }.forEach {
            nuove += MetricaBozza(
                UUID.randomUUID(), nomeLibero("Somma ${it.nomeOrigine}"), occ.id, it.colonna, TipoAggregazione.SUM
            )
        }
        return copy(metriche = metriche + nuove)
    }

    // ---------- associazioni ----------

    /**
     * I campi con lo stesso nome in più occorrenze. Confidenza ALTA se il campo
     * è una chiave in almeno una delle tabelle (come il "verde" del Data manager).
     */
    fun associazioni(): List<Associazione> =
        campi().filter { !it.escluso }
            .groupBy { it.nomeCampo }
            .mapNotNull { (nome, lista) ->
                val occ = lista.map { it.occorrenzaId }.distinct()
                if (occ.size < 2) null
                else Associazione(
                    nomeCampo = nome,
                    occorrenze = occ,
                    confidenza = if (lista.any { it.isChiave }) Confidenza.ALTA else Confidenza.MEDIA,
                    perValore = lista.map { it.colonna }.distinct().size > 1
                )
            }
            .sortedBy { it.nomeCampo }

    /**
     * Le chiavi composte del modello: gruppi di due o più campi condivisi
     * dalle stesse tabelle. Sono anche le chiavi sintetiche segnalate da [valida].
     */
    /** Cambia il flag "disconnessa" di una tabella. */
    fun impostaDisconnessa(occorrenzaId: UUID, disconnessa: Boolean): DatasetBozza =
        copy(occorrenze = occorrenze.map { if (it.id == occorrenzaId) it.copy(disconnessa = disconnessa) else it })

    /**
     * Le tabelle disconnesse logicamente: quelle scelte a mano più quelle che il sistema disconnette
     * da solo per rompere i loop (come Qlik), una per loop.
     */
    fun disconnesse(): Set<UUID> = risolviLoop().disconnesse

    /** Le associazioni che contano per la propagazione: senza le tabelle disconnesse. */
    fun associazioniAttive(): List<Associazione> = associazioniSenza(disconnesse())

    private fun associazioniSenza(disc: Set<UUID>): List<Associazione> =
        associazioni().mapNotNull { a ->
            val occ = a.occorrenze.filter { it !in disc }
            if (occ.size < 2) null else a.copy(occorrenze = occ)
        }

    private fun risolviLoop(): RisoluzioneLoop {
        val disc = occorrenze.filter { it.disconnessa }.map { it.id }.toMutableSet()
        val automatiche = mutableListOf<Pair<UUID, List<UUID>>>()
        while (true) {
            val ciclo = primoLoop(disc) ?: break
            // Si disconnette la tabella non-Fatti più recente del ciclo; se sono tutti Fatti, l'ultimo.
            val scelta = ciclo.lastOrNull { occorrenza(it).ruolo != RuoloTabella.FATTI } ?: ciclo.last()
            disc += scelta
            automatiche += scelta to ciclo
        }
        return RisoluzioneLoop(disc, automatiche)
    }

    /** Le tabelle (nell'ordine del dataset) del primo ciclo del grafo senza le tabelle [disc], o null se non ci sono loop. */
    private fun primoLoop(disc: Set<UUID>): List<UUID>? {
        val attive = occorrenze.filter { it.id !in disc }
        if (attive.size < 2) return null
        val indice = attive.mapIndexed { i, o -> o.id to i }.toMap()
        val n = attive.size
        val chiavi = associazioniSenza(disc).groupBy { it.occorrenze.toSet() }.toList()
        val totale = n + chiavi.size
        val adiacenti = Array(totale) { mutableListOf<Int>() }
        val archi = mutableListOf<Pair<Int, Int>>()
        chiavi.forEachIndexed { j, (tabelle, _) ->
            tabelle.forEach { id ->
                val t = indice.getValue(id)
                adiacenti[t] += n + j
                adiacenti[n + j] += t
                archi += t to (n + j)
            }
        }
        val padre = IntArray(totale) { -1 }
        val profondita = IntArray(totale)
        val visitato = BooleanArray(totale)
        for (inizio in 0 until totale) {
            if (visitato[inizio]) continue
            visitato[inizio] = true
            val coda = ArrayDeque<Int>().apply { add(inizio) }
            while (coda.isNotEmpty()) {
                val u = coda.removeFirst()
                for (v in adiacenti[u]) {
                    if (!visitato[v]) {
                        visitato[v] = true
                        padre[v] = u
                        profondita[v] = profondita[u] + 1
                        coda.add(v)
                    }
                }
            }
        }
        for ((a, b) in archi) {
            if (padre[b] == a || padre[a] == b) continue
            var x = a
            var y = b
            val nodi = mutableSetOf(x, y)
            while (x != y) {
                if (profondita[x] >= profondita[y]) { x = padre[x]; nodi += x } else { y = padre[y]; nodi += y }
            }
            return nodi.filter { it < n }.sorted().map { attive[it].id }
        }
        return null
    }

    /**
     * Le chiavi composte del modello: gruppi di due o più campi condivisi
     * dalle stesse tabelle (senza quelle disconnesse). Sono anche le chiavi
     * sintetiche segnalate da [valida].
     */
    fun chiaviComposte(): List<ChiaveComposta> =
        associazioniAttive()
            .groupBy { it.occorrenze.toSet() }
            .filter { it.value.size > 1 }
            .map { (occorrenze, assoc) ->
                val campi = assoc.map { it.nomeCampo }.sorted()
                ChiaveComposta("chiave__" + campi.joinToString("__"), campi, occorrenze)
            }
            .sortedBy { it.nome }

    // ---------- validazione ----------

    /**
     * Controlla il modello. Errori (bloccano il salvataggio): nome, almeno un
     * Fatti, alias unici, campi duplicati nella stessa tabella, metriche
     * valide, LOOP. Avvisi: chiavi sintetiche (soglie di Qlik: più di 5 campi,
     * più di 10 chiavi) e tabelle non collegate. Le chiavi sintetiche annidate
     * non sono rilevate.
     *
     * Un loop è un ciclo nel grafo tabelle-campi condivisi. Campi condivisi
     * dallo stesso insieme di tabelle formano UNA sola chiave (sintetica se
     * più di uno): non sono un loop. Un campo condiviso da tre tabelle non è
     * un loop.
     */
    fun valida(): EsitoValidazione {
        val errori = mutableListOf<Problema>()
        val avvisi = mutableListOf<Problema>()

        if (nome.isBlank()) errori += Problema("Il nome del dataset è obbligatorio")
        if (occorrenze.none { it.ruolo == RuoloTabella.FATTI }) {
            errori += Problema("Serve almeno una tabella Fatti")
        }
        occorrenze.groupBy { it.alias.lowercase() }.filter { it.value.size > 1 }.forEach { (_, v) ->
            errori += Problema("L'alias '${v.first().alias}' è usato da più tabelle", tabelle = v.map { it.alias })
        }
        occorrenze.filter { it.alias.isBlank() }.forEach {
            errori += Problema("Una tabella ha l'alias vuoto", tabelle = listOf(it.nomeLogico))
        }

        val inclusi = campi().filter { !it.escluso }
        inclusi.groupBy { it.occorrenzaId to it.nomeCampo }.filter { it.value.size > 1 }.forEach { (chiave, v) ->
            errori += Problema(
                "Nella tabella '${v.first().alias}' due colonne hanno lo stesso nome di campo '${chiave.second}': rinomina o escludi una delle due",
                campi = listOf(chiave.second), tabelle = listOf(v.first().alias)
            )
        }

        validaGrafo(errori, avvisi)
        validaMetriche(inclusi, errori)
        return EsitoValidazione(errori, avvisi)
    }

    private fun validaMetriche(inclusi: List<CampoEffettivo>, errori: MutableList<Problema>) {
        metriche.forEach { m ->
            val occ = occorrenze.firstOrNull { it.id == m.areaTabellaId }
            if (m.nome.isBlank()) {
                errori += Problema("Una metrica non ha il nome")
            }
            if (occ == null || occ.ruolo != RuoloTabella.FATTI) {
                errori += Problema("La metrica '${m.nome}' non è collegata a una tabella Fatti")
            } else if (m.tipo != TipoAggregazione.COUNT) {
                val c = inclusi.firstOrNull { it.occorrenzaId == occ.id && it.colonna == m.colonna }
                val soloNumerici = m.tipo in setOf(
                    TipoAggregazione.SUM, TipoAggregazione.AVG, TipoAggregazione.MIN, TipoAggregazione.MAX
                )
                if (c == null) {
                    errori += Problema("La metrica '${m.nome}' usa una colonna non disponibile nel dataset")
                } else if (soloNumerici && !c.numerico) {
                    errori += Problema("La metrica '${m.nome}' richiede un campo numerico", campi = listOf(c.nomeCampo))
                }
            }
        }
        metriche.groupBy { it.nome.trim().lowercase() }.filter { it.key.isNotEmpty() && it.value.size > 1 }
            .forEach { (_, v) -> errori += Problema("Due metriche si chiamano '${v.first().nome}'") }
    }

    private fun validaGrafo(errori: MutableList<Problema>, avvisi: MutableList<Problema>) {
        if (occorrenze.isEmpty()) return
        val indice = occorrenze.mapIndexed { i, o -> o.id to i }.toMap()
        val n = occorrenze.size

        // Chiavi: campi condivisi con lo stesso insieme di tabelle = una chiave.
        val chiavi: List<Pair<Set<UUID>, List<Associazione>>> =
            associazioni().groupBy { it.occorrenze.toSet() }.toList()

        // Nodi 0..n-1 = tabelle, n.. = chiavi. Archi tabella-chiave.
        val totale = n + chiavi.size
        val adiacenti = Array(totale) { mutableListOf<Int>() }
        val archi = mutableListOf<Pair<Int, Int>>()
        chiavi.forEachIndexed { j, (tabelle, _) ->
            tabelle.forEach { id ->
                val t = indice.getValue(id)
                adiacenti[t] += n + j
                adiacenti[n + j] += t
                archi += t to (n + j)
            }
        }

        // Foresta di copertura (BFS) e collegamento con la prima tabella Fatti.
        val padre = IntArray(totale) { -1 }
        val profondita = IntArray(totale)
        val visitato = BooleanArray(totale)
        val partenze = occorrenze.indices.sortedBy { if (occorrenze[it].ruolo == RuoloTabella.FATTI) 0 else 1 }
        var principale: BooleanArray? = null
        for (inizio in partenze) {
            if (visitato[inizio]) continue
            visitato[inizio] = true
            val coda = ArrayDeque<Int>().apply { add(inizio) }
            while (coda.isNotEmpty()) {
                val u = coda.removeFirst()
                for (v in adiacenti[u]) {
                    if (!visitato[v]) {
                        visitato[v] = true
                        padre[v] = u
                        profondita[v] = profondita[u] + 1
                        coda.add(v)
                    }
                }
            }
            if (principale == null) principale = visitato.copyOf()
        }

        // Tabelle non collegate alla prima componente.
        val primo = principale
        if (primo != null && n > 1) {
            val isolate = (0 until n).filter { !primo[it] }.map { occorrenze[it].alias }
            if (isolate.isNotEmpty()) {
                avvisi += Problema(
                    "Tabelle non collegate al resto del modello: ${isolate.joinToString(", ")}",
                    tabelle = isolate
                )
            }
        }

        // Loop: come in Qlik non sono un errore. Una tabella per ciclo viene disconnessa
        // logicamente (le selezioni non passano da lei); l'utente può sceglierla a mano.
        risolviLoop().automatiche.forEach { (scelta, ciclo) ->
            val alias = ciclo.map { occorrenza(it).alias }
            avvisi += Problema(
                "Loop tra le tabelle ${alias.joinToString(", ")}: '${occorrenza(scelta).alias}' è stata disconnessa " +
                        "logicamente (le selezioni non passano da lei). Puoi scegliere tu quale disconnettere.",
                tabelle = alias
            )
        }

        // Chiavi sintetiche (avvisi, come Qlik).
        val sintetiche = chiavi.filter { it.second.size > 1 }
        sintetiche.forEach { (tabelle, assoc) ->
            val alias = tabelle.map { occorrenza(it).alias }
            val nomi = assoc.map { it.nomeCampo }
            avvisi += Problema(
                "Chiave sintetica tra ${alias.joinToString(", ")} sui campi ${nomi.joinToString(", ")}: " +
                        "per evitarla lascia un solo campo in comune",
                campi = nomi, tabelle = alias
            )
            if (nomi.size > MAX_CAMPI_CHIAVE_SINTETICA) {
                avvisi += Problema(
                    "La chiave sintetica tra ${alias.joinToString(", ")} contiene più di $MAX_CAMPI_CHIAVE_SINTETICA campi",
                    campi = nomi, tabelle = alias
                )
            }
        }
        if (sintetiche.size > MAX_CHIAVI_SINTETICHE) {
            avvisi += Problema("Ci sono più di $MAX_CHIAVI_SINTETICHE chiavi sintetiche nel modello")
        }
    }

    companion object {
        /** Soglie degli avvisi sulle chiavi sintetiche (documentazione Qlik, Data manager). */
        const val MAX_CAMPI_CHIAVE_SINTETICA = 5
        const val MAX_CHIAVI_SINTETICHE = 10
        const val MAX_LOOP_SEGNALATI = 5
    }
}