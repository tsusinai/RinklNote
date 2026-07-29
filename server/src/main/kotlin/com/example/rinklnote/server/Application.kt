package com.example.rinklnote.server

import com.example.rinklnote.server.plugins.*
import com.example.rinklnote.server.routes.*
import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.UserService
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.routing.*

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(CallLogging)
    configureSerialization()
    configureDatabase()
    configureSecurity()

    val jwtSecret = System.getenv("JWT_SECRET")
        ?: environment.config.propertyOrNull("jwt.secret")?.getString()
        ?: throw IllegalStateException("JWT secret not configured. Set JWT_SECRET env var.")
    val jwtIssuer = System.getenv("JWT_ISSUER")
        ?: environment.config.propertyOrNull("jwt.issuer")?.getString()
        ?: "rinklnote-server"
    val jwtAudience = System.getenv("JWT_AUDIENCE")
        ?: environment.config.propertyOrNull("jwt.audience")?.getString()
        ?: "rinklnote-app"
    val webhookSecret = System.getenv("WEBHOOK_SECRET")
        ?: environment.config.propertyOrNull("webhook.secret")?.getString()
        ?: throw IllegalStateException("Webhook secret not configured. Set WEBHOOK_SECRET env var.")

    val userService = UserService(jwtSecret, jwtIssuer, jwtAudience)
    val billService = BillService()

    routing {
        authRoutes(userService)
        billRoutes(billService)
        correctionRoutes()
        keywordRoutes()
        qqWebhookRoutes(webhookSecret, userService, billService)
    }
}
