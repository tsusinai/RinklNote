package com.example.rinklnote.server

import com.example.rinklnote.server.plugins.*
import com.example.rinklnote.server.routes.*
import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.QQBotService
import com.example.rinklnote.server.services.QQBotWebSocketClient
import com.example.rinklnote.server.services.TemplateService
import com.example.rinklnote.server.services.UserService
import com.example.rinklnote.server.services.asr.AsrConfig
import com.example.rinklnote.server.services.asr.WhisperAsrService
import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.DefaultNLUService
import com.example.rinklnote.server.services.nlu.LLMParser
import com.example.rinklnote.server.services.nlu.LLMParserConfig
import com.example.rinklnote.server.services.nlu.LearningService
import com.example.rinklnote.server.services.nlu.RuleBasedParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.routing.*
import kotlinx.coroutines.cancel

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(CallLogging)
    configureErrorHandling()
    configureSerialization()
    configureDatabase()
    configureSecurity()

    val jwtSecret = requireStrongSecret(
        System.getenv("JWT_SECRET") ?: environment.config.propertyOrNull("jwt.secret")?.getString(),
        "JWT_SECRET"
    )
    val jwtIssuer = System.getenv("JWT_ISSUER")
        ?: environment.config.propertyOrNull("jwt.issuer")?.getString()
        ?: "rinklnote-server"
    val jwtAudience = System.getenv("JWT_AUDIENCE")
        ?: environment.config.propertyOrNull("jwt.audience")?.getString()
        ?: "rinklnote-app"
    val webhookSecret = requireStrongSecret(
        System.getenv("WEBHOOK_SECRET") ?: environment.config.propertyOrNull("webhook.secret")?.getString(),
        "WEBHOOK_SECRET"
    )

    val userService = UserService(jwtSecret, jwtIssuer, jwtAudience)
    val billService = BillService()
    val budgetService = BudgetService()

    val deepseekApiKey = System.getenv("DEEPSEEK_API_KEY")
        ?: environment.config.propertyOrNull("deepseek.apiKey")?.getString()
        ?: throw IllegalStateException("DeepSeek API key not configured. Set DEEPSEEK_API_KEY env var.")
    val deepseekBaseUrl = System.getenv("DEEPSEEK_BASE_URL")
        ?: environment.config.propertyOrNull("deepseek.baseUrl")?.getString()
        ?: "https://api.deepseek.com"
    val deepseekModel = System.getenv("DEEPSEEK_MODEL")
        ?: environment.config.propertyOrNull("deepseek.model")?.getString()
        ?: "deepseek-chat"

    val llmTimeoutMs = System.getenv("LLM_TIMEOUT_MS")?.toLongOrNull()
        ?: environment.config.propertyOrNull("deepseek.timeoutMs")?.getString()?.toLongOrNull()
        ?: 10000L

    val llmParser = LLMParser(LLMParserConfig(apiKey = deepseekApiKey, baseUrl = deepseekBaseUrl, model = deepseekModel, timeoutMs = llmTimeoutMs))

    val nluService = DefaultNLUService(
        ruleBasedParser = RuleBasedParser(),
        llmParser = llmParser,
        billService = billService
    )

    // Learning service
    val learningIntervalMin = System.getenv("LEARNING_INTERVAL_MIN")?.toLongOrNull()
        ?: environment.config.propertyOrNull("learning.intervalMin")?.getString()?.toLongOrNull()
        ?: 60
    val anomalyThreshold = System.getenv("ANOMALY_THRESHOLD")?.toDoubleOrNull()
        ?: environment.config.propertyOrNull("anomaly.threshold")?.getString()?.toDoubleOrNull()
        ?: 1.5
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    LearningService(llmParser, learningIntervalMin, log).start(appScope)

    val insightService = InsightService(
        llmParser = llmParser,
        billService = billService,
        anomalyThreshold = anomalyThreshold
    )

    // QQ Official Bot — config stored in DB, managed via Web UI
    val qqBotService = QQBotService()
    qqBotService.loadFromDb()
    if (qqBotService.isConfigured()) {
        log.info("QQ Bot service loaded from DB (AppID: ${qqBotService.getMaskedAppId()})")
    } else {
        log.info("QQ Bot not yet configured — use Web settings page")
    }

    // QQ Official Bot gateway — long WebSocket connection (default receiving mode).
    // Shares message processing with the HTTP webhook path via QQMessageProcessor.
    val qqWsClient = QQBotWebSocketClient(qqBotService, userService, billService, nluService, budgetService, insightService)
    qqWsClient.start(appScope)

    val templateService = TemplateService()

    // Speech-to-text (optional). Unconfigured → Android falls back to on-device recognition.
    val asrApiKey = System.getenv("ASR_API_KEY")
        ?: environment.config.propertyOrNull("asr.apiKey")?.getString()
    val asrBaseUrl = System.getenv("ASR_BASE_URL")
        ?: environment.config.propertyOrNull("asr.baseUrl")?.getString()
        ?: "https://api.openai.com"
    val asrModel = System.getenv("ASR_MODEL")
        ?: environment.config.propertyOrNull("asr.model")?.getString()
        ?: "whisper-1"
    val asrTimeoutMs = System.getenv("ASR_TIMEOUT_MS")?.toLongOrNull()
        ?: environment.config.propertyOrNull("asr.timeoutMs")?.getString()?.toLongOrNull()
        ?: 30_000L
    val asrService = WhisperAsrService(
        if (asrApiKey.isNullOrBlank()) null
        else AsrConfig(asrApiKey, asrBaseUrl, asrModel, asrTimeoutMs)
    )

    // Release HTTP clients and cancel the app coroutine scope (LearningService loop,
    // async webhook processing) on graceful shutdown.
    environment.monitor.subscribe(ApplicationStopped) {
        appScope.cancel()
        llmParser.shutdown()
        qqWsClient.shutdown()
        qqBotService.shutdown()
        asrService.shutdown()
    }

    routing {
        authRoutes(userService)
        billRoutes(billService, nluService)
        transcribeRoutes(asrService)
        budgetRoutes(budgetService)
        correctionRoutes()
        keywordRoutes()
        qqWebhookRoutes(webhookSecret, userService, billService, nluService)
        insightRoutes(insightService)
        qqBotWebhookRoutes(qqBotService, userService, billService, nluService, budgetService, insightService)
        qqBotManageRoutes(qqBotService, userService)
        templateRoutes(templateService)
    }
}
