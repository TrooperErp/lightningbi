package com.lightning.piattaforma.config

import com.lightning.piattaforma.service.Cronometro
import java.io.Closeable
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Statement
import javax.sql.DataSource

/**
 * DataSource che conta le query e il loro tempo per il Cronometro, con l'etichetta [db]
 * (CH, PG). Il tempo va dalla prima esecuzione dello statement alla sua chiusura: comprende
 * la lettura delle righe. Fuori da un clic non registra niente.
 *
 * Va usato come bean DataSource (non solo nel JdbcTemplate): il transaction manager e il
 * template devono vedere lo stesso DataSource.
 */
class DataSourceMisurato(private val ds: DataSource, private val db: String) : DataSource by ds, Closeable {

    override fun getConnection(): Connection = connessione(ds.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        connessione(ds.getConnection(username, password))

    /** Chiude il pool sottostante allo spegnimento (Spring chiama close sui bean). */
    override fun close() {
        (ds as? AutoCloseable)?.close()
    }

    private fun connessione(con: Connection): Connection =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java), InvocationHandler { proxy, metodo, args ->
            when (metodo.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                else -> when (val r = invoca(con, metodo, args)) {
                    is CallableStatement -> statement(r, CallableStatement::class.java)
                    is PreparedStatement -> statement(r, PreparedStatement::class.java)
                    is Statement -> statement(r, Statement::class.java)
                    else -> r
                }
            }
        }) as Connection

    private fun <S : Statement> statement(st: S, tipo: Class<S>): S {
        var inizio = 0L
        var eseguite = 0
        return tipo.cast(Proxy.newProxyInstance(javaClass.classLoader, arrayOf(tipo), InvocationHandler { proxy, metodo, args ->
            when (metodo.name) {
                "equals" -> return@InvocationHandler proxy === args?.get(0)
                "hashCode" -> return@InvocationHandler System.identityHashCode(proxy)
            }
            if (metodo.name.startsWith("execute")) {
                if (eseguite == 0) inizio = System.nanoTime()
                eseguite++
            }
            try {
                invoca(st, metodo, args)
            } finally {
                if (metodo.name == "close" && eseguite > 0) {
                    Cronometro.registraQuery(db, eseguite, System.nanoTime() - inizio)
                    eseguite = 0
                }
            }
        }))
    }

    /** Chiama il metodo vero; l'eccezione esce com'era (Spring la traduce in base alla SQLException). */
    private fun invoca(target: Any, metodo: Method, args: Array<out Any?>?): Any? =
        try {
            metodo.invoke(target, *(args ?: emptyArray()))
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
}