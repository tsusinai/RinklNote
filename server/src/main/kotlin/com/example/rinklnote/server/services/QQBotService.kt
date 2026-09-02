package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.BotConfigTable
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class AccessTokenResponse(val access_token: String, val expires_in: Long)

@Serializable
data class SendMessageRequest(val content: String, val msg_id: String? = null)

class QQBotService {
    private val logger = LoggerFactory.getLogger(QQBotService::class.java)
    private val baseUrl = "https://api.sgroup.qq.com"
    // Access token is issued by the auth host (bots.qq.com), NOT the api gateway.
    private val authUrl = "https://bots.qq.com"

    // explicitNulls=false: active C2C/group pushes must OMIT msg_id entirely — QQ rejects a
    // client-generated msg_id with 40034024 "msg_id无效或越权". msg_id is only valid as a passive
    // reply key (the received event's id), so a null msg_id signals "active push" and gets dropped.
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false }

    private val client = HttpClient(CIO) {
        install(HttpTimeout) { requestTimeoutMillis = 15_000 }
        install(ContentNegotiation) {
            json(json)
        }
    }

    @Volatile private var appId: String? = null
    @Volatile private var clientSecret: String? = null

    // Ed25519 keys (lazily initialized on configure)
    @Volatile private var ed25519Seed: ByteArray? = null
    @Volatile private var privateKey: Ed25519PrivateKeyParameters? = null
    @Volatile private var publicKey: ByteArray? = null

    // Access token cache
    @Volatile private var accessToken: String? = null
    @Volatile private var tokenExpiresAt: Long = 0
    private val tokenMutex = Mutex()

    // Bind codes
    private val bindCodes = ConcurrentHashMap<String, BindEntry>()
    private val random = SecureRandom()

    data class BindEntry(val openid: String, val expiresAt: Long)

    // ── Configuration ──

    fun isConfigured(): Boolean {
        return appId != null && clientSecret != null &&
               appId!!.isNotBlank() && clientSecret!!.isNotBlank()
    }

    fun configure(appId: String, clientSecret: String) {
        require(appId.isNotBlank() && clientSecret.isNotBlank()) {
            "AppID and ClientSecret must not be blank"
        }
        this.appId = appId
        this.clientSecret = clientSecret

        // Re-derive Ed25519 keys
        val seedBytes = clientSecret.toByteArray(Charsets.UTF_8)
        val md = java.security.MessageDigest.getInstance("SHA-512")
        val digest = md.digest(seedBytes)
        ed25519Seed = digest.copyOf(32)

        privateKey = Ed25519PrivateKeyParameters(ed25519Seed, 0)
        publicKey = privateKey!!.generatePublicKey().encoded

        // Reset cached token
        accessToken = null
        tokenExpiresAt = 0

        logger.info("QQBotService configured (AppID: ${appId.take(4)}...)")
    }

    fun loadFromDb() {
        try {
            transaction {
                val aid = BotConfigTable.selectAll()
                    .where { BotConfigTable.key eq "app_id" }
                    .singleOrNull()?.get(BotConfigTable.value)
                val secret = BotConfigTable.selectAll()
                    .where { BotConfigTable.key eq "client_secret" }
                    .singleOrNull()?.get(BotConfigTable.value)
                if (aid != null && secret != null && aid.isNotBlank() && secret.isNotBlank()) {
                    configure(aid, secret)
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to load QQ Bot config from DB: ${e.message}")
        }
    }

    fun saveToDb(appId: String, clientSecret: String) {
        transaction {
            listOf("app_id" to appId, "client_secret" to clientSecret).forEach { (k, configVal) ->
                val existing = BotConfigTable.selectAll().where { BotConfigTable.key eq k }.singleOrNull()
                if (existing != null) {
                    BotConfigTable.update({ BotConfigTable.key eq k }) {
                        it[BotConfigTable.value] = configVal
                    }
                } else {
                    BotConfigTable.insert {
                        it[BotConfigTable.key] = k
                        it[BotConfigTable.value] = configVal
                    }
                }
            }
        }
        configure(appId, clientSecret)
        logger.info("QQ Bot config saved to DB")
    }

    fun hasSavedConfig(): Boolean {
        return try {
            transaction {
                val aid = BotConfigTable.selectAll()
                    .where { BotConfigTable.key eq "app_id" }
                    .singleOrNull()?.get(BotConfigTable.value)
                !aid.isNullOrBlank()
            }
        } catch (e: Exception) { false }
    }

    fun getMaskedAppId(): String? {
        val aid = appId ?: return null
        return if (aid.length <= 4) aid else aid.take(4) + "****"
    }

    fun getAppId(): String? = appId

    // ── Ed25519 ──

    fun sign(payload: ByteArray): ByteArray {
        val pk = privateKey ?: throw IllegalStateException("QQBotService not configured")
        val signer = Ed25519Signer()
        signer.init(true, pk)
        signer.update(payload, 0, payload.size)
        return signer.generateSignature()
    }

    fun verify(signature: ByteArray, payload: ByteArray): Boolean {
        val pub = publicKey ?: return false
        val verifier = Ed25519Signer()
        verifier.init(false, Ed25519PublicKeyParameters(pub, 0))
        verifier.update(payload, 0, payload.size)
        return verifier.verifySignature(signature)
    }

    // ── Access Token ──

    suspend fun getAccessToken(): String {
        val now = Instant.now().epochSecond
        if (accessToken != null && now < tokenExpiresAt - 300) {
            return accessToken!!
        }
        return tokenMutex.withLock {
            if (accessToken != null && now < tokenExpiresAt - 300) {
                return@withLock accessToken!!
            }
            refreshAccessToken()
        }
    }

    private suspend fun refreshAccessToken(): String {
        val aid = appId ?: throw IllegalStateException("QQBotService not configured")
        val secret = clientSecret ?: throw IllegalStateException("QQBotService not configured")

        logger.info("Refreshing QQ Bot access token...")
        val response: AccessTokenResponse = try {
            client.post("$authUrl/app/getAppAccessToken") {
                contentType(ContentType.Application.Json)
                setBody(mapOf("appId" to aid, "clientSecret" to secret))
            }.body()
        } catch (e: Exception) {
            logger.error("Failed to get access token", e)
            throw RuntimeException("QQ Bot auth failed: ${e.message}")
        }
        accessToken = response.access_token
        tokenExpiresAt = Instant.now().epochSecond + response.expires_in
        logger.info("QQ Bot access token refreshed, expires in ${response.expires_in}s")
        return accessToken!!
    }

    // ── Send Messages ──

    suspend fun sendC2CMessage(openid: String, content: String, msgId: String): Boolean {
        if (!isConfigured()) return false
        return try {
            val token = getAccessToken()
            val resp: HttpResponse = client.post("$baseUrl/v2/users/$openid/messages") {
                contentType(ContentType.Application.Json)
                header("Authorization", "QQBot $token")
                setBody(SendMessageRequest(content = content, msg_id = msgId.takeIf { it.isNotBlank() }))
            }
            logger.info("Sent message to $openid: ${resp.status.value}")
            resp.status.value in 200..299
        } catch (e: Exception) {
            logger.error("Failed to send C2C message to $openid", e)
            false
        }
    }

    suspend fun sendGroupMessage(groupOpenid: String, content: String, msgId: String): Boolean {
        if (!isConfigured()) return false
        return try {
            val token = getAccessToken()
            val resp: HttpResponse = client.post("$baseUrl/v2/groups/$groupOpenid/messages") {
                contentType(ContentType.Application.Json)
                header("Authorization", "QQBot $token")
                setBody(SendMessageRequest(content = content, msg_id = msgId.takeIf { it.isNotBlank() }))
            }
            logger.info("Sent group message to $groupOpenid: ${resp.status.value}")
            resp.status.value in 200..299
        } catch (e: Exception) {
            logger.error("Failed to send group message to $groupOpenid", e)
            false
        }
    }

    // ── Bind Codes ──

    fun generateBindCode(openid: String): String {
        val now = Instant.now().epochSecond
        bindCodes.entries.removeIf { it.value.expiresAt < now }

        val code = "%06d".format(random.nextInt(1_000_000))
        bindCodes[code] = BindEntry(openid, now + 300)
        logger.info("Generated bind code $code for openid $openid")
        return code
    }

    fun consumeBindCode(code: String): String? {
        val entry = bindCodes.remove(code) ?: return null
        if (Instant.now().epochSecond > entry.expiresAt) {
            logger.info("Bind code $code expired")
            return null
        }
        logger.info("Bind code $code consumed, openid=${entry.openid}")
        return entry.openid
    }

    fun shutdown() {
        client.close()
    }
}
