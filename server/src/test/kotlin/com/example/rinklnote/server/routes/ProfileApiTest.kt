package com.example.rinklnote.server.routes

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.rinklnote.server.services.AvatarStorage
import com.example.rinklnote.server.services.QQBotService
import com.example.rinklnote.server.services.TestDatabase
import com.example.rinklnote.server.services.UserService
import com.example.rinklnote.server.tables.UsersTable
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.engine.*
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * 个人资料页（2026-09-17）的 HTTP 级用例：PUT /api/auth/profile 往返、
 * POST /api/auth/avatar 的类型/大小校验与旧文件覆盖清理、/uploads 静态资源可达。
 *
 * 测试基座照 AdminApiTest：不引入 ktor-server-test-host（零新依赖约束），直接
 * embeddedServer(Netty, port = 0) 挂真实路由 + ktor-client-cio 发真请求；数据库整类共用一个
 * H2（TestDatabase.connect 只在 @BeforeClass 调一次），每个用例清表。头像落盘走临时目录，
 * 类结束后递归删除，不污染工作目录。
 */
class ProfileApiTest {

    companion object {
        private const val JWT_SECRET = "unit-test-profile-jwt-secret-0123456789"
        private const val ISSUER = "test-issuer"
        private const val AUDIENCE = "test-audience"
        private const val PHONE = "13800000201"
        // 夹具密码须满足新密码规则（≥6 位 + 大小写字母，2026-09-17 起），否则注册会被 400 拦下
        private const val PASSWORD = "Pass123456"

        private lateinit var server: ApplicationEngine
        private var port: Int = 0
        private lateinit var client: HttpClient
        private lateinit var userService: UserService
        private lateinit var avatarDir: File

        /** 生成一张真实 PNG（2x2 白图），供上传用例使用。 */
        private fun pngBytes(): ByteArray {
            val img = BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, 0xFFFFFF)
            val out = ByteArrayOutputStream()
            ImageIO.write(img, "png", out)
            return out.toByteArray()
        }

        @BeforeClass
        @JvmStatic
        fun startServer() {
            TestDatabase.connect("profileapi")
            transaction { SchemaUtils.create(UsersTable) }

            // 头像临时目录：AvatarStorage 注入 baseDir，类结束后统一清理。
            avatarDir = Files.createTempDirectory("profile-avatar-test").toFile()
            val storage = AvatarStorage(avatarDir.absolutePath)

            userService = UserService(JWT_SECRET, ISSUER, AUDIENCE)
            val qqBotService = QQBotService() // 只构造不 start：authRoutes 仅用到它的绑定码消费

            server = embeddedServer(Netty, port = 0) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
                }
                install(Authentication) {
                    jwt("auth-jwt") {
                        realm = "RinklNote API"
                        verifier(
                            JWT.require(Algorithm.HMAC256(JWT_SECRET))
                                .withAudience(AUDIENCE)
                                .withIssuer(ISSUER)
                                .build()
                        )
                        validate { credential ->
                            val userId = credential.payload.getClaim("userId").asLong()
                            if (userId != null) JWTPrincipal(credential.payload) else null
                        }
                    }
                }
                routing {
                    staticFiles("/uploads", File(avatarDir.absolutePath))
                    authRoutes(userService, qqBotService, storage)
                }
            }.start(wait = false)
            port = runBlocking { server.resolvedConnectors().first().port }
            client = HttpClient(CIO)
        }

        @AfterClass
        @JvmStatic
        fun stopServer() {
            client.close()
            server.stop(100, 1000)
            avatarDir.deleteRecursively()
        }

        /** 注册并拿 token（每个用例各自注册，互不依赖）。 */
        private suspend fun registerAndLogin(): Pair<Long, String> {
            val resp = client.post("http://127.0.0.1:$port/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"phone":"$PHONE","password":"$PASSWORD"}""")
            }
            assertEquals(HttpStatusCode.Created, resp.status)
            val json = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            return json["userId"]!!.jsonPrimitive.content.toLong() to json["token"]!!.jsonPrimitive.content
        }
    }

    @Before
    fun cleanTable() {
        transaction { UsersTable.deleteAll() }
        // 头像目录一并清空：H2 自增 id 不随 deleteAll 回退，各用例 userId 不同、落盘文件也不同。
        File(avatarDir, "avatars").deleteRecursively()
    }

    // ── PUT /api/auth/profile 往返 ──

    @Test
    fun `profile put then me roundtrip carries all fields`() = runBlocking {
        val (_, token) = registerAndLogin()

        val put = client.put("http://127.0.0.1:$port/api/auth/profile") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                """{"nickname":"记账小能手","signature":"少花钱多记账","birthday":"2000-02-29",""" +
                    """"showcaseBadges":["record-30","budget-first"]}"""
            )
        }
        assertEquals(HttpStatusCode.OK, put.status)

        val me = client.get("http://127.0.0.1:$port/api/auth/me") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, me.status)
        val obj = Json.parseToJsonElement(me.bodyAsText()).jsonObject
        assertEquals("记账小能手", obj["nickname"]!!.jsonPrimitive.content)
        assertEquals("少花钱多记账", obj["signature"]!!.jsonPrimitive.content)
        assertEquals("2000-02-29", obj["birthday"]!!.jsonPrimitive.content)
        assertEquals("record-30,budget-first", obj["showcaseBadges"]!!.jsonPrimitive.content)
        // 未上传过头像：avatarUrl 缺省（null）——JSON 里键可缺省也可为 null
        assertTrue(obj["avatarUrl"]?.jsonPrimitive?.isString != true)
    }

    @Test
    fun `profile put is whole replace and clears on null`() = runBlocking {
        val (_, token) = registerAndLogin()

        client.put("http://127.0.0.1:$port/api/auth/profile") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"nickname":"旧昵称","showcaseBadges":["record-7"]}""")
        }
        // 整体替换：nickname/signature 传 null 清除，徽章空列表清空
        val put2 = client.put("http://127.0.0.1:$port/api/auth/profile") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"nickname":null,"signature":null,"birthday":null,"showcaseBadges":[]}""")
        }
        assertEquals(HttpStatusCode.OK, put2.status)
        val obj = Json.parseToJsonElement(put2.bodyAsText()).jsonObject
        assertTrue(obj["nickname"]?.jsonPrimitive?.isString != true)
        val badges = obj["showcaseBadges"]
        assertTrue("清空后徽章字段应为 null/缺失", badges == null || (badges is JsonPrimitive && badges.contentOrNull == null))
    }

    @Test
    fun `profile put trims whitespace and ignores blank badges`() = runBlocking {
        val (_, token) = registerAndLogin()
        val put = client.put("http://127.0.0.1:$port/api/auth/profile") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"nickname":"  空白昵称  ","showcaseBadges":[" record-30 ","  ","record-30"]}""")
        }
        assertEquals(HttpStatusCode.OK, put.status)
        val obj = Json.parseToJsonElement(put.bodyAsText()).jsonObject
        assertEquals("空白昵称", obj["nickname"]!!.jsonPrimitive.content)
        // 去空白 + 去重后只剩一枚
        assertEquals("record-30", obj["showcaseBadges"]!!.jsonPrimitive.content)
    }

    @Test
    fun `profile put rejects over limit badges bad birthday and long nickname`() = runBlocking {
        val (_, token) = registerAndLogin()
        val base = "http://127.0.0.1:$port/api/auth/profile"
        suspend fun put(body: String): HttpResponse = client.put(base) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        // 4 枚徽章 → 400
        assertEquals(HttpStatusCode.BadRequest, put("""{"showcaseBadges":["a-1","b-2","c-3","d-4"]}""").status)
        // 生日格式非法 → 400
        assertEquals(HttpStatusCode.BadRequest, put("""{"birthday":"2000/02/29"}""").status)
        // 昵称超 32 字符 → 400
        assertEquals(HttpStatusCode.BadRequest, put("""{"nickname":"${"长".repeat(33)}"}""").status)
        // 徽章 key 带非法字符 → 400
        assertEquals(HttpStatusCode.BadRequest, put("""{"showcaseBadges":["record_30"]}""").status)
    }

    @Test
    fun `profile put without token is unauthorized`() = runBlocking {
        val resp = client.put("http://127.0.0.1:$port/api/auth/profile") {
            contentType(ContentType.Application.Json)
            setBody("""{"nickname":"x"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // ── POST /api/auth/avatar：校验与文件生命周期 ──

    private suspend fun upload(token: String, bytes: ByteArray, contentType: String): HttpResponse =
        client.post("http://127.0.0.1:$port/api/auth/avatar") {
            bearerAuth(token)
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file", bytes,
                            Headers.build {
                                append(HttpHeaders.ContentType, contentType)
                                append(HttpHeaders.ContentDisposition, "filename=\"avatar.png\"")
                            }
                        )
                    }
                )
            )
        }

    @Test
    fun `avatar upload saves file updates me and serves via static route`() = runBlocking {
        val (_, token) = registerAndLogin()
        val png = pngBytes()

        val resp = upload(token, png, "image/png")
        assertEquals(HttpStatusCode.OK, resp.status)
        val url = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["avatarUrl"]!!.jsonPrimitive.content
        assertTrue(url.startsWith("uploads/avatars/") && "?v=" in url)

        // 落盘校验：一人一文件，字节一致
        val obj = Json.parseToJsonElement(
            client.get("http://127.0.0.1:$port/api/auth/me") { bearerAuth(token) }.bodyAsText()
        ).jsonObject
        assertEquals(url, obj["avatarUrl"]!!.jsonPrimitive.content)
        val saved = File(avatarDir, "avatars").listFiles()!!.filter { !it.name.endsWith(".tmp") }
        assertEquals(1, saved.size)
        assertTrue(png.contentEquals(saved.single().readBytes()))

        // 静态路由可达且内容一致（App/Web 经 BASE_URL + avatarUrl 直取）
        val static = client.get("http://127.0.0.1:$port/uploads/avatars/" + saved.single().name)
        assertEquals(HttpStatusCode.OK, static.status)
        assertTrue(png.contentEquals(static.readBytes()))
    }

    @Test
    fun `avatar reupload overwrites old file and bumps version param`() = runBlocking {
        val (_, token) = registerAndLogin()
        val first = upload(token, pngBytes(), "image/png")
        assertEquals(HttpStatusCode.OK, first.status)
        val url1 = Json.parseToJsonElement(first.bodyAsText()).jsonObject["avatarUrl"]!!.jsonPrimitive.content

        Thread.sleep(10) // 保证 lastModified 变化（毫秒精度）
        val second = upload(token, pngBytes(), "image/png")
        assertEquals(HttpStatusCode.OK, second.status)
        val url2 = Json.parseToJsonElement(second.bodyAsText()).jsonObject["avatarUrl"]!!.jsonPrimitive.content

        // 仍然只有一个头像文件（旧文件被覆盖清理），版本参数随文件时间变化
        val files = File(avatarDir, "avatars").listFiles()!!.filter { !it.name.endsWith(".tmp") }
        assertEquals(1, files.size)
        assertTrue("重传后 ?v= 应变化以失效多端缓存", url1 != url2)
    }

    @Test
    fun `avatar upload rejects wrong type oversize and missing file`() = runBlocking {
        val (_, token) = registerAndLogin()

        // GIF 不在白名单 → 415
        val gif = upload(token, byteArrayOf(0x47, 0x49, 0x46, 0x38), "image/gif")
        assertEquals(HttpStatusCode.UnsupportedMediaType, gif.status)

        // 超 2MB → 413
        val big = upload(token, ByteArray(AvatarStorage.MAX_BYTES + 1), "image/png")
        assertEquals(HttpStatusCode.PayloadTooLarge, big.status)

        // 没带文件字段 → 400
        val empty = client.post("http://127.0.0.1:$port/api/auth/avatar") {
            bearerAuth(token)
            setBody(MultiPartFormDataContent(formData { append("note", "no file here") }))
        }
        assertEquals(HttpStatusCode.BadRequest, empty.status)

        // 未登录 → 401
        val noAuth = client.post("http://127.0.0.1:$port/api/auth/avatar") {
            setBody(MultiPartFormDataContent(formData { append("file", pngBytes()) }))
        }
        assertEquals(HttpStatusCode.Unauthorized, noAuth.status)

        // 全部失败后不残留文件
        assertEquals(0, File(avatarDir, "avatars").listFiles()?.size ?: 0)
    }

    @Test
    fun `me avatarUrl is null when never uploaded`() = runBlocking {
        val (_, token) = registerAndLogin()
        val me = client.get("http://127.0.0.1:$port/api/auth/me") { bearerAuth(token) }
        val obj = Json.parseToJsonElement(me.bodyAsText()).jsonObject
        assertFalse(obj["avatarUrl"]!!.toString().contains("uploads"))
    }
}
