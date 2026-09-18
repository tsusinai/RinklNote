package com.example.rinklnote.server

import com.example.rinklnote.server.plugins.*
import com.example.rinklnote.server.routes.*
import com.example.rinklnote.server.services.AdminService
import com.example.rinklnote.server.services.AlertNotifier
import com.example.rinklnote.server.services.AiAssistService
import com.example.rinklnote.server.services.AiTokenService
import com.example.rinklnote.server.services.AvatarStorage
import com.example.rinklnote.server.services.BotCommands
import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.BudgetService
import com.example.rinklnote.server.services.ChallengeService
import com.example.rinklnote.server.services.FeishuBotService
import com.example.rinklnote.server.services.Money
import com.example.rinklnote.server.services.MailIngestConfig
import com.example.rinklnote.server.services.MailIngestService
import com.example.rinklnote.server.services.MpBotService
import com.example.rinklnote.server.services.PhoneIntentRouter
import com.example.rinklnote.server.services.QQBotService
import com.example.rinklnote.server.services.QQBotWebSocketClient
import com.example.rinklnote.server.services.RateService
import com.example.rinklnote.server.services.PushScheduler
import com.example.rinklnote.server.services.TemplateService
import com.example.rinklnote.server.services.UserService
import com.example.rinklnote.server.services.WecomBotService
import com.example.rinklnote.server.services.coach.CoachService
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
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.http.content.staticFiles
import io.ktor.server.routing.*
import kotlinx.coroutines.cancel
import java.io.File

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

    val userService = UserService(jwtSecret, jwtIssuer, jwtAudience)
    val billService = BillService()
    val budgetService = BudgetService()
    val challengeService = ChallengeService()
    // 头像文件存储：uploads/avatars/{userId}.jpg（相对工作目录），测试可注入目录。
    val avatarStorage = AvatarStorage()
    // 预热头像目录：staticFiles 注册的目录不要求存在，但首次上传/访问前先建好更稳。
    avatarStorage.ensureDirs()

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

    // AI 助手接口（小爱等手机 AI）—— 个人访问令牌 + 结构化查询
    val aiTokenService = AiTokenService()
    val phoneIntentRouter = PhoneIntentRouter(billService, budgetService, insightService, nluService)
    val aiAssistService = AiAssistService(billService, budgetService, insightService)

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

    // 飞书自建应用机器人（B2）——配置存 bot_config，经 Web 管理端维护；
    // 事件收口 webhook（FeishuBotWebhookRoutes），消息处理走 FeishuMessageProcessor。
    val feishuBotService = FeishuBotService()
    feishuBotService.loadFromDb()
    if (feishuBotService.isConfigured()) {
        log.info("飞书 Bot 已加载配置（AppID: ${feishuBotService.getMaskedAppId()}）")
    } else {
        log.info("飞书 Bot 未配置 —— 可在 Web 设置页维护")
    }

    // 企业微信智能机器人（C-W1）——配置存 bot_config（wecom_token / wecom_encoding_aes_key /
    // wecom_push_webhook_url），回调收口 WecomBotWebhookRoutes（同步被动回复，5s 窗口），
    // 消息处理走 WecomMessageProcessor；主动推送走「消息推送」群机器人 webhook（见 send 分发处）。
    val wecomBotService = WecomBotService()
    wecomBotService.loadFromDb()
    if (wecomBotService.isConfigured()) {
        log.info("企微 Bot 已加载配置（Token: ${wecomBotService.getToken()?.take(2)}**）") // 掩码：只露前 2 位（安全评审修复）
    } else {
        log.info("企微 Bot 未配置 —— 可在 Web 设置页维护")
    }

    // 个人订阅号（C-W2）——只收不推：配置仅 mp_token（+可选 mp_encoding_aes_key 安全模式），
    // 唯一出口是回调里的 5s 被动回复（MpWebhookRoutes），消息处理走 MpMessageProcessor。
    val mpBotService = MpBotService()
    mpBotService.loadFromDb()
    if (mpBotService.isConfigured()) {
        log.info("订阅号已加载配置（明文${if (mpBotService.getEncodingAesKey() != null) "以外还配了加密 key（安全模式）" else "模式"}）")
    } else {
        log.info("订阅号未配置 —— 可在 Web 设置页维护")
    }

    // 全局异常告警（2026-09-18 Task 0.8）：未捕获异常经 Bot 通道推给主账号（ADMIN_IDENTITIES）。
    // StatusPages 需在路由前安装，但告警目标依赖上面各 Bot 服务 —— 因此 configureErrorHandling
    // 挪到这里（仍在 routing 之前，语义不变）。告警只含路径与异常摘要，不含请求体。
    val alertNotifier = AlertNotifier(
        userService = userService,
        send = { channel, targetId, content ->
            when (channel) {
                BotCommands.SOURCE_QQ -> qqBotService.sendC2CMessage(targetId, content, "")
                BotCommands.SOURCE_FEISHU -> feishuBotService.sendText(targetId, content)
                BotCommands.SOURCE_WECOM -> wecomBotService.pushText(content)
                else -> false
            }
        },
        log = log
    )
    configureErrorHandling(alertNotifier)

    // Bot 主动推送调度：月末月结卡片 / 每日异常提醒 / 时段习惯提醒（走 push_log 去重；ai_disabled 跳过）。
    // B1 通道底座：send 带通道维度，按 PushScheduler 选定的目标通道分发——
    // 目标通道已在调度器内按 飞书 > 企业微信 > QQ 取第一个已绑定，这里只做「通道 → 发送实现」的映射。
    // 周报（2026-09-18 Task 1.4）：周一推账单教练的个性化建议（烧穿预警/可执行周建议，仅聚合输入）。
    val coachService = CoachService(billService, budgetService, challengeService)
    val pushScheduler = PushScheduler(
        userService = userService,
        dailyReportProvider = { userId ->
            // 日报推「昨天」（已完结的一天）：人话化文案 —— LLM 一句话点评 + 连续记账/预算锚点，
            // 失败回退模板；昨天没账则不发。窗口改成昨天是 2026-09-12 修复，勿改回今天。
            val zone = java.time.ZoneId.of("Asia/Shanghai")
            val now = java.time.ZonedDateTime.now(zone)
            val dayStart = now.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
            insightService.dailyPushCopy(userId, dayStart - 86_400_000L, dayStart)
        },
        // QQ 通道：接现有发送实现。主动推送 msg_id 传空串 → QQ 会省略该字段
        // （随机 UUID 会被拒 40034024）；被动回复（QQMessageProcessor）仍传真实事件 id，不受影响。
        // FEISHU 通道（B2 已接线）：sendText 不传消息 id 即主动发送（tenant_access_token 由服务内缓存）。
        // WECOM 通道（C-W1 已接线）：企微「消息推送」webhook 主动推。⚠️ 边界：webhook 机器人推送是
        // **群 / 群机器人维度**（消息发到配置该 webhook 的群会话），不是按用户单聊——按人单聊主动
        // 触达需要企业微信「应用消息」接口 message/send（corpid + 应用 secret 换 access_token），
        // 本期不做（调研文档 3.4/3.6）。因此企微绑定用户的推送统一落到群机器人会话。
        // ⚠️ 安全评审修复：正因群维度会跨用户，调度器（PushScheduler）仅在全库企微绑定用户数 == 1
        // 时才选中 WECOM 通道，多用户绑定时会跳过企微落 QQ —— 此处的 pushText 只承接调度器放行的场景。
        // MP 分支（订阅号）：无任何主动推送能力（客服/模板/订阅通知均需认证服务号，调研文档 4.5），
        // 唯一出口是回调里的 5s 被动回复（MpWebhookRoutes）。调度器也不会选到该通道
        // （findAllPushUsers 只看飞书/企微/QQ，B1 已保证），此分支不可达，no-op 兜底返回 false。
        send = { channel, targetId, content, _ ->
            when (channel) {
                BotCommands.SOURCE_QQ -> qqBotService.sendC2CMessage(targetId, content, "")
                BotCommands.SOURCE_FEISHU -> feishuBotService.sendText(targetId, content)
                BotCommands.SOURCE_WECOM -> wecomBotService.pushText(content)
                BotCommands.SOURCE_MP -> false // 订阅号无主动推送能力，no-op（见上方边界注释）
                else -> false
            }
        },
        monthlyProvider = { userId, month ->
            val (y, m) = month.split("-").map { it.toInt() }
            val zone = java.time.ZoneId.of("Asia/Shanghai")
            val monthStart = java.time.LocalDate.of(y, m, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val nextMonthStart = java.time.LocalDate.of(y, m, 1).plusMonths(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val stats = billService.monthlyStats(userId, monthStart, nextMonthStart)
            if (stats.totalExpenseMinor <= 0 && stats.totalIncomeMinor <= 0) null
            else {
                val r = insightService.monthlyReview(userId, month)
                buildString {
                    appendLine("📊 小盘月报")
                    append(r.summary)
                    r.highlights.forEach { append("\n• ").append(it) }
                    if (r.spikeDays.isNotEmpty()) {
                        append("\n⚠️ 超标日\n")
                        r.spikeDays.take(5).forEach {
                            append("- ").append(it.date).append(" ¥").append(Money.format(it.amountMinor))
                                .append("（超日均").append(it.ratioPct).append("%）\n")
                        }
                    }
                    r.biggestSingle?.let {
                        append("\n🔍 最大单笔：").append(it.categoryName).append(" ¥")
                            .append(Money.format(it.amountMinor)).append("（").append(it.date).append("）")
                    }
                    if (r.topCategories.isNotEmpty()) {
                        append("\n🧾 消费集中：")
                        append(r.topCategories.joinToString("、") { it.name + " ¥" + Money.format(it.amountMinor) })
                    }
                }
            }
        },
        anomalyProvider = { userId ->
            // 只在 18 点后评估当天异常：清晨推「今天花超了」既不准也打扰；
            // 每天最多一条（push_log 按 dayKey 去重）。
            val hour = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai")).hour
            if (hour < 18) null
            else insightService.anomalyCheck(userId).alerts
                .joinToString("\n") { "⚠️ " + it.message }
                .ifEmpty { null }
        },
        habitProvider = { userId ->
            val habit = insightService.habitReminder(userId)
            if (habit == null) null
            else insightService.polishHabitCopy(habit)
        },
        weeklyProvider = { userId -> coachService.weeklyPushCopy(userId) },
        intervalMs = 30_000L,
        log = log
    )
    pushScheduler.start(appScope)

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

    // 汇率服务（2026-09-18 Task 4.2）：免 key 开放源（open.er-api.com，USD 基准）每日拉一次，
    // 缓存 bot_config KV；失败回落上次成功值，从未成功回落演示表（与 App DEMO_RATES_VS_CNY 同值）。
    val rateHttpClient = HttpClient(CIO)
    val rateService = RateService(
        fetchJson = { url ->
            try { rateHttpClient.get(url).bodyAsText() } catch (_: Exception) { null }
        },
        apiUrl = System.getenv("RATE_API_URL") ?: RateService.DEFAULT_API_URL,
        log = log
    )
    rateService.start(appScope)

    // 邮件账单转发自动入账（2026-09-18 Task 4.4）：默认关闭——配置 MAIL_INGEST_USER_ID +
    // MAIL_IMAP_HOST/USER/PASSWORD（可选 PORT / MAIL_SENDER_WHITELIST / MAIL_INGEST_INTERVAL_MS）后启用。
    // 凭证只走环境变量；正文只在内存解析，不落盘不送 LLM（隐私红线见 MailIngestService 注释头）。
    val mailIngestConfig = MailIngestConfig.fromEnv()
    if (mailIngestConfig != null) {
        val mailIngest = MailIngestService(
            config = mailIngestConfig,
            billService = billService,
            alert = { text -> alertNotifier.deliver(text) },
            log = log
        )
        mailIngest.start(appScope)
        log.info("邮件账单入账已启用（IMAP=${mailIngestConfig.host} 白名单=${mailIngestConfig.senders.size}条）")
    } else {
        log.info("邮件账单入账未配置（缺 MAIL_INGEST_USER_ID / MAIL_IMAP_* 环境变量），保持关闭")
    }

    // 管理端只读服务：LLM/ASR 只回「是否已配置」布尔，绝不回显 key 值（隐私红线见 AdminService 注释头）。
    val adminService = AdminService(
        dbTypeName = { DbRuntimeInfo.typeName },
        llmConfigured = { deepseekApiKey.isNotBlank() },
        asrConfigured = { !asrApiKey.isNullOrBlank() }
    )

    // Release HTTP clients and cancel the app coroutine scope (LearningService loop,
    // async webhook processing) on graceful shutdown.
    environment.monitor.subscribe(ApplicationStopped) {
        appScope.cancel()
        llmParser.shutdown()
        qqWsClient.shutdown()
        qqBotService.shutdown()
        feishuBotService.shutdown()
        wecomBotService.shutdown()
        asrService.shutdown()
        rateHttpClient.close()
    }

    routing {
        // 头像等静态资源：/uploads/* 对外只读（无需 JWT；文件名不含用户可控输入，越权面可控）。
        staticFiles("/uploads", File("uploads"))
        authRoutes(userService, qqBotService, avatarStorage)
        billRoutes(billService, nluService)
        accountRoutes(billService)
        transcribeRoutes(asrService)
        budgetRoutes(budgetService)
        challengeRoutes(challengeService)
        correctionRoutes()
        keywordRoutes()
        insightRoutes(insightService)
        qqBotWebhookRoutes(qqBotService, userService, billService, nluService, budgetService, insightService)
        qqBotManageRoutes(qqBotService, userService)
        feishuBotWebhookRoutes(feishuBotService, userService, billService, nluService, budgetService, insightService)
        feishuBotManageRoutes(feishuBotService, userService)
        wecomBotWebhookRoutes(wecomBotService, userService, billService, nluService, budgetService, insightService)
        wecomBotManageRoutes(wecomBotService, userService)
        mpWebhookRoutes(mpBotService, userService, billService, nluService, budgetService, insightService)
        templateRoutes(templateService)
        aiAssistantRoutes(phoneIntentRouter, aiAssistService, aiTokenService)
        rateRoutes(rateService)
        adminRoutes(adminService, qqBotService, qqWsClient)
    }
}
