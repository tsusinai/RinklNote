package com.example.rinklnote.server.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

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

/**
 * 管理员身份名单：来自 ADMIN_IDENTITIES 环境变量（逗号分隔的手机号 / userId）。
 *
 * 判定为「双保险」：JWT 里的 admin claim **或** 身份命中本名单，任一命中即放行。
 * 名单是每次请求实时比对的「活」判定（改环境变量重启即生效，不依赖 24h token 轮换）；
 * admin claim 只是签发时刻的快照，用于名单之外的可信授信通道。
 *
 * 单例 + 可注入：测试用 [refresh] 覆盖名单，跑完记得恢复环境变量原值。
 */
object AdminIdentities {
    @Volatile private var entries: Set<String> = parse(System.getenv("ADMIN_IDENTITIES"))

    /** 刷新名单（null / 空串 = 清空）。测试注入入口，也供运行时重载。 */
    fun refresh(raw: String?) {
        entries = parse(raw)
    }

    /** 当前名单快照（调试 / 测试断言用）。 */
    fun snapshot(): Set<String> = entries

    private fun parse(raw: String?): Set<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    /** 手机号或 userId 命中名单（忽略首尾空白）。 */
    fun matches(phone: String?, userId: Long?): Boolean {
        if (entries.isEmpty()) return false
        if (phone != null && phone in entries) return true
        if (userId != null && userId.toString() in entries) return true
        return false
    }

    /** requireAdmin 的判定核心：claim 或名单任一放行。抽出纯函数便于无 Ktor 的 JVM 单测。 */
    fun isAllowed(adminClaim: Boolean, phone: String?, userId: Long?): Boolean =
        adminClaim || matches(phone, userId)
}

/**
 * 管理员守卫：未命中双保险之一则直接回 403（沿用 {"message": ...} 错误风格）。
 * 命中返回 principal 供调用方继续取 userId；未命中返回 null（响应已写出，调用方直接 return）。
 */
suspend fun ApplicationCall.requireAdmin(): JWTPrincipal? {
    val principal = this.principal<JWTPrincipal>()
    val adminClaim = principal?.payload?.getClaim("admin")?.asBoolean() ?: false
    val phone = principal?.payload?.getClaim("phone")?.asString()
    val userId = principal?.payload?.getClaim("userId")?.asLong()
    if (AdminIdentities.isAllowed(adminClaim, phone, userId)) return principal
    respond(HttpStatusCode.Forbidden, mapOf("message" to "需要管理员权限"))
    return null
}
