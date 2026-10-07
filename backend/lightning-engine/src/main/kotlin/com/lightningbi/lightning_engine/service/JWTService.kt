package com.lightningbi.lightning_engine.service

import io.jsonwebtoken.Claims
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.util.Date
import java.util.UUID
import javax.crypto.SecretKey

@Service
class JwtService(
    @Value("\${lightningbi.security.jwt-secret}") secret: String,
    @Value("\${lightningbi.security.session-hours:4}") private val ore: Long
) {

    private val key: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray())

    fun generate(userId: UUID, sessionId: String, roleName: String): String =
        Jwts.builder()
            .subject(userId.toString())
            .claim("sessionId", sessionId)
            .claim("role", roleName)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + ore * 3600 * 1000))
            .signWith(key)
            .compact()

    fun validate(token: String): Claims? =
        try {
            Jwts.parser().verifyWith(key).build()
                .parseSignedClaims(token).payload
        } catch (e: Exception) {
            null
        }
}