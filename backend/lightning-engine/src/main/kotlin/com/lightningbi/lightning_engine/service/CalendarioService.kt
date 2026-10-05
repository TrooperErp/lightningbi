package com.lightningbi.lightning_engine.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields

/**
 * Un componente del calendario derivabile da una data.
 *
 * Come in Qlik (campi derivati / autoCalendar) da ogni campo di tipo data si
 * ricavano campi veri del modello: anno, mese, giorno... Il valore è DUALE:
 * un testo che si vede ("Set") e un numero con cui si ordina (9).
 */
enum class ComponenteCalendario(val codice: String, val etichetta: String) {
    ANNO("anno", "Anno"),
    MESE("mese", "Mese"),
    GIORNO("giorno", "Giorno"),
    TRIMESTRE("trimestre", "Trimestre"),
    SETTIMANA("settimana", "Settimana");

    companion object {
        fun daCodice(codice: String): ComponenteCalendario? =
            entries.firstOrNull { it.codice.equals(codice.trim(), ignoreCase = true) }
    }
}

/** Un valore derivato: il testo mostrato e il numero con cui si ordina. */
data class ValoreCalendario(val testo: String, val numero: BigDecimal)

/**
 * La definizione del calendario comune a tutti i campi data (come la
 * "calendar definition" di Qlik), in configurazione:
 *
 * - `lbi.calendar.componenti`: quali campi derivare da ogni data
 *   (default `anno,mese,giorno`);
 * - `lbi.calendar.mesi`: i nomi dei mesi mostrati (default italiano).
 *
 * Nessun nome di colonna della sorgente: vale per ogni gestionale.
 */
@Service
class CalendarioService(
    @Value("\${lbi.calendar.componenti:anno,mese,giorno}") componentiConfigurati: String,
    @Value("\${lbi.calendar.mesi:Gen,Feb,Mar,Apr,Mag,Giu,Lug,Ago,Set,Ott,Nov,Dic}") mesiConfigurati: String
) {
    private val componenti: List<ComponenteCalendario> = componentiConfigurati
        .split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { codice ->
            ComponenteCalendario.daCodice(codice)
                ?: throw IllegalStateException(
                    "lbi.calendar.componenti: componente sconosciuto '$codice' " +
                            "(ammessi: ${ComponenteCalendario.entries.joinToString(", ") { it.codice }})"
                )
        }
        .distinct()

    private val mesi: List<String> = mesiConfigurati.split(',').map { it.trim() }.also {
        check(it.size == 12 && it.none { m -> m.isEmpty() }) {
            "lbi.calendar.mesi deve contenere 12 nomi separati da virgola (trovati ${it.size})"
        }
    }

    /** I componenti da derivare da ogni campo data, nell'ordine configurato. */
    fun componenti(): List<ComponenteCalendario> = componenti


    /** I nomi dei mesi mostrati, da gennaio a dicembre. */
    fun nomiMesi(): List<String> = mesi

    /**
     * La colonna è di tipo data? Si guarda la prima parola del tipo
     * ("datetime2", "timestamp with time zone"). Un valore che poi non è una
     * data (es. il "timestamp" di SQL Server, che è binario) dà derivati vuoti.
     */
    fun isData(tipo: String): Boolean {
        val base = tipo.trim().lowercase().split(' ', '(').firstOrNull().orEmpty()
        return base in tipiData
    }

    /** Nome fisico del campo derivato da una colonna data (già nome fisico): `data_documento_anno`. */
    fun nomeFisico(fisicaOrigine: String, componente: ComponenteCalendario): String =
        Naming.column("${fisicaOrigine}_${componente.codice}")

    /** Nome del campo derivato come lo mostra la sorgente di nomi: `Data Documento Anno`. */
    fun nomeLogico(nomeOrigine: String, componente: ComponenteCalendario): String =
        "$nomeOrigine ${componente.etichetta}"

    /** La data di un valore letto dalla sorgente, o null se non è una data. */
    fun daValore(valore: Any?): LocalDateTime? = when (valore) {
        null -> null
        is Timestamp -> valore.toLocalDateTime()
        is java.sql.Date -> valore.toLocalDate().atStartOfDay()
        is java.util.Date -> LocalDateTime.ofInstant(valore.toInstant(), ZoneOffset.UTC)
        is LocalDateTime -> valore
        is LocalDate -> valore.atStartOfDay()
        is OffsetDateTime -> valore.toLocalDateTime()
        is Instant -> LocalDateTime.ofInstant(valore, ZoneOffset.UTC)
        is String -> analizza(valore)
        else -> null
    }

    /** Il valore di un componente per una data. */
    fun derivato(componente: ComponenteCalendario, data: LocalDateTime): ValoreCalendario {
        val d = data.toLocalDate()
        return when (componente) {
            ComponenteCalendario.ANNO -> ValoreCalendario(d.year.toString(), BigDecimal(d.year))
            ComponenteCalendario.MESE -> ValoreCalendario(mesi[d.monthValue - 1], BigDecimal(d.monthValue))
            ComponenteCalendario.GIORNO -> ValoreCalendario(d.dayOfMonth.toString(), BigDecimal(d.dayOfMonth))
            ComponenteCalendario.TRIMESTRE -> {
                val t = d.get(IsoFields.QUARTER_OF_YEAR)
                ValoreCalendario("T$t", BigDecimal(t))
            }
            ComponenteCalendario.SETTIMANA -> {
                val s = d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                ValoreCalendario(s.toString(), BigDecimal(s))
            }
        }
    }

    /**
     * La data come testo canonico, ordinabile in ordine alfabetico: `2026-10-01`,
     * oppure `2026-10-01 14:30:00` se ha un'ora diversa dalla mezzanotte.
     */
    fun testoCanonico(data: LocalDateTime): String =
        if (data.toLocalTime() == java.time.LocalTime.MIDNIGHT) data.toLocalDate().toString()
        else data.format(formatoOra)

    /** Il testo canonico di una data, per mostrarlo (default `dd/MM/yyyy`); altri testi restano uguali. */
    fun formatta(testo: String): String {
        val data = analizza(testo) ?: return testo
        return if (testo.length <= 10) data.toLocalDate().format(formatoVisto)
        else data.format(formatoVistoOra)
    }

    private fun analizza(testo: String): LocalDateTime? {
        val t = testo.trim()
        if (t.length < 10 || t[4] != '-' || t[7] != '-') return null
        return try {
            if (t.length == 10) LocalDate.parse(t).atStartOfDay()
            else LocalDateTime.parse(t.replace(' ', 'T').substringBefore('.').substringBefore('Z').substringBefore('+'))
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        val tipiData = setOf("date", "datetime", "datetime2", "smalldatetime", "datetimeoffset", "timestamp", "timestamptz")
        val formatoOra: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val formatoVisto: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val formatoVistoOra: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
    }
}