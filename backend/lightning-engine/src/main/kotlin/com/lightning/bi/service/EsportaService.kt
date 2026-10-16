package com.lightning.bi.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

/** Salva i file Excel dei report nella cartella condivisa (NFS/SMB montata nel container). */
@Service
class EsportaService(
    @Value("\${lbi.export.cartella:/report}") private val cartella: String
) {
    /** Percorso della cartella in cui finiscono i file di un anno e un mese. */
    fun cartellaDi(anno: String, mese: String): Path =
        Paths.get(cartella, pulisci(anno), "Fatturati Agenti", pulisci(mese))

    /** Scrive il file (sovrascrive se esiste) e ne restituisce il percorso. */
    fun salva(anno: String, mese: String, nome: String, dati: ByteArray): Path {
        val dir = cartellaDi(anno, mese)
        Files.createDirectories(dir)
        val destinazione = dir.resolve(pulisci(nome) + ".xlsx")
        // Prima in un file temporaneo e poi lo si sposta: chi apre la cartella non vede mai un file a metà.
        val temporaneo = Files.createTempFile(dir, ".esporta-", ".tmp")
        try {
            Files.write(temporaneo, dati)
            Files.move(temporaneo, destinazione, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporaneo)
        }
        return destinazione
    }

    private fun pulisci(testo: String): String =
        testo.replace(Regex("[\\\\/:*?\"<>|]"), " ").trim().trimEnd('.').ifEmpty { "senza nome" }
}