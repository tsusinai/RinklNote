package com.example.rinklnote.server.routes

import com.example.rinklnote.server.plugins.AdminIdentities
import com.example.rinklnote.server.services.AvatarStorage
import com.example.rinklnote.server.services.InMemoryRateLimiter
import com.example.rinklnote.server.services.PasswordPolicy
import com.example.rinklnote.server.services.QQBotService
import com.example.rinklnote.server.services.UserInfo
import com.example.rinklnote.server.services.UserService
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.time.LocalDate

// 2026-09-17 优化登录方式：新增 email 可选身份，与 phone 同级 ——
// email 非空走邮箱身份，否则走手机号；phone 保持原语义（非空字符串，邮箱模式传空串），
// 旧客户端只发 phone 完全不受影响。新客户端邮箱模式可不传 phone（有默认值）。
@Serializable
data class RegisterRequest(
    val phone: String = "",
    val password: String,
    val email: String? = null
)

@Serializable
data class LoginRequest(
    val phone: String = "",
    val password: String,
    val email: String? = null
)

@Serializable
data class AuthResponse(val userId: Long, val token: String)

@Serializable
data class MessageResponse(val message: String)

@Serializable
data class MeResponse(
    val id: Long,
    val phone: String?,
    // 邮箱身份（2026-09-17）：可空下发，未设置/旧库为 null。
    val email: String? = null,
    val qqNumber: String? = null,
    val qqOpenid: String? = null,
    val createdAt: String? = null,
    val isAdmin: Boolean = false,
    val aiDisabled: Boolean = false,
    val dailyReportEnabled: Boolean = false,
    val dailyReportHour: Int = 9,
    val dailyReportMinute: Int = 0,
    // ── 个人资料（2026-09-17）──
    // 展示徽章以下发的逗号分隔串为准（与存储同形）；数量上限 3 由写入接口校验。
    val nickname: String? = null,
    val signature: String? = null,
    val birthday: String? = null,
    // 头像对外相对 URL（含 ?v= 文件时间版本参数）；未上传为 null。
    val avatarUrl: String? = null,
    val showcaseBadges: String? = null
)

@Serializable
data class ChangePasswordRequest(val oldPassword: String, val newPassword: String)

@Serializable
data class AiSettingRequest(val disabled: Boolean)

@Serializable
data class DailyReportSettingRequest(val enabled: Boolean, val hour: Int, val minute: Int)

/**
 * PUT /api/auth/profile 的请求体：文字资料**整体替换**（null / 缺省 = 清除该字段）。
 * 头像不在此处改，走 POST /api/auth/avatar 独立上传。
 */
@Serializable
data class ProfileUpdateRequest(
    val nickname: String? = null,
    val signature: String? = null,
    val birthday: String? = null,
    // 展示徽章 key 列表（上限 3）；空列表 = 清空展示。
    val showcaseBadges: List<String> = emptyList()
)

/** POST /api/auth/avatar 的响应：回传最新对外头像 URL（含 ?v= 版本参数）。 */
@Serializable
data class AvatarUploadResponse(val avatarUrl: String? = null, val message: String? = null)

/** 徽章展示上限（与 App「我的」卡片约定一致）。 */
private const val MAX_SHOWCASE_BADGES = 3

/** 邮箱格式（仅服务端兜底校验用）：本地段@域名.顶级域，三端同构的宽松标准 email 正则。 */
private val EmailPattern = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

/** 邮箱身份归一：去空白 + 转小写 + 空串归 null（与 UserService 存取同构，大小写不敏感）。 */
private fun normalizeEmail(email: String?): String? =
    email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

/** UserInfo → MeResponse（头像 URL 附加 ?v= 文件时间，防多端缓存）。 */
private fun meResponseOf(user: UserInfo, avatarStorage: AvatarStorage): MeResponse = MeResponse(
    id = user.id,
    phone = user.phone,
    email = user.email,
    qqNumber = user.qqNumber,
    qqOpenid = user.qqOpenid,
    createdAt = user.createdAt,
    // 管理员判定 = 身份在 ADMIN_IDENTITIES 名单（活的判定，不依赖 token claim 快照）。
    isAdmin = AdminIdentities.matches(user.phone, user.id),
    aiDisabled = user.aiDisabled,
    dailyReportEnabled = user.dailyReportEnabled,
    dailyReportHour = user.dailyReportHour,
    dailyReportMinute = user.dailyReportMinute,
    nickname = user.nickname,
    signature = user.signature,
    birthday = user.birthday,
    avatarUrl = avatarStorage.publicUrl(user.id),
    showcaseBadges = user.showcaseBadges
)

fun Route.authRoutes(
    userService: UserService,
    qqBotService: QQBotService,
    avatarStorage: AvatarStorage = AvatarStorage()
) {
    // In-memory per-IP limiting: blocks brute-force login/password guessing and
    // mass account creation. Generous limits so legit users behind a shared IP
    // are not affected.
    val loginLimiter = InMemoryRateLimiter(maxAttempts = 10, windowSeconds = 600)      // 10 failed / 10 min
    val registerLimiter = InMemoryRateLimiter(maxAttempts = 5, windowSeconds = 3600)   // 5 registrations / hour

    route("/api/auth") {
        post("/register") {
            val ip = call.request.local.remoteHost
            if (registerLimiter.isBlocked(ip)) {
                call.respond(HttpStatusCode.TooManyRequests, MessageResponse("注册过于频繁，请稍后再试"))
                return@post
            }
            val body = call.receive<RegisterRequest>()
            // 邮箱身份归一（小写、去空白）；email 非空走邮箱身份，否则走手机号（旧客户端语义不变）。
            val email = normalizeEmail(body.email)
            val phone = body.phone.trim()
            if (phone.isBlank() && email == null) {
                call.respond(HttpStatusCode.BadRequest, MessageResponse("手机号或邮箱不能为空"))
                return@post
            }
            // 邮箱格式仅做宽松兜底校验（App/Web 已有前置提示，这里防脏数据入库）。
            if (email != null && !EmailPattern.matches(email)) {
                call.respond(HttpStatusCode.BadRequest, MessageResponse("邮箱格式不正确"))
                return@post
            }
            // 密码规则（2026-09-17）：≥6 位且同时含大小写字母；只约束新设定，存量老密码不受影响。
            val passwordError = PasswordPolicy.validate(body.password)
            if (passwordError != null) {
                call.respond(HttpStatusCode.BadRequest, MessageResponse(passwordError))
                return@post
            }
            val existing = userService.findByPhone(phone)
            if (existing != null) {
                registerLimiter.recordFailure(ip)
                call.respond(HttpStatusCode.Conflict, MessageResponse("该手机号已注册"))
                return@post
            }
            if (email != null && userService.findByEmail(email) != null) {
                registerLimiter.recordFailure(ip)
                call.respond(HttpStatusCode.Conflict, MessageResponse("该邮箱已注册"))
                return@post
            }
            val (userId, token) = userService.register(phone.takeIf { it.isNotBlank() }, email, body.password)
            call.respond(HttpStatusCode.Created, AuthResponse(userId, token))
        }

        post("/login") {
            val ip = call.request.local.remoteHost
            if (loginLimiter.isBlocked(ip)) {
                call.respond(HttpStatusCode.TooManyRequests, MessageResponse("尝试次数过多，请稍后再试"))
                return@post
            }
            val body = call.receive<LoginRequest>()
            // email 非空走邮箱登录，否则按手机号（旧客户端只发 phone，文案保持不变）。
            val email = normalizeEmail(body.email)
            val result = if (email != null) {
                userService.loginByEmail(email, body.password)
            } else {
                userService.login(body.phone, body.password)
            }
            if (result == null) {
                loginLimiter.recordFailure(ip)
                val message = if (email != null) "邮箱或密码错误" else "手机号或密码错误"
                call.respond(HttpStatusCode.Unauthorized, MessageResponse(message))
                return@post
            }
            loginLimiter.recordSuccess(ip)
            val (userId, token) = result
            call.respond(AuthResponse(userId, token))
        }

        authenticate("auth-jwt") {
            get("/me") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val user = userService.findById(userId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, MessageResponse("用户不存在"))
                call.respond(meResponseOf(user, avatarStorage))
            }

            // 整体替换文字资料（昵称 / 签名 / 生日 / 展示徽章）；成功后回传最新 MeResponse。
            put("/profile") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized)

                val body = call.receive<ProfileUpdateRequest>()

                val nickname = body.nickname?.trim()?.takeIf { it.isNotEmpty() }
                if (nickname != null && nickname.length > 32) {
                    call.respond(HttpStatusCode.BadRequest, MessageResponse("昵称最长 32 个字符"))
                    return@put
                }
                val signature = body.signature?.trim()?.takeIf { it.isNotEmpty() }
                if (signature != null && signature.length > 120) {
                    call.respond(HttpStatusCode.BadRequest, MessageResponse("签名最长 120 个字符"))
                    return@put
                }
                val birthday = body.birthday?.trim()?.takeIf { it.isNotEmpty() }
                if (birthday != null) {
                    try {
                        LocalDate.parse(birthday)
                    } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, MessageResponse("生日格式应为 yyyy-MM-dd"))
                        return@put
                    }
                }
                val badges = body.showcaseBadges.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                if (badges.size > MAX_SHOWCASE_BADGES) {
                    call.respond(HttpStatusCode.BadRequest, MessageResponse("最多展示 $MAX_SHOWCASE_BADGES 枚徽章"))
                    return@put
                }
                // 服务端只存字符串：key 合法性只做宽松约束（小写字母/数字/连字符），具体枚举由客户端成就引擎演进。
                if (badges.any { !Regex("[a-z0-9-]{1,64}").matches(it) }) {
                    call.respond(HttpStatusCode.BadRequest, MessageResponse("徽章 key 格式不合法"))
                    return@put
                }

                val user = userService.updateProfile(
                    userId = userId,
                    nickname = nickname,
                    signature = signature,
                    birthday = birthday,
                    showcaseBadges = badges.takeIf { it.isNotEmpty() }?.joinToString(",")
                ) ?: return@put call.respond(HttpStatusCode.NotFound, MessageResponse("用户不存在"))
                call.respond(meResponseOf(user, avatarStorage))
            }

            // 头像上传：multipart（字段名 file），仅 JPEG/PNG/WebP、≤2MB。
            // 落盘 uploads/avatars/{userId}.jpg（一人一文件、覆盖替换），并回写 users.avatar_url。
            post("/avatar") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                var bytes: ByteArray? = null
                var contentType: ContentType? = null
                try {
                    call.receiveMultipart().forEachPart { part ->
                        if (part is PartData.FileItem && bytes == null) {
                            // Ktor 2.x 的 FileItem.contentType 已是 ContentType?，直接采用。
                            contentType = part.contentType
                            bytes = part.streamProvider().readBytes()
                        }
                        part.dispose()
                    }
                } catch (_: Exception) {
                    call.respond(HttpStatusCode.BadRequest, MessageResponse("请求不是合法的 multipart 上传"))
                    return@post
                }
                val data = bytes
                if (data == null || data.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, MessageResponse("未收到头像文件"))
                    return@post
                }
                if (contentType !in AvatarStorage.ALLOWED_CONTENT_TYPES) {
                    call.respond(HttpStatusCode.UnsupportedMediaType, MessageResponse("仅支持 JPEG / PNG / WebP 图片"))
                    return@post
                }
                if (data.size > AvatarStorage.MAX_BYTES) {
                    call.respond(HttpStatusCode.PayloadTooLarge, MessageResponse("头像不能超过 2MB"))
                    return@post
                }

                avatarStorage.save(userId, data)
                // 库里存不含版本参数的相对路径；对外 URL 由 publicUrl 附加 ?v= 文件时间。
                userService.updateAvatarUrl(userId, "uploads/avatars/$userId.jpg")
                val user = userService.findById(userId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, MessageResponse("用户不存在"))
                call.respond(AvatarUploadResponse(avatarUrl = avatarStorage.publicUrl(userId), message = "头像已更新"))
            }

            post("/password") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val body = call.receive<ChangePasswordRequest>()
                if (body.oldPassword.isBlank() || body.newPassword.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, MessageResponse("密码不能为空"))
                    return@post
                }
                // 新密码规则与注册一致（≥6 位 + 大小写字母）；老密码不受影响，仅约束本次新设定。
                val passwordError = PasswordPolicy.validate(body.newPassword)
                if (passwordError != null) {
                    call.respond(HttpStatusCode.BadRequest, MessageResponse(passwordError))
                    return@post
                }
                val success = userService.updatePassword(userId, body.oldPassword, body.newPassword)
                if (success) {
                    call.respond(MessageResponse("密码修改成功"))
                } else {
                    call.respond(HttpStatusCode.Unauthorized, MessageResponse("原密码错误"))
                }
            }

            get("/ai") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                call.respond(mapOf("disabled" to userService.isAiDisabled(userId)))
            }

            put("/ai") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<AiSettingRequest>()
                userService.setAiDisabled(userId, body.disabled)
                call.respond(mapOf("disabled" to body.disabled))
            }

            // 日报推送设置（QQ 端）。命名 daily-report-setting，与 api/insights/daily-report（拉日报内容）区分。
            get("/daily-report-setting") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                val user = userService.findById(userId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, MessageResponse("用户不存在"))
                call.respond(
                    DailyReportSettingRequest(
                        enabled = user.dailyReportEnabled,
                        hour = user.dailyReportHour,
                        minute = user.dailyReportMinute
                    )
                )
            }

            put("/daily-report-setting") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<DailyReportSettingRequest>()
                if (body.hour !in 0..23 || body.minute !in 0..59) {
                    call.respond(HttpStatusCode.BadRequest, MessageResponse("时间不合法（hour 0-23, minute 0-59）"))
                    return@put
                }
                userService.setDailyReport(userId, body.enabled, body.hour, body.minute)
                call.respond(body)
            }
        }
    }
}
