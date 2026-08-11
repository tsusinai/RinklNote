package com.example.rinklnote.server.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*

// Placeholder values shipped in application.conf. Shipping a predictable secret
// is worse than failing to start, so startup rejects them outright.
private val INSECURE_SECRETS = setOf(
    "change-me-in-production-use-random-256-bit-key",
    "change-me-webhook-secret",
)

/**
 * Secrets are read from env (preferred) or application.conf. Fail fast if the
 * value is blank or still one of the placeholder strings from the repo config.
 */
fun requireStrongSecret(secret: String?, name: String): String {
    if (secret.isNullOrBlank() || secret in INSECURE_SECRETS) {
        throw IllegalStateException(
            "$name not configured securely. Set the $name env var to a strong random value."
        )
    }
    return secret
}

fun Application.configureSecurity() {
    val jwtSecret = requireStrongSecret(
        System.getenv("JWT_SECRET") ?: environment.config.propertyOrNull("jwt.secret")?.getString(),
        "JWT_SECRET"
    )
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
