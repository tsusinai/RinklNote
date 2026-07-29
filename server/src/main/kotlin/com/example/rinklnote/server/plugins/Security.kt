package com.example.rinklnote.server.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

fun Application.configureSecurity() {
    val jwtSecret = System.getenv("JWT_SECRET")
        ?: environment.config.propertyOrNull("jwt.secret")?.getString()
        ?: throw IllegalStateException("JWT secret not configured. Set JWT_SECRET env var.")
    val jwtIssuer = environment.config.propertyOrNull("jwt.issuer")?.getString() ?: "rinklnote-server"
    val jwtAudience = environment.config.propertyOrNull("jwt.audience")?.getString() ?: "rinklnote-app"

    install(Authentication) {
        jwt("auth-jwt") {
            realm = "RinklNote API"
            verifier(
                JWT.require(Algorithm.HMAC256(jwtSecret))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtIssuer)
                    .build()
            )
            validate { credential ->
                val userId = credential.payload.getClaim("userId").asLong()
                if (userId != null) JWTPrincipal(credential.payload) else null
            }
        }
    }
}
