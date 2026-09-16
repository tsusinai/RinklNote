package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.NLUService
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * QQ official bot WebSocket gateway connection.
 *
 * The QQ open platform's default event-receiving mode is a WebSocket long connection
 * (no public webhook URL needed). The bot connects to the gateway, identifies, then
 * receives C2C / group-@ events over the socket. Events have the same payload shape as
 * the HTTP webhook, so [QQMessageProcessor] is shared between both channels.
 *
 * Protocol (from the QQ bot docs):
 *   - connect → op 10 Hello (d.heartbeat_interval)
 *   - send op 2 Identify  {"op":2,"d":{"token":"QQBot {accessToken}","intents":N,"shard":[0,1]}}
 *   - heartbeat op 1 {"op":1,"d":lastSeq} every heartbeat_interval → op 11 ack
 *   - op 0 Dispatch carries events (t = type, d = payload, s = sequence)
 *   - op 7 Reconnect / op 9 Invalid Session / socket close → reconnect with backoff
 */
class QQBotWebSocketClient(
    private val qqBotService: QQBotService,
    private val userService: UserService,
    private val billService: BillService,
    private val nluService: NLUService,
    private val budgetService: BudgetService,
    private val insightService: InsightService
) {
    private val logger = LoggerFactory.getLogger("QQBotWebSocket")

    // Covers both C2C_MESSAGE_CREATE and GROUP_AT_MESSAGE_CREATE.
    private val intents = 1 shl 25
    private val shard = listOf(0, 1)

    // Overridable for sandbox: wss://sandbox.api.sgroup.qq.com/websocket
    private val gatewayUrl = System.getenv("QQ_BOT_WS_URL") ?: "wss://api.sgroup.qq.com/websocket"

    private val json = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) {
        install(WebSockets)
    }

    @Volatile private var job: Job? = null

    /** Sequence number of the last dispatched event; carried in heartbeats. */
    @Volatile private var lastSeq: Long? = null

    /** WS 网关在线状态：已完成 Identify 且 socket 仍在收帧。 */
    @Volatile private var wsConnected: Boolean = false

    /** start() 保存的协程作用域，restart() 热重连时复用。 */
    private var scope: CoroutineScope? = null

    fun start(scope: CoroutineScope) {
        this.scope = scope
        // 注册配置热重连：机器人凭据保存成功后由 QQBotService.saveToDb 触发 restart()。
        qqBotService.onConfigChanged = { restart() }
        if (!qqBotService.isConfigured()) {
            logger.info("QQ Bot not configured — WebSocket gateway connection skipped")
            return
        }
        job = scope.launch {
            connectWithRetry()
        }
        logger.info("QQ Bot WebSocket gateway client started: $gatewayUrl")
    }

    /**
     * 配置热重连：取消旧连接协程，按新配置重新拨号；未配置则仅断开。
     * 由 QQBotService.saveToDb 触发，任何异常只记日志、不影响保存结果。
     */
    fun restart() {
        val s = scope ?: run {
            logger.warn("QQ Bot WebSocket restart skipped: scope not initialized")
            return
        }
        try {
            job?.cancel()
            job = if (qqBotService.isConfigured()) s.launch { connectWithRetry() } else null
            wsConnected = false
            logger.info("QQ Bot WebSocket gateway restart requested (configured=${qqBotService.isConfigured()})")
        } catch (e: Exception) {
            logger.warn("QQ Bot WebSocket restart failed: ${e.message}")
        }
    }

    /** 网关是否在线（已完成 Identify 且 socket 仍在收帧）。 */
    fun isGatewayOnline(): Boolean = wsConnected

    /** 后台重连协程是否在跑（含退避重试中，未必然在线）。 */
    fun isGatewayStarted(): Boolean = job?.isActive == true

    private suspend fun CoroutineScope.connectWithRetry() {
        var backoffMs = 1_000L
        while (isActive) {
            try {
                connectOnce()
                backoffMs = 1_000L
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                wsConnected = false
                logger.warn("QQ Bot WebSocket disconnected (${e.message ?: e.javaClass.simpleName}); retry in ${backoffMs}ms")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    private suspend fun connectOnce() {
        val token = qqBotService.getAccessToken()
        lastSeq = null
        wsConnected = false

        client.webSocket(
            urlString = gatewayUrl,
            request = { header(HttpHeaders.Authorization, "QQBot $token") }
        ) {
            logger.info("QQ Bot WebSocket connected (appId=${qqBotService.getAppId()}); waiting for Hello")

            // op 10 Hello carries heartbeat_interval.
            var heartbeatIntervalMs = 41_000L
            for (frame in incoming) {
                if (frame !is Frame.Text) continue
                val payload = json.parseToJsonElement(frame.readText()).jsonObject
                if (payload["op"]?.jsonPrimitive?.intOrNull == 10) {
                    heartbeatIntervalMs = payload["d"]?.jsonObject
                        ?.get("heartbeat_interval")?.jsonPrimitive?.longOrNull ?: 41_000L
                    break
                }
            }

            outgoing.send(Frame.Text(json.encodeToString<JsonObject>(identifyPayload(token))))
            logger.info("QQ Bot WebSocket Identify sent (intents=$intents)")
            wsConnected = true

            launch { heartbeatLoop(heartbeatIntervalMs) }

            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> handleFrame(frame.readText(), this)
                    is Frame.Ping -> outgoing.send(Frame.Pong(frame.data))
                    else -> {}
                }
            }
            wsConnected = false
            logger.info("QQ Bot WebSocket closed")
        }
    }

    private fun identifyPayload(token: String) = buildJsonObject {
        put("op", 2)
        put("d", buildJsonObject {
            put("token", "QQBot $token")
            put("intents", intents)
            put("shard", JsonArray(listOf(JsonPrimitive(shard[0]), JsonPrimitive(shard[1]))))
        })
    }

    private suspend fun WebSocketSession.heartbeatLoop(intervalMs: Long) {
        while (isActive) {
            val heartbeat = buildJsonObject {
                put("op", 1)
                put("d", lastSeq)
            }
            outgoing.send(Frame.Text(json.encodeToString<JsonObject>(heartbeat)))
            delay(intervalMs)
        }
    }

    private fun handleFrame(text: String, session: WebSocketSession) {
        val payload = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) {
            logger.warn("Ignoring unparseable WS frame")
            return
        }
        when (payload["op"]?.jsonPrimitive?.intOrNull) {
            0 -> {
                lastSeq = payload["s"]?.jsonPrimitive?.longOrNull ?: lastSeq
                val t = payload["t"]?.jsonPrimitive?.content ?: return
                val d = payload["d"]?.jsonObject ?: return
                val msgId = d["id"]?.jsonPrimitive?.content
                val dedupKey = "$t:${msgId ?: ""}"
                // Fire-and-forget processing so the read loop keeps draining frames.
                session.launch {
                    if (QQMessageProcessor.isFirstEvent(dedupKey)) {
                        QQMessageProcessor.process(t, d, qqBotService, userService, billService, nluService, budgetService, insightService)
                    } else {
                        logger.info("Duplicate WS event ignored: $dedupKey")
                    }
                }
            }
            7 -> {
                logger.warn("Gateway requested reconnect (op 7)")
                throw ReconnectException()
            }
            9 -> {
                logger.warn("Invalid session (op 9) — re-identifying on reconnect")
                throw ReconnectException()
            }
            // 10 (Hello) and 11 (heartbeat ack) are handled elsewhere / ignored here.
        }
    }

    fun shutdown() {
        job?.cancel()
        wsConnected = false
        client.close()
        logger.info("QQ Bot WebSocket client stopped")
    }

    private class ReconnectException : Exception("reconnect requested by gateway")
}
