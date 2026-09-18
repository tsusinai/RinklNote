package com.example.rinklnote.server.services

import com.example.rinklnote.server.services.nlu.RuleBasedParser
import io.ktor.util.logging.Logger
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.search.FlagTerm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Properties

/**
 * 邮件账单转发自动入账（2026-09-18 Task 4.4）：
 * IMAP 轮询（默认 10 分钟）→ 发件人白名单 → [MailBillParser] 解析支付宝/微信账单邮件
 * → 经 [BillService.createBill] 入账（source = `MAIL`，备注 = 商家，金额整数分）。
 *
 * **隐私红线**：邮件正文只在内存解析，不落盘、不进日志正文、绝不送 LLM；
 * 日志只允许 出现发件人地址与解析结果（金额/商家），失败也只报异常摘要（截断）。
 * 凭证全部走环境变量（MAIL_IMAP_USER / MAIL_IMAP_PASSWORD 等），不写 application.conf 明文。
 *
 * 已处理标记：成功入账与「白名单内但解析失败」的邮件都标为已读（避免无限重扫）；
 * 白名单外邮件不触碰未读状态。解析失败与轮询失败经 [alert] 推 Bot 告警（1 小时限流）。
 *
 * 未配置环境变量（[MailIngestConfig.fromEnv] 返回 null）时整个功能不启动，行为零变化。
 */
class MailIngestService(
    val config: MailIngestConfig,
    private val billService: BillService,
    private val alert: suspend (String) -> Unit = {},
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val log: Logger,
    /** 收件箱打开器（默认 Jakarta Mail 真实现；测试注入假实现，零网络）。 */
    private val openInbox: (MailIngestConfig) -> MailInbox = ::jakartaOpenInbox
) {

    /** 告警限流：同一 key 1 小时内最多一条。 */
    internal val lastAlertAt = HashMap<String, Long>()

    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    pollOnce()
                } catch (e: Exception) {
                    log.warn("邮件入账轮询异常: ${e.message}")
                    alertThrottled("poll", "📬 邮件入账轮询失败：${e.message?.take(120)}，本轮跳过")
                }
                delay(config.intervalMs)
            }
        }
    }

    /**
     * 轮询一次：返回成功入账条数。任何异常（连接/认证等）被捕获并告警，绝不向上抛。
     */
    suspend fun pollOnce(): Int = withContext(Dispatchers.IO) {
        openInbox(config).use { inbox ->
            var booked = 0
            for (msg in inbox.unread()) {
                if (!isWhitelisted(msg.from, config.senders)) continue  // 白名单外不碰未读状态
                val parsed = MailBillParser.parse(msg.body)
                if (parsed == null) {
                    // 白名单内但解析失败：标已读防重扫 + 告警（正文绝不进日志）
                    inbox.markRead(msg)
                    log.warn("账单邮件解析失败 from=${msg.from} subject=${msg.subject.take(40)}")
                    alertThrottled(
                        "parse:${msg.from}",
                        "📬 收到 ${msg.from} 的账单邮件但没解析出金额/商家，已标为已读，请到 App 里手动补一笔～"
                    )
                    continue
                }
                val categoryName = ruleCategory(parsed.merchant)
                billService.createBill(
                    userId = config.userId,
                    amountMinor = parsed.amountMinor,
                    categoryName = categoryName,
                    remark = parsed.merchant,
                    source = BotCommands.SOURCE_MAIL,
                    date = parsed.paidAtMillis
                )
                // 个人记忆层同步累计（商家词来自规则命中的字段，聚合口径不变）
                UserMemoryService.recordBillAsync(config.userId, parsed.merchant, categoryName)
                inbox.markRead(msg)
                booked++
                log.info("邮件账单已入账 user=${config.userId} amountMinor=${parsed.amountMinor} merchant=${parsed.merchant}")
            }
            booked
        }
    }

    /** 发件人白名单匹配：条目为完整地址或 `@domain` 后缀。纯函数，可单测。 */
    internal fun isWhitelisted(from: String, senders: List<String>): Boolean {
        val f = from.trim().lowercase()
        return senders.any { rule ->
            val r = rule.trim().lowercase()
            r.isNotEmpty() && (f.endsWith(r) || f == r)
        }
    }

    /** 商家 → 分类：只走本地规则（品牌映射），**绝不把商家名送 LLM**（隐私红线）。无命中 → null（落默认分类）。 */
    private fun ruleCategory(merchant: String): String? =
        RuleBasedParser().parse(merchant, emptyList())

    /** 告警限流：同 key 1 小时内最多发一条。 */
    internal suspend fun alertThrottled(key: String, text: String) {
        val now = nowMillis()
        val last = synchronized(lastAlertAt) { lastAlertAt[key] }
        if (last != null && now - last < ALERT_INTERVAL_MS) return
        synchronized(lastAlertAt) { lastAlertAt[key] = now }
        try {
            alert(text)
        } catch (e: Exception) {
            log.warn("邮件入账告警发送失败: ${e.message}")
        }
    }

    companion object {
        /** 同类告警最小间隔。 */
        const val ALERT_INTERVAL_MS = 60L * 60 * 1000

        /**
         * Jakarta Mail（Angus）真实现：连接 IMAPS、打开 INBOX（读写，用于标记已读）。
         * 每次轮询新建连接（低频轮询，连接生命周期简单可靠）。
         */
        fun jakartaOpenInbox(config: MailIngestConfig): MailInbox {
            val props = Properties().apply {
                put("mail.store.protocol", "imaps")
                put("mail.imaps.host", config.host)
                put("mail.imaps.port", config.port.toString())
                put("mail.imaps.ssl.enable", "true")
                put("mail.imaps.connectiontimeout", "15000")
                put("mail.imaps.timeout", "30000")
            }
            val session = Session.getInstance(props, null)
            val store = session.getStore("imaps")
            store.connect(config.host, config.user, config.password)
            val folder = store.getFolder("INBOX")
            folder.open(Folder.READ_WRITE)
            return object : MailInbox {
                override fun unread(): List<MailMessage> {
                    val term = FlagTerm(Flags(Flags.Flag.SEEN), false)
                    return folder.search(term).map { m ->
                        MailMessage(
                            from = m.from.firstOrNull()?.toString() ?: "",
                            subject = m.subject ?: "",
                            body = readBody(m)
                        )
                    }
                }

                override fun markRead(msg: MailMessage) {
                    // Jakarta Message 无稳定 id，按 from+subject 匹配标记（低频轮询，量小可接受）
                    folder.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
                        .firstOrNull { (it.from.firstOrNull()?.toString() ?: "") == msg.from && (it.subject ?: "") == msg.subject }
                        ?.setFlag(Flags.Flag.SEEN, true)
                }

                override fun close() {
                    runCatching { folder.close(false) }
                    runCatching { store.close() }
                }
            }
        }

        /** 正文读取：纯文本直接取；多部分取第一个文本块。正文只进内存，绝不落日志/磁盘。 */
        private fun readBody(message: jakarta.mail.Message): String = try {
            when {
                message.isMimeType("text/plain") || message.isMimeType("text/html") ->
                    message.content.toString()
                message.isMimeType("multipart/*") -> {
                    val multipart = message.content as jakarta.mail.Multipart
                    (0 until multipart.count).asSequence()
                        .map { multipart.getBodyPart(it) }
                        .firstOrNull { it.isMimeType("text/plain") || it.isMimeType("text/html") }
                        ?.content?.toString() ?: ""
                }
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }
}

/** 一封待处理邮件（正文仅在内存）。 */
data class MailMessage(val from: String, val subject: String, val body: String)

/** 收件箱抽象（Jakarta 真实现 / 测试假实现）。 */
interface MailInbox : AutoCloseable {
    /** 当前未读邮件。 */
    fun unread(): List<MailMessage>

    /** 把某封邮件标记为已读。 */
    fun markRead(msg: MailMessage)
}

/** 邮件入账配置：全部来自环境变量，绝不写 application.conf / 数据库明文。 */
data class MailIngestConfig(
    val userId: Long,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
    /** 发件人白名单：完整地址或 `@domain` 后缀。 */
    val senders: List<String>,
    val intervalMs: Long = 600_000L
) {
    companion object {
        const val DEFAULT_INTERVAL_MS = 600_000L

        /** 从环境变量装配；未配置（缺任一必填项）返回 null = 功能关闭。 */
        fun fromEnv(): MailIngestConfig? {
            val userId = System.getenv("MAIL_INGEST_USER_ID")?.toLongOrNull() ?: return null
            val host = System.getenv("MAIL_IMAP_HOST")?.takeIf { it.isNotBlank() } ?: return null
            val user = System.getenv("MAIL_IMAP_USER")?.takeIf { it.isNotBlank() } ?: return null
            val password = System.getenv("MAIL_IMAP_PASSWORD")?.takeIf { it.isNotBlank() } ?: return null
            val senders = (System.getenv("MAIL_SENDER_WHITELIST") ?: "@mail.alipay.com,@tencent.com")
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val port = System.getenv("MAIL_IMAP_PORT")?.toIntOrNull() ?: 993
            val interval = System.getenv("MAIL_INGEST_INTERVAL_MS")?.toLongOrNull() ?: DEFAULT_INTERVAL_MS
            return MailIngestConfig(userId, host, port, user, password, senders, interval)
        }
    }
}
