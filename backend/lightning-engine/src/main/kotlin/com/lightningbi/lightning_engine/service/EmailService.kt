package com.lightningbi.lightning_engine.service

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.stereotype.Service

/**
 * Servizio generico per l'invio di email di sistema (alert, notifiche
 * amministrative). Pensato per essere richiamato da qualunque classe
 * che generi un evento rilevante per chi amministra l'applicazione
 * (fallimenti ETL, errori critici, in futuro anche log aggregati) -
 * un solo punto d'ingresso, non un'integrazione ad hoc per ogni
 * sottosistema.
 *
 * Un fallimento nell'invio dell'email NON deve mai propagare
 * un'eccezione al chiamante: un alert che non parte non deve far
 * fallire anche l'operazione che lo ha generato (es. l'ETL notturno).
 * Si logga soltanto.
 */
@Service
class EmailService(
    private val mailSender: JavaMailSender,
    @Value("\${spring.mail.username}") private val fromAddress: String,
    @Value("\${lbi.alert.admin-email}") private val adminEmail: String
) {
    private val log = LoggerFactory.getLogger(EmailService::class.java)

    fun send(to: String, subject: String, body: String) {
        try {
            val message = SimpleMailMessage().apply {
                setFrom(fromAddress)
                setTo(to)
                setSubject(subject)
                setText(body)
            }
            mailSender.send(message)
        } catch (e: Exception) {
            log.error("Invio email fallito (to=$to, subject=$subject)", e)
        }
    }

    /**
     * Scorciatoia per gli alert amministrativi: invia sempre
     * all'indirizzo admin configurato (lbi.alert.admin-email), con un
     * prefisso "[LightningBI ALERT]" nel subject per farli riconoscere
     * subito nella casella di posta.
     */
    fun sendAdminAlert(subject: String, body: String) {
        send(adminEmail, "[LightningBI ALERT] $subject", body)
    }
}