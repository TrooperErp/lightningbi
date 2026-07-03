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
        if (header != null && header.startsWith("Bearer ")) {
            val token = header.removePrefix("Bearer ")
            val claims = jwtService.validate(token)
            if (claims != null) {
                val sessionId = claims["sessionId"] as String
                val session = sessionService.validate(sessionId)
                if (session != null) {
                    val role = claims["role"] as String
                    val authorities = listOf(SimpleGrantedAuthority("ROLE_$role"))
                    val auth = UsernamePasswordAuthenticationToken(claims.subject, null, authorities)
                    SecurityContextHolder.getContext().authentication = auth
                }
            }
        }
        filterChain.doFilter(request, response)
    }
}