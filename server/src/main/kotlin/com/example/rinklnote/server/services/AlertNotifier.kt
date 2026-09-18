package com.example.rinklnote.server.services

import io.ktor.util.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * 服务端异常告警（2026-09-18 Task 0.8）：未捕获异常经 Bot 通道推给**主账号**（管理员）。
 *
 * 主账号解析：ADMIN_IDENTITIES 环境变量（与管理端同一份名单）里第一个可解析出的身份 ——
 * 先试手机号、再试 userId，查到用户后按「飞书 > 企微(全库唯一绑定才可用) > QQ」选通道
 * （与 PushScheduler 同一优先级；订阅号只收不推不参与）。查不到可推送身份时只落日志。
 *
 * 隐私红线：告警内容只有 请求方法+路径 + 异常类名 + 截断后的异常消息（≤200 字符），
 * **绝不包含请求体 / 响应体 / 请求头 / SQL 参数等敏感内容**。
 *
 * 防风暴：同一条路径上同一种异常 1 分钟内最多告警一次（进程内计数，重启清零可接受）。
 */
class AlertNotifier(
    private val userService: UserService,
    private val send: suspend (channel: String, targetId: String, content: String) -> Boolean,
    private val adminIdentityRaw: () -> String? = { System.getenv("ADMIN_IDENTITIES") },
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    /** 同一 (路径+异常类) 的最小告警间隔。 */
    private val minIntervalMs: Long = 60_000L,
    private val log: Logger
) {
    companion object {
        /** 异常消息摘要最大长度（防 SQL/IO 异常消息携带超长上下文）。 */
        private const val MAX_SUMMARY_LEN = 200

        /** 告警消息里异常消息的清洗上限（与摘要同长）。 */
        private val WHITESPACE = Regex("\\s+")

        /** 纯函数：拼装告警文案（可单测）。 */
        fun buildAlertText(method: String?, path: String?, cause: Throwable, at: LocalDateTime): String {
            val summary = (cause.message ?: "").replace(WHITESPACE, " ").trim()
                .take(MAX_SUMMARY_LEN)
            val time = at.format(DateTimeFormatter.ofPattern("MM-dd HH:mm:ss"))
            return buildString {
                appendLine("🚨 服务异常告警")
                if (!method.isNullOrBlank() || !path.isNullOrBlank()) {
                    appendLine("路径: ${method ?: "?"} ${path ?: "?"}")
                }
                appendLine("异常: ${cause.javaClass.simpleName}${if (summary.isEmpty()) "" else ": $summary"}")
                append("时间: $time（Asia/Shanghai）")
            }
        }
    }

    private val lastSentByKey = ConcurrentHashMap<String, Long>()

    /**
     * 发一条异常告警（fire-and-forget）：只带方法/路径与异常摘要，不含任何请求体。
     * 任何内部失败只落日志，绝不影响调用方（异常处理路径本身不能再抛）。
     */
    fun alertAsync(method: String?, path: String?, cause: Throwable) {
        val key = "${method ?: "-"} ${path ?: "-"} ${cause.javaClass.name}"
        val now = nowMillis()
        val last = lastSentByKey[key]
        if (last != null && now - last < minIntervalMs) return
        lastSentByKey[key] = now
        val text = buildAlertText(method, path, cause, LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
        CoroutineScope(Dispatchers.Default).launch {
            try { deliver(text) } catch (e: Exception) { log.warn("异常告警发送失败: ${e.message}") }
        }
    }

    /** 供测试直接同步调用：返回是否送达（无主账号/通道时为 false）。 */
    suspend fun deliver(text: String): Boolean {
        val target = resolveAdminTarget() ?: return false
        return try {
            send(target.first, target.second, text)
        } catch (e: Exception) {
            log.warn("异常告警通道调用失败: ${e.message}")
            false
        }
    }

    /**
     * 解析主账号与其推送通道：ADMIN_IDENTITIES（逗号分隔手机号 / userId）里第一个
     * 能解析且绑定了可推送通道（飞书 > 企微受限 > QQ）的用户。找不到返回 null。
     */
    internal fun resolveAdminTarget(): Pair<String, String>? {
        val raw = adminIdentityRaw() ?: return null
        for (token in raw.split(',')) {
            val id = token.trim()
            if (id.isEmpty()) continue
            val user = id.toLongOrNull()?.let { userService.findById(it) }
                ?: userService.findByPhone(id)
                ?: continue
            // 通道优先级与 PushScheduler 一致；企微受「全库唯一绑定」限制（群 webhook 维度防跨用户泄露）
            val wecomUsable = user.wecomUserid != null && userService.countWecomBoundUsers() == 1
            return when {
                user.feishuOpenId != null -> BotCommands.SOURCE_FEISHU to user.feishuOpenId
                wecomUsable -> BotCommands.SOURCE_WECOM to user.wecomUserid!!
                user.qqOpenid != null -> BotCommands.SOURCE_QQ to user.qqOpenid
                else -> continue
            }
        }
        return null
    }
}
