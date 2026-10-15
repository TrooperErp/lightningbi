package com.lightining.service.setanalysis

import com.lightning.bi.service.setanalysis.Elemento
import com.lightning.bi.service.setanalysis.ErroreSetAnalysis
import com.lightning.bi.service.setanalysis.Identificatore
import com.lightning.bi.service.setanalysis.Modificatore
import com.lightning.bi.service.setanalysis.Operatore
import com.lightning.bi.service.setanalysis.SetAnalysisParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

class SetAnalysisParserTest {

    private fun analizza(t: String) = SetAnalysisParser.analizza(t)

    @Test
    fun `esclusione con jolly`() {
        val e = analizza("{\$<Codice_Articolo -= {\"TRASP*\"}>}")
        assertEquals(Identificatore.CORRENTI, e.identificatore)
        assertEquals(listOf(Modificatore("Codice_Articolo", Operatore.ESCLUDI, listOf(Elemento.Ricerca("TRASP*")))), e.modificatori)
    }

    @Test
    fun `identificatore omesso vale dollaro`() {
        assertEquals(Identificatore.CORRENTI, analizza("{<A = {1}>}").identificatore)
        assertEquals(Identificatore.TUTTO, analizza("{1<A = {1}>}").identificatore)
        assertEquals(Identificatore.TUTTO, analizza("{1}").identificatore)
    }

    @Test
    fun `campo con spazi, letterali con apice, piu campi`() {
        val e = analizza("{<[Codice Tipo Documento] = {'C-FATRIEPDDT', 'L''X'}, Anno *= {2025, 2026}>}")
        assertEquals("Codice Tipo Documento", e.modificatori[0].campo)
        assertEquals(listOf(Elemento.Letterale("C-FATRIEPDDT"), Elemento.Letterale("L'X")), e.modificatori[0].elementi)
        assertEquals(Operatore.INTERSECA, e.modificatori[1].operatore)
        assertEquals(listOf(Elemento.Numero(BigDecimal(2025)), Elemento.Numero(BigDecimal(2026))), e.modificatori[1].elementi)
    }

    @Test
    fun `confronti numerici`() {
        val e = analizza("{<Anno = {\">=2025<=2026\"}, Mese = {\"<10\"}>}")
        assertEquals(Elemento.Confronto(BigDecimal(2025), true, BigDecimal(2026), true), e.modificatori[0].elementi[0])
        assertEquals(Elemento.Confronto(null, false, BigDecimal(10), false), e.modificatori[1].elementi[0])
    }

    @Test
    fun `insieme vuoto e aggiunta`() {
        val e = analizza("{<A += {}>}")
        assertEquals(Operatore.AGGIUNGI, e.modificatori[0].operatore)
        assertTrue(e.modificatori[0].elementi.isEmpty())
    }

    @Test
    fun `il testo canonico si rilegge uguale`() {
        listOf(
            "{\$<Codice_Articolo -= {\"TRASP*\"}>}",
            "{1<[Codice Tipo Documento] = {'A', 'B'}, Anno = {\">=2025\"}>}",
            "{\$}"
        ).forEach { t ->
            val e = analizza(t)
            assertEquals(e, analizza(e.testo()))
        }
    }

    @Test
    fun `livelli non ancora supportati si rifiutano con un messaggio chiaro`() {
        assertThrows<ErroreSetAnalysis> { analizza("{\$-1}") }
        assertThrows<ErroreSetAnalysis> { analizza("{\$<A = P(B)>}") }
        assertThrows<ErroreSetAnalysis> { analizza("{\$<A = {\"=Sum(x)>0\"}>}") }
        assertThrows<ErroreSetAnalysis> { analizza("{\$<A /= {1}>}") }
    }

    @Test
    fun `errori di sintassi`() {
        assertThrows<ErroreSetAnalysis> { analizza("{\$<A = {'x'}") }
        assertThrows<ErroreSetAnalysis> { analizza("{\$<A {'x'}>}") }
        assertThrows<ErroreSetAnalysis> { analizza("{\$<A = {'x'}, a = {'y'}>}") }
        assertThrows<ErroreSetAnalysis> { analizza("{\$<A = {'x}>}") }
        assertThrows<ErroreSetAnalysis> { analizza("{\$<A = {\">=abc\"}>}") }
    }
}