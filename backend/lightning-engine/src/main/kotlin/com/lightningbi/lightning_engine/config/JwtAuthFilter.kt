package com.lightningbi.lightning_engine.config

import com.lightningbi.lightning_engine.service.JwtService
import com.lightningbi.lightning_engine.service.SessionService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Autenticazione per le chiamate HTTP con "Authorization: Bearer ...".
 * L'interfaccia Vaadin non lo usa (ha la sua sessione e il suo cookie): serve
 * per eventuali API. Un token non valido, scaduto, o senza sessione di lavoro
 * valida viene ignorato: la richiesta resta non autenticata.
 */
@Component
class JwtAuthFilter(
    private val jwtService: JwtService,
    private val sessionService: SessionService
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val header = request.getHeader("Authorization")
        if (header != null && header.startsWith("Bearer ") &&
            SecurityContextHolder.getContext().authentication == null
        ) {
            val claims = jwtService.validate(header.removePrefix("Bearer ").trim())
            val sessionId = claims?.get("sessionId", String::class.java)
            val ruolo = claims?.get("role", String::class.java)
            if (claims != null && sessionId != null && ruolo != null &&
                sessionService.validate(sessionId) != null
            ) {
                val autorizzazioni = listOf(SimpleGrantedAuthority("ROLE_$ruolo"))
                SecurityContextHolder.getContext().authentication =
                    UsernamePasswordAuthenticationToken(claims.subject, null, autorizzazioni)
            }
        }
        filterChain.doFilter(request, response)
    }
}