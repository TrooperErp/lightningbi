package com.lightning.bi.repository

import com.lightning.bi.model.SourceConnection
import java.util.UUID

interface SourceConnectionRepository {
    fun findAll(): List<SourceConnection>
    fun findById(id: UUID): SourceConnection?
    fun findByNome(nome: String): SourceConnection?

    /** Inserisce la connessione, o la aggiorna se l'id esiste già. La data di creazione non cambia. */
    fun save(connection: SourceConnection)

    fun delete(id: UUID)
}