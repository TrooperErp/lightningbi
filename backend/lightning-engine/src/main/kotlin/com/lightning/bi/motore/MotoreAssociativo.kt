package com.lightning.bi.motore

import com.lightning.bi.model.AggregateRequest
import com.lightning.bi.model.AggregateResult
import com.lightning.bi.model.VersionSnapshot
import com.lightning.bi.service.ContatoreCampo
import com.lightning.bi.service.SezioneAccessoService
import com.lightning.bi.service.ValoreFiltro
import java.util.UUID

/**
 * Il motore associativo: tutto ciò che dipende dalle selezioni (stati, contatori, valori,
 * aggregati). Le pagine e i servizi parlano solo con lui.
 *
 * Due implementazioni: ClickHouse (quella di oggi) e in memoria (Motore 2.0). Restano
 * entrambe fino a fine collaudo, per il confronto incrociato e per tornare indietro.
 *
 * Fuori dal motore: etichette dei valori (symbol table), ordinamento, gerarchia della pivot,
 * formattazione. Non dipendono dalle selezioni.
 *
 * Le selezioni sono per id di dimensione (come in UserPivotState); il valore 0 è
 * "non definito". Il [vincolo] del section access va calcolato nel thread della pagina
 * (legge la sessione) e passato: così ogni metodo si può chiamare da un pool.
 */
interface MotoreAssociativo {

    /** Nome per log e confronti: "clickhouse", "memoria". */
    val nome: String

    /** Contatori (possibili/totali/selezionati) dei campi richiesti; null = tutti. */
    fun contatori(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioni: Collection<UUID>?,
        vincolo: SezioneAccessoService.Vincolo?
    ): Map<UUID, ContatoreCampo>

    /** La finestra [offset, offset + limite) dei valori di un campo, con lo stato, ordinata come Qlik. */
    fun valori(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        ricerca: String?,
        offset: Int,
        limite: Int,
        vincolo: SezioneAccessoService.Vincolo?
    ): List<ValoreFiltro>

    /** Quanti valori ha l'elenco di un campo, con la ricerca applicata. */
    fun numeroValori(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        ricerca: String?,
        vincolo: SezioneAccessoService.Vincolo?
    ): Int

    /** Gli id dei valori possibili di un campo, senza la sua selezione ("Seleziona tutti"). */
    fun idPossibili(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        vincolo: SezioneAccessoService.Vincolo?
    ): Set<Long>

    /** Le dimensioni di cui togliere la selezione perché un valore grigio diventi possibile. */
    fun conflitti(
        areaId: UUID,
        selezioni: Map<UUID, Set<Long>>,
        dimensioneId: UUID,
        valoreId: Long,
        vincolo: SezioneAccessoService.Vincolo?
    ): Set<UUID>

    /** Aggregati (pivot e grafici), set analysis compresa. */
    fun aggrega(
        richiesta: AggregateRequest,
        versioni: VersionSnapshot,
        vincolo: SezioneAccessoService.Vincolo?
    ): AggregateResult
}