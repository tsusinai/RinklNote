package com.example.rinklnote.server.services.bot

import com.example.rinklnote.server.services.*
import com.example.rinklnote.server.tables.BillsTable
import com.example.rinklnote.server.tables.BotActionTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.transactions.transaction
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate

/** 所有动作都定位具体账单；数据库条件更新抵御旧卡、重放和并发点击。 */
class BotInteractionService(
    private val bills: BillService,
    private val budgets: BudgetService,
    private val users: UserService,
    private val webBaseUrl: String? = System.getenv("BOT_WEB_BASE_URL"),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val random = SecureRandom()
    private val lifetime = 30 * 60_000L
    private data class Ticket(
        val hash: String, val userId: Long, val channel: String, val actor: String,
        val action: String, val billId: Long?, val version: Long?, val expiresAt: Long, val consumed: Boolean
    )

    private fun hash(raw: String) = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun issue(userId: Long, channel: String, actor: String, action: String, bill: BillDTO? = null): String {
        val token = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        transaction {
            BotActionTable.deleteWhere { expiresAt less (clock() - 60_000) }
            if (action == "save_amount") BotActionTable.update({
                (BotActionTable.channel eq channel) and (BotActionTable.actor eq actor) and
                    (BotActionTable.action eq "save_amount") and (BotActionTable.consumed eq false)
            }) { it[consumed] = true }
            BotActionTable.insert {
                it[tokenHash] = hash(token); it[BotActionTable.userId] = userId
                it[commandCode] = token.take(12)
                it[BotActionTable.channel] = channel; it[BotActionTable.actor] = actor
                it[BotActionTable.action] = action; it[billId] = bill?.id
                it[billVersion] = bill?.let { b -> b.updatedAt ?: b.createdAt }
                it[expiresAt] = clock() + lifetime
            }
        }
        return token
    }

    private fun ticket(token: String): Ticket {
        if (!token.matches(Regex("[a-f0-9]{48}|[a-f0-9]{12}"))) throw BotActionException(410, "入口已失效，请在小盘重新获取")
        val t = transaction {
            BotActionTable.selectAll().where {
                if (token.length == 48) BotActionTable.tokenHash eq hash(token) else BotActionTable.commandCode eq token
            }.singleOrNull()?.let {
                Ticket(it[BotActionTable.tokenHash], it[BotActionTable.userId], it[BotActionTable.channel],
                    it[BotActionTable.actor], it[BotActionTable.action], it[BotActionTable.billId],
                    it[BotActionTable.billVersion], it[BotActionTable.expiresAt], it[BotActionTable.consumed])
            }
        } ?: throw BotActionException(410, "入口已失效，请在小盘重新获取")
        if (t.expiresAt <= clock()) throw BotActionException(410, "入口已过期，请在小盘重新获取")
        return t
    }

    private fun authorize(t: Ticket, channel: String, actor: String) {
        val userId = when (channel) {
            "QQ" -> users.findByQqOpenid(actor)?.id
            "FEISHU" -> users.findByFeishuOpenId(actor)?.id
            "MP" -> users.findByWechatOpenid(actor)?.id
            else -> null
        }
        if (t.channel != channel || t.actor != actor || t.userId != userId) {
            throw BotActionException(403, "只能操作你自己的账单与提醒")
        }
        if (t.consumed) throw BotActionException(409, "此操作已经处理，请使用最新卡片")
    }

    private fun currentBill(t: Ticket, checkVersion: Boolean = true): BillDTO {
        val b = t.billId?.let { bills.getBill(it, t.userId) }
            ?: throw BotActionException(404, "账单不存在")
        if (b.deleted && !t.consumed) throw BotActionException(409, "这一笔已撤销，请使用最新账单")
        if (checkVersion && !t.consumed && (b.updatedAt ?: b.createdAt) != t.version) {
            throw BotActionException(409, "账单已被修改，请重新查看账单后操作")
        }
        return b
    }

    fun billCard(userId: Long, channel: String, actor: String, b: BillDTO, text: String): BotCard = BotCard(
        "已记录", text,
        listOf("改金额" to "amount", "换分类" to "category", "撤销这一笔" to "undo", "查看账单" to "view")
            .map { (label, action) -> BotButton(label, issue(userId, channel, actor, action, b)) }, actor
    )

    fun budgetCard(userId: Long, channel: String, actor: String): BotCard = BotCard(
        "本月预算", budgetView(userId).text,
        listOf("查看明细" to "budget_details", "管理提醒" to "reminders")
            .map { (label, action) -> BotButton(label, issue(userId, channel, actor, action)) }, actor
    )

    fun isBudgetRequest(text: String): Boolean = Regex("^(查看|查询|看看)?(本月|这个月|我的)?预算(概览|情况|还剩多少)?[？?]?$")
        .matches(text.trim())

    private fun billView(t: Ticket, message: String? = null): InteractionView {
        val b = currentBill(t)
        val account = bills.accountsFor(t.userId).firstOrNull { it.id == b.accountId }?.name ?: "无账户"
        val day = Instant.ofEpochMilli(b.date).atZone(TimeUtil.BOOKKEEPING_ZONE).toLocalDate()
        val text = "🧾 ${b.categoryName} ¥${Money.format(b.amountMinor)}\n日期：$day\n账户：$account"
        return InteractionView("bill", "查看账单", text, t.expiresAt, t.consumed, message,
            InteractionBill(b.id, b.amountMinor, b.categoryId, b.categoryName, b.billType, b.date, account, b.updatedAt ?: b.createdAt),
            bills.getCategories().filter { it.billType == b.billType }.map { InteractionCategory(it.id, it.name) })
    }

    private fun budgetView(userId: Long, expiresAt: Long = clock() + lifetime, consumed: Boolean = false,
                           message: String? = null): InteractionView {
        val today = Instant.ofEpochMilli(clock()).atZone(TimeUtil.BOOKKEEPING_ZONE).toLocalDate()
        val start = today.withDayOfMonth(1).atStartOfDay(TimeUtil.BOOKKEEPING_ZONE).toInstant().toEpochMilli()
        val end = today.withDayOfMonth(1).plusMonths(1).atStartOfDay(TimeUtil.BOOKKEEPING_ZONE).toInstant().toEpochMilli()
        val stats = bills.monthlyStats(userId, start, end)
        val amount = budgets.list(userId).firstOrNull {
            !it.deleted && it.monthStart == start && it.categoryId == null && it.subCategoryId == null
        }?.amountMinor
        val user = users.findById(userId) ?: throw BotActionException(404, "账号不存在")
        val text = buildString {
            append("📊 本月已用 ¥${Money.format(stats.totalExpenseMinor)}")
            if (amount != null) append("\n预算 ¥${Money.format(amount)} · 剩余 ¥${Money.format(amount - stats.totalExpenseMinor)}")
            else append("\n还未设置本月总预算")
            if (stats.topExpenseCategories.isNotEmpty()) append("\n消费分类：" + stats.topExpenseCategories.joinToString("、") {
                "${it.first} ¥${Money.format(it.second)}"
            })
            if (user.qqOpenid == null && user.feishuOpenId == null && user.wecomUserid == null)
                append("\n公众号不主动发送日报，提醒需绑定 QQ、飞书或企业微信")
            else if (user.aiDisabled) append("\nAI 功能开关已关闭，当前不会发送主动提醒")
        }
        return InteractionView("budget", "本月预算", text, expiresAt, consumed, message, budget = InteractionBudget(
            amount, stats.totalExpenseMinor, amount?.minus(stats.totalExpenseMinor),
            user.dailyReportEnabled, user.dailyReportHour, user.dailyReportMinute
        ))
    }

    /** 公众号私聊中的限时编辑入口，不发登录凭据，也不给其他通道的群消息可写链接。 */
    fun webLink(userId: Long, actor: String, b: BillDTO? = null): String? {
        val base = webBaseUrl?.trim()?.trimEnd('/')?.takeIf {
            val uri = runCatching { java.net.URI(it) }.getOrNull()
            (uri?.scheme == "https" || (uri?.scheme == "http" && uri.host in setOf("localhost", "127.0.0.1", "::1"))) &&
                !uri?.host.isNullOrBlank() && uri?.rawQuery == null && uri?.rawFragment == null
        } ?: return null
        val token = issue(userId, "MP", actor, if (b == null) "web_budget" else "web_bill", b)
        return "$base/bot/interaction#token=$token"
    }

    fun webView(token: String): InteractionView {
        if (token.length != 48) throw BotActionException(410, "入口已失效，请在小盘重新获取")
        val t = ticket(token)
        if (t.channel != "MP" || t.action !in listOf("web_bill", "web_budget")) {
            throw BotActionException(403, "此入口不能在网页使用")
        }
        if (users.findByWechatOpenid(t.actor)?.id != t.userId) throw BotActionException(403, "账号绑定已变化，请重新获取入口")
        return if (t.action == "web_bill") billView(t) else budgetView(t.userId, t.expiresAt, t.consumed)
    }

    fun webEdit(token: String, edit: InteractionEdit): InteractionView {
        val t = ticket(token)
        webView(token)
        if (t.consumed) throw BotActionException(409, "此操作已经处理，请在小盘重新获取入口")
        val message = when {
            t.action == "web_bill" && edit.operation in listOf("save", "undo") -> mutateBill(t, edit)
            t.action == "web_budget" && edit.operation == "reminders" -> mutateReminders(t, edit)
            else -> throw BotActionException(403, "此入口不支持该操作")
        }
        return if (t.action == "web_bill") billView(t.copy(consumed = true), message)
        else budgetView(t.userId, t.expiresAt, true, message)
    }

    private fun claim(t: Ticket) {
        val claimed = BotActionTable.update({
            (BotActionTable.tokenHash eq t.hash) and (BotActionTable.consumed eq false) and (BotActionTable.expiresAt greater clock())
        }) { it[consumed] = true }
        if (claimed != 1) throw BotActionException(409, "此操作已经处理，请使用最新卡片")
    }

    private fun mutateBill(t: Ticket, edit: InteractionEdit): String = transaction {
        val b = currentBill(t)
        val minor = if (edit.operation == "save") edit.amountMinor ?: b.amountMinor else b.amountMinor
        if (minor <= 0 || minor > 9_007_199_254_740_991L) throw BotActionException(400, "金额必须大于零且在有效范围内")
        val category = bills.getCategories().firstOrNull { it.id == (edit.categoryId ?: b.categoryId) && it.billType == b.billType }
            ?: throw BotActionException(400, "请选择同一收支类型的有效分类")
        claim(t)
        val updated = BillsTable.update({
            (BillsTable.id eq b.id) and (BillsTable.userId eq t.userId) and (BillsTable.deleted eq false) and
                ((BillsTable.updatedAt eq t.version) or (BillsTable.updatedAt.isNull() and (BillsTable.createdAt eq t.version!!)))
        }) {
            if (edit.operation == "undo") it[deleted] = true
            else {
                it[amountMinor] = minor; it[amount] = Money.fromMinor(minor)
                it[categoryId] = category.id; it[categoryName] = category.name
                if (category.id != b.categoryId) it[subCategoryName] = null
            }
            it[updatedAt] = maxOf(clock(), (t.version ?: 0) + 1)
        }
        if (updated != 1) throw BotActionException(409, "账单已被修改，请重新查看后操作")
        if (edit.operation == "undo") "✅ 已撤销这一笔：${b.categoryName} ¥${Money.format(b.amountMinor)}"
        else "✅ 已修改：${category.name} ¥${Money.format(minor)}"
    }

    private fun mutateReminders(t: Ticket, edit: InteractionEdit): String = transaction {
        val enabled = edit.enabled ?: throw BotActionException(400, "请选择是否开启日报提醒")
        val hour = edit.hour ?: 9; val minute = edit.minute ?: 0
        if (hour !in 0..23 || minute !in 0..59) throw BotActionException(400, "提醒时间无效")
        claim(t)
        users.setDailyReport(t.userId, enabled, hour, minute)
        val user = users.findById(t.userId)!!
        val delivery = if (enabled && user.qqOpenid == null && user.feishuOpenId == null && user.wecomUserid == null)
            "\n偏好已保存，绑定支持推送的 Bot 通道后才会发送" else if (enabled && user.aiDisabled)
            "\n偏好已保存，AI 功能开关开启后才会发送" else ""
        "✅ 日报提醒已${if (enabled) "开启" else "关闭"}，时间 ${"%02d:%02d".format(hour, minute)}$delivery"
    }

    fun action(token: String, channel: String, actor: String, edit: InteractionEdit? = null): BotActionOutcome {
        val t = ticket(token); authorize(t, channel, actor)
        return when (t.action) {
            "view" -> {
                val b = t.billId?.let { bills.getBill(it, t.userId) } ?: throw BotActionException(404, "账单不存在")
                if (b.deleted) BotActionOutcome("这一笔已撤销")
                else {
                    val view = billView(t.copy(version = b.updatedAt ?: b.createdAt))
                    val card = billCard(t.userId, channel, actor, b, view.text).copy(title = "查看账单")
                    BotActionOutcome(view.text, if (channel == "FEISHU") BotCardRenderer.feishu(card) else null,
                        if (channel == "QQ") card else null)
                }
            }
            "undo" -> BotActionOutcome(mutateBill(t, InteractionEdit("undo")))
            "amount", "category" -> {
                val view = billView(t)
                if (channel == "FEISHU") {
                    val save = issue(t.userId, channel, actor, "save", currentBill(t))
                    BotActionOutcome("请填写金额与分类", BotCardRenderer.feishuEditForm(view, save))
                } else if (t.action == "amount") {
                    issue(t.userId, channel, actor, "save_amount", currentBill(t))
                    BotActionOutcome("请回复这一笔的新金额，例如「30.00」，回复「取消」结束修改")
                } else {
                    val b = currentBill(t)
                    val buttons = view.categories.map { BotButton(it.name, issue(t.userId, channel, actor, "category:${it.id}", b)) }
                    BotActionOutcome("请选择这一笔的分类", qqCard = BotCard("换分类", view.text, buttons, actor))
                }
            }
            "save", "save_amount" -> {
                val request = edit ?: throw BotActionException(400, "请填写修改内容")
                val message = mutateBill(t, request.copy(operation = "save"))
                val b = bills.getBill(t.billId!!, t.userId)!!
                val card = billCard(t.userId, channel, actor, b, message)
                BotActionOutcome(message, if (channel == "FEISHU") BotCardRenderer.feishu(card) else null,
                    if (channel == "QQ") card else null)
            }
            "budget_details" -> BotActionOutcome(budgetView(t.userId).text)
            "reminders" -> {
                if (channel == "FEISHU") {
                    val save = issue(t.userId, channel, actor, "save_reminders")
                    BotActionOutcome("管理日报提醒", BotCardRenderer.feishuReminderForm(budgetView(t.userId), save))
                } else {
                    val buttons = listOf("开启日报" to "enable", "关闭日报" to "disable").map {
                        BotButton(it.first, issue(t.userId, channel, actor, it.second))
                    }
                    BotActionOutcome("请选择日报提醒状态", qqCard = BotCard("管理提醒", "日报提醒设置", buttons, actor))
                }
            }
            "save_reminders", "enable", "disable" -> {
                val request = if (t.action == "save_reminders") edit ?: throw BotActionException(400, "请填写提醒设置")
                else {
                    val u = users.findById(t.userId)!!
                    InteractionEdit("reminders", enabled = t.action == "enable", hour = u.dailyReportHour, minute = u.dailyReportMinute)
                }
                val message = mutateReminders(t, request)
                val card = budgetCard(t.userId, channel, actor)
                BotActionOutcome(message, if (channel == "FEISHU") BotCardRenderer.feishu(card, message) else null,
                    if (channel == "QQ") card.copy(text = "$message\n${card.text}") else null)
            }
            else -> if (t.action.startsWith("category:")) {
                val message = mutateBill(t, InteractionEdit("save", categoryId = t.action.substringAfter(':').toLongOrNull()))
                val b = bills.getBill(t.billId!!, t.userId)!!
                BotActionOutcome(message, qqCard = billCard(t.userId, channel, actor, b, message))
            } else throw BotActionException(403, "不支持的操作")
        }
    }

    /** 带明确票据的金额补充不会被当成一笔新账或修改另一笔账单。 */
    fun textAction(text: String, channel: String, actor: String): BotActionOutcome? {
        val clean = text.trim()
        val pending = transaction {
            BotActionTable.selectAll().where {
                (BotActionTable.channel eq channel) and (BotActionTable.actor eq actor) and
                    (BotActionTable.action eq "save_amount") and (BotActionTable.consumed eq false) and
                    (BotActionTable.expiresAt greater clock())
            }.firstOrNull()?.get(BotActionTable.commandCode)
        }
        if (pending != null && clean == "取消") {
            val t = ticket(pending); authorize(t, channel, actor); transaction { claim(t) }
            return BotActionOutcome("已取消修改")
        }
        if (pending != null && Regex("^(?:改金额\\s*)?\\d+(?:\\.\\d+)?(?:元)?$").matches(clean)) {
            val minor = Money.parseMinor(clean.removePrefix("改金额").trim().removeSuffix("元"))
                ?: throw BotActionException(400, "请输入大于零的金额，最多两位小数")
            return action(pending, channel, actor, InteractionEdit("save", amountMinor = minor))
        }
        val match = Regex("^\\S+ ([a-f0-9]{48}|[a-f0-9]{12})(?: (.+))?$")
            .matchEntire(clean)
        if (match == null) {
            if (pending != null) throw BotActionException(400, "请回复有效的新金额，或回复「取消」结束修改")
            return null
        }
        val value = match.groupValues[2].trim()
        val edit = if (value.isBlank()) null else InteractionEdit("save", amountMinor = Money.parseMinor(value)
            ?: throw BotActionException(400, "请输入大于零的金额，最多两位小数"))
        return action(match.groupValues[1], channel, actor, edit)
    }
}
