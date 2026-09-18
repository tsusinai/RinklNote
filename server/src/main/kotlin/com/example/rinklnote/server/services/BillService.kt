package com.example.rinklnote.server.services

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import com.example.rinklnote.server.tables.*
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class BillDTO(
    val id: Long,
    // 新字段（分，权威值）；旧客户端请忽略。
    val amountMinor: Long,
    // 旧字段，仅供旧客户端，勿用；值 = Money.fromMinor(amountMinor)。
    val amount: Double,
    val billType: String,
    val categoryId: Long,
    val categoryName: String,
    val subCategoryName: String?,
    val accountId: Long,
    val remark: String?,
    val date: Long,
    val source: String,
    val createdAt: Long,
    val updatedAt: Long? = null,
    val deleted: Boolean = false,
    // 同日内显式排序名次（App 端拖动重排）；null = 未排序。
    val sortOrder: Long? = null,
    // 经纬度（度）：仅用户主动打点的账单才有值；null 时 JSON 省略该字段
    // （旧客户端不认识/新客户端按默认 null 解析，双向兼容），与 amount 的 @EncodeDefault(ALWAYS)
    // 惯例不同——那是「旧端必读」的兼容字段，本字段是「新端增量」字段。
    val latitude: Double? = null,
    val longitude: Double? = null
)

/** 条件 PUT（乐观锁）的三种结果：
 *  Updated 成功；VersionConflict 版本不匹配（附服务端当前最新 DTO，客户端据此重取 base 重放）；
 *  NotFound 账单不存在或非本人账单。 */
sealed interface BillUpdateResult {
    data class Updated(val bill: BillDTO) : BillUpdateResult
    data class VersionConflict(val current: BillDTO) : BillUpdateResult
    data object NotFound : BillUpdateResult
}

@Serializable
data class SyncResponse(
    val bills: List<BillDTO>,
    val serverTime: Long,
    val hasMore: Boolean = false,
    // Composite cursor (updatedAt, id) for the last returned bill — clients echo
    // these back on the next page to avoid duplicate/skip when two bills share
    // the same updatedAt across a page boundary.
    val nextAfter: Long? = null,
    val nextAfterId: Long? = null
)

/** 账单搜索响应（2026-09-18 Task 0.6）：当前页列表 + 分页元数据 +
 *  「当前筛选全集」的聚合（汇总卡跨页展示用），金额一律整数分。 */
@Serializable
data class BillSearchResponse(
    val bills: List<BillDTO>,
    val page: Int,
    val pageSize: Int,
    val total: Long,
    val totalPages: Int,
    val sumExpenseMinor: Long,
    val sumIncomeMinor: Long
)

@Serializable
data class SubCategoryDTO(
    val id: Long,
    val name: String,
    val parentCategoryId: Long
)

@Serializable
data class CategoryDTO(
    val id: Long,
    val name: String,
    val iconName: String,
    val billType: String,
    val subCategories: List<SubCategoryDTO> = emptyList()
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AccountDTO(
    val id: Long,
    val name: String,
    val balanceMinor: Long,
    // 旧字段，仅供旧客户端，勿用；值 = Money.fromMinor(balanceMinor)。
    val balance: Double,
    val iconColor: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val iconKey: String = "WALLET",
    val updatedAt: Long = 0,
    val deleted: Boolean = false
)

private const val ACCOUNT_BUCKET_NAME = "无账户"
private val ACCOUNT_ICON_KEYS = setOf(
    "WALLET", "BANK_CARD", "CASH", "WECHAT",
    "ALIPAY", "CREDIT_CARD", "INVESTMENT", "OTHER"
)

private fun normalizeAccountName(raw: String): String {
    val name = raw.trim()
    require(name.isNotEmpty()) { "账户名不能为空" }
    require(name != ACCOUNT_BUCKET_NAME) { "该账户名不可用" }
    require(name.length <= 50) { "账户名不能超过50个字符" }
    return name
}

private fun normalizeAccountIconKey(raw: String): String =
    raw.takeIf { it in ACCOUNT_ICON_KEYS } ?: "WALLET"

class BillService {
    fun createBill(
        userId: Long,
        amountMinor: Long,
        categoryName: String?,
        remark: String?,
        source: String = "QQ"
    ): BillDTO {
        require(amountMinor > 0) { "金额必须大于0" }
        val sanitizedRemark = remark?.take(500)

        val now = System.currentTimeMillis()
        val todayStart = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()

        // Match category by name, supporting both EXPENSE and INCOME
        val category = if (categoryName != null) {
            transaction {
                CategoriesTable.selectAll()
                    .where { CategoriesTable.name eq categoryName }
                    .singleOrNull()
            }
        } else null

        val catId: Long
        val catName: String
        val billType: String
        if (category != null) {
            catId = category[CategoriesTable.id]
            catName = category[CategoriesTable.name]
            billType = category[CategoriesTable.billType] // Use category's type (EXPENSE or INCOME)
        } else {
            val defaultCat = transaction {
                CategoriesTable.selectAll()
                    .where { CategoriesTable.billType eq "EXPENSE" }
                    .orderBy(CategoriesTable.id)
                    .firstOrNull()
                    ?: throw IllegalStateException("No default category found")
            }
            catId = defaultCat[CategoriesTable.id]
            catName = defaultCat[CategoriesTable.name]
            billType = "EXPENSE"
        }

        val accountId = accountIdFor(userId)

        val billId = transaction {
            BillsTable.insert {
                it[BillsTable.userId] = userId
                it[BillsTable.amountMinor] = amountMinor
                it[BillsTable.amount] = Money.fromMinor(amountMinor)
                it[BillsTable.billType] = billType
                it[BillsTable.categoryId] = catId
                it[BillsTable.categoryName] = catName
                it[BillsTable.accountId] = accountId
                it[BillsTable.remark] = sanitizedRemark
                it[BillsTable.date] = todayStart
                it[BillsTable.billSource] = source
                it[BillsTable.createdAt] = now
                it[BillsTable.updatedAt] = now
            } get BillsTable.id
        }

        return BillDTO(
            id = billId, amountMinor = amountMinor, amount = Money.fromMinor(amountMinor), billType = billType,
            categoryId = catId, categoryName = catName,
            subCategoryName = null, accountId = accountId,
            remark = sanitizedRemark, date = todayStart, source = source,
            createdAt = now, updatedAt = now
        )
    }

    fun createWebBill(
        userId: Long,
        amountMinor: Long,
        billType: String,
        categoryId: Long,
        categoryName: String,
        subCategoryName: String?,
        accountId: Long,
        remark: String?,
        date: Long?,
        sortOrder: Long? = null,
        latitude: Double? = null,
        longitude: Double? = null
    ): BillDTO {
        require(amountMinor > 0) { "金额必须大于0" }
        require(billType == "EXPENSE" || billType == "INCOME") { "账单类型不合法" }
        val ownsAccount = transaction {
            AccountsTable.selectAll()
                .where { (AccountsTable.id eq accountId) and (AccountsTable.userId eq userId) }
                .any()
        }
        require(ownsAccount) { "账户不存在" }
        val now = System.currentTimeMillis()
        val billDate = date ?: LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .atStartOfDay(ZoneId.of("Asia/Shanghai"))
            .toInstant()
            .toEpochMilli()

        val billId = transaction {
            BillsTable.insert {
                it[BillsTable.userId] = userId
                it[BillsTable.amountMinor] = amountMinor
                it[BillsTable.amount] = Money.fromMinor(amountMinor)
                it[BillsTable.billType] = billType
                it[BillsTable.categoryId] = categoryId
                it[BillsTable.categoryName] = categoryName
                it[BillsTable.subCategoryName] = subCategoryName
                it[BillsTable.accountId] = accountId
                it[BillsTable.remark] = remark
                it[BillsTable.date] = billDate
                it[BillsTable.billSource] = "WEB"
                it[BillsTable.createdAt] = now
                it[BillsTable.updatedAt] = now
                it[BillsTable.sortOrder] = sortOrder
                it[BillsTable.latitude] = latitude
                it[BillsTable.longitude] = longitude
            } get BillsTable.id
        }

        return BillDTO(
            id = billId, amountMinor = amountMinor, amount = Money.fromMinor(amountMinor), billType = billType,
            categoryId = categoryId, categoryName = categoryName,
            subCategoryName = subCategoryName, accountId = accountId,
            remark = remark, date = billDate, source = "WEB",
            createdAt = now, updatedAt = now, sortOrder = sortOrder,
            latitude = latitude, longitude = longitude
        )
    }

    /**
     * 账单搜索（2026-09-18 Task 0.6）：服务端条件拼接 + 分页，替代 Web 端全量内存过滤。
     * - 只查未删除（软删过滤），按 userId 隔离；
     * - q 模糊匹配备注 / 分类名（不区分大小写）；
     * - minMinor / maxMinor 为整数分比较（元入参由路由层经 Money.toMinor 换算），区间含边界；
     * - fromDayStart / toDayEnd 为 epoch 毫秒（业务时区当天边界，含首尾，由路由层解析）；
     * - 分页 page 从 1 起；total / 聚合覆盖当前筛选全集（跨页）。
     * 排序：date 倒序、id 倒序（最新在前，与 Web 列表现有习惯一致）。
     */
    fun searchBills(
        userId: Long,
        q: String? = null,
        minMinor: Long? = null,
        maxMinor: Long? = null,
        categoryId: Long? = null,
        fromDayStart: Long? = null,
        toDayEnd: Long? = null,
        page: Int = 1,
        pageSize: Int = 20
    ): BillSearchResponse {
        val safePage = page.coerceAtLeast(1)
        val safeSize = pageSize.coerceIn(1, 200)

        fun filterOp(): Op<Boolean> = with(SqlExpressionBuilder) {
            var op: Op<Boolean> = (BillsTable.userId eq userId) and (BillsTable.deleted eq false)
            if (!q.isNullOrBlank()) {
                val like = "%" + q.trim().lowercase() + "%"
                op = op and (BillsTable.remark.lowerCase().like(like) or BillsTable.categoryName.lowerCase().like(like))
            }
            if (minMinor != null) op = op and (BillsTable.amountMinor greaterEq minMinor)
            if (maxMinor != null) op = op and (BillsTable.amountMinor lessEq maxMinor)
            if (categoryId != null) op = op and (BillsTable.categoryId eq categoryId)
            if (fromDayStart != null) op = op and (BillsTable.date greaterEq fromDayStart)
            if (toDayEnd != null) op = op and (BillsTable.date lessEq toDayEnd)
            op
        }

        return transaction {
            val where: Op<Boolean> = filterOp()
            val total = BillsTable.selectAll().where { where }.count()
            val sumExpense = BillsTable.select(BillsTable.amountMinor.sum())
                .where { where and (BillsTable.billType eq "EXPENSE") }
                .first()[BillsTable.amountMinor.sum()] ?: 0L
            val sumIncome = BillsTable.select(BillsTable.amountMinor.sum())
                .where { where and (BillsTable.billType eq "INCOME") }
                .first()[BillsTable.amountMinor.sum()] ?: 0L

            val bills = BillsTable.selectAll()
                .where { where }
                .orderBy(BillsTable.date to SortOrder.DESC, BillsTable.id to SortOrder.DESC)
                .limit(safeSize, offset = (safePage - 1).toLong() * safeSize)
                .map { it.toBillDto() }

            BillSearchResponse(
                bills = bills,
                page = safePage,
                pageSize = safeSize,
                total = total,
                totalPages = ((total + safeSize - 1) / safeSize).toInt(),
                sumExpenseMinor = sumExpense,
                sumIncomeMinor = sumIncome
            )
        }
    }

    fun deleteBill(billId: Long, userId: Long): Boolean {
        val now = System.currentTimeMillis()
        return transaction {
            val updated = BillsTable.update({
                (BillsTable.id eq billId) and (BillsTable.userId eq userId)
            }) {
                it[deleted] = true
                it[updatedAt] = now
            }
            updated > 0
        }
    }

    /**
     * 条件 PUT（乐观锁，2026-09-18 Task 0.1）：单条 UPDATE 把版本条件写进 WHERE，
     * 消灭旧「先读后写」实现在两步之间被并发写入穿插导致的丢更新窗口。
     * baseUpdatedAt 为 null 时不带版本条件（无条件覆盖，旧客户端兼容）。
     * 0 行命中时二次区分：记录不存在 → NotFound；存在但版本不匹配 → VersionConflict（附当前最新 DTO）。
     */
    fun updateBill(
        id: Long,
        userId: Long,
        baseUpdatedAt: Long?,
        amountMinor: Long,
        billType: String,
        categoryId: Long,
        categoryName: String,
        subCategoryName: String?,
        accountId: Long,
        remark: String?,
        sortOrder: Long?,
        latitude: Double?,
        longitude: Double?
    ): BillUpdateResult = transaction {
        val now = System.currentTimeMillis()
        val affected = BillsTable.update({
            if (baseUpdatedAt == null) {
                (BillsTable.id eq id) and (BillsTable.userId eq userId)
            } else {
                (BillsTable.id eq id) and (BillsTable.userId eq userId) and
                    (BillsTable.updatedAt eq baseUpdatedAt)
            }
        }) {
            it[BillsTable.amountMinor] = amountMinor
            it[BillsTable.amount] = Money.fromMinor(amountMinor)
            it[BillsTable.billType] = billType
            it[BillsTable.categoryId] = categoryId
            it[BillsTable.categoryName] = categoryName
            it[BillsTable.subCategoryName] = subCategoryName
            it[BillsTable.accountId] = accountId
            it[BillsTable.remark] = remark
            it[BillsTable.sortOrder] = sortOrder
            // PUT 是全量替换语义（与 remark 等字段一致）：传 null 即清除打点。
            it[BillsTable.latitude] = latitude
            it[BillsTable.longitude] = longitude
            it[BillsTable.updatedAt] = now
        }
        val row = BillsTable.selectAll()
            .where { (BillsTable.id eq id) and (BillsTable.userId eq userId) }
            .singleOrNull()
        when {
            affected > 0 && row != null -> BillUpdateResult.Updated(row.toBillDto())
            row != null -> BillUpdateResult.VersionConflict(row.toBillDto())
            else -> BillUpdateResult.NotFound
        }
    }

    fun syncBills(userId: Long, after: Long? = null, afterId: Long? = null, limit: Int = 200): SyncResponse {
        val now = System.currentTimeMillis()
        val page = transaction {
            val query = BillsTable.selectAll()
                .where { BillsTable.userId eq userId }
                .orderBy(BillsTable.updatedAt to SortOrder.ASC, BillsTable.id to SortOrder.ASC)

            if (after != null && after > 0) {
                if (afterId != null) {
                    // Keyset cursor: strictly after (updatedAt, id). Handles concurrent
                    // writes that bump several rows to the same updatedAt.
                    query.andWhere {
                        (BillsTable.updatedAt greater after) or
                            ((BillsTable.updatedAt eq after) and (BillsTable.id greater afterId))
                    }
                } else {
                    query.andWhere { BillsTable.updatedAt greater after }
                }
            }

            query.limit(limit + 1).map {
                BillDTO(
                    id = it[BillsTable.id],
                    amountMinor = it[BillsTable.amountMinor] ?: 0L,
                    amount = Money.fromMinor(it[BillsTable.amountMinor] ?: 0L),
                    billType = it[BillsTable.billType],
                    categoryId = it[BillsTable.categoryId],
                    categoryName = it[BillsTable.categoryName],
                    subCategoryName = it[BillsTable.subCategoryName],
                    accountId = it[BillsTable.accountId],
                    remark = it[BillsTable.remark],
                    date = it[BillsTable.date],
                    source = it[BillsTable.billSource],
                    createdAt = it[BillsTable.createdAt],
                    updatedAt = it[BillsTable.updatedAt],
                    deleted = it[BillsTable.deleted],
                    sortOrder = it[BillsTable.sortOrder],
                    latitude = it[BillsTable.latitude],
                    longitude = it[BillsTable.longitude]
                )
            }
        }
        val hasMore = page.size > limit
        val pageBills = page.take(limit)
        val last = pageBills.lastOrNull()
        return SyncResponse(
            bills = pageBills,
            serverTime = now,
            hasMore = hasMore,
            nextAfter = last?.updatedAt,
            nextAfterId = last?.id
        )
    }

    /**
     * All non-deleted bills for a user, page-fetched with the composite cursor so
     * insight queries never truncate at 200 rows or include soft-deleted bills.
     */
    fun allBills(userId: Long): List<BillDTO> {
        var after: Long? = null
        var afterId: Long? = null
        val result = mutableListOf<BillDTO>()
        do {
            val page = syncBills(userId, after, afterId, 200)
            result += page.bills.filter { !it.deleted }
            after = page.nextAfter
            afterId = page.nextAfterId
        } while (page.hasMore && after != null)
        return result
    }

    // ── 每用户账户 ──────────────────────────────────────────────

    private fun ResultRow.toAccountDto() = AccountDTO(
        id = this[AccountsTable.id],
        name = this[AccountsTable.name],
        balanceMinor = this[AccountsTable.balanceMinor] ?: 0L,
        balance = Money.fromMinor(this[AccountsTable.balanceMinor] ?: 0L),
        iconColor = this[AccountsTable.iconColor],
        iconKey = this[AccountsTable.iconKey],
        updatedAt = this[AccountsTable.updatedAt],
        deleted = this[AccountsTable.deleted]
    )

    /** 按 (user_id,name) 幂等补默认兜底账户：只补「无账户」（快速记账需要默认账户落点），
     *  微信/支付宝等真实钱包改由用户自建。缺哪个名补哪个，自定义账户共存；
     *  老用户已有的微信/支付宝不受影响（本函数只补缺，从不删改）。 */
    fun ensureDefaultAccounts(userId: Long) {
        val now = System.currentTimeMillis()
        transaction {
            val existing = AccountsTable.selectAll()
                .where { AccountsTable.userId eq userId }
                .map { it[AccountsTable.name] }
                .toSet()
            listOf(
                Triple("无账户", "#F97D1D", "OTHER")
            ).forEach { (name, color, iconKey) ->
                if (name in existing) return@forEach
                AccountsTable.insert {
                    it[AccountsTable.userId] = userId
                    it[AccountsTable.name] = name
                    it[AccountsTable.iconColor] = color
                    it[AccountsTable.iconKey] = iconKey
                    it[AccountsTable.balanceMinor] = 0L
                    it[AccountsTable.balance] = 0.0
                    it[AccountsTable.updatedAt] = now
                }
            }
        }
    }

    fun accountsFor(userId: Long): List<AccountDTO> {
        ensureDefaultAccounts(userId)
        return transaction {
            AccountsTable.selectAll()
                .where { (AccountsTable.userId eq userId) and (AccountsTable.deleted eq false) }
                .orderBy(AccountsTable.id)
                .map { it.toAccountDto() }
        }
    }

    fun createAccount(
        userId: Long,
        name: String,
        iconColor: String,
        balanceMinor: Long,
        iconKey: String = "WALLET"
    ): AccountDTO {
        val normalizedName = normalizeAccountName(name)
        val normalizedIconKey = normalizeAccountIconKey(iconKey)
        require(balanceMinor >= 0) { "余额不能为负" }
        // (user_id, name) 唯一索引：同名账户对同一用户不可重复（含默认账户名）
        val exists = transaction {
            AccountsTable.selectAll()
                .where { (AccountsTable.userId eq userId) and (AccountsTable.name eq normalizedName) and (AccountsTable.deleted eq false) }
                .any()
        }
        require(!exists) { "账户已存在" }
        val now = System.currentTimeMillis()
        val id = transaction {
            AccountsTable.insert {
                it[AccountsTable.userId] = userId
                it[AccountsTable.name] = normalizedName
                it[AccountsTable.iconColor] = iconColor
                it[AccountsTable.iconKey] = normalizedIconKey
                it[AccountsTable.balanceMinor] = balanceMinor
                it[AccountsTable.balance] = Money.fromMinor(balanceMinor)
                it[AccountsTable.updatedAt] = now
            } get AccountsTable.id
        }
        return AccountDTO(
            id = id,
            name = normalizedName,
            balanceMinor = balanceMinor,
            balance = Money.fromMinor(balanceMinor),
            iconColor = iconColor,
            iconKey = normalizedIconKey,
            updatedAt = now,
            deleted = false
        )
    }

    /** 真正的部分更新：只覆盖请求中传入的字段，未传字段保持原值。 */
    fun updateAccount(
        id: Long,
        userId: Long,
        name: String? = null,
        iconColor: String? = null,
        iconKey: String? = null,
        balanceMinor: Long? = null
    ): AccountDTO? = transaction {
        val row = AccountsTable.selectAll()
            .where { (AccountsTable.id eq id) and (AccountsTable.userId eq userId) }
            .singleOrNull() ?: return@transaction null

        val current = row.toAccountDto()
        val nextName = name?.let(::normalizeAccountName) ?: current.name
        val nextColor = iconColor ?: current.iconColor
        val nextIconKey = iconKey?.let(::normalizeAccountIconKey) ?: current.iconKey
        val nextBalance = balanceMinor ?: current.balanceMinor
        require(nextBalance >= 0) { "余额不能为负" }

        if (name != null && nextName != current.name) {
            val dup = AccountsTable.selectAll()
                .where {
                    (AccountsTable.userId eq userId) and
                        (AccountsTable.name eq nextName) and
                        (AccountsTable.deleted eq false) and
                        (AccountsTable.id neq id)
                }
                .any()
            require(!dup) { "账户已存在" }
        }

        val now = System.currentTimeMillis()
        AccountsTable.update({ AccountsTable.id eq id }) {
            it[AccountsTable.name] = nextName
            it[AccountsTable.iconColor] = nextColor
            it[AccountsTable.iconKey] = nextIconKey
            it[AccountsTable.balanceMinor] = nextBalance
            it[AccountsTable.balance] = Money.fromMinor(nextBalance)
            it[AccountsTable.updatedAt] = now
        }
        current.copy(
            name = nextName,
            balanceMinor = nextBalance,
            balance = Money.fromMinor(nextBalance),
            iconColor = nextColor,
            iconKey = nextIconKey,
            updatedAt = now
        )
    }

    /** 兼容旧调用：重命名同时更新颜色。 */
    fun renameAccount(id: Long, userId: Long, name: String, iconColor: String): AccountDTO? =
        updateAccount(id, userId, name = name, iconColor = iconColor)

    fun deleteAccount(id: Long, userId: Long): Boolean {
        val now = System.currentTimeMillis()
        return transaction {
            val updated = AccountsTable.update({
                (AccountsTable.id eq id) and (AccountsTable.userId eq userId)
            }) {
                it[deleted] = true
                it[updatedAt] = now
            }
            updated > 0
        }
    }

    /** 当前用户首个未删除账户 id；无则先播种默认。 */
    fun accountIdFor(userId: Long): Long {
        ensureDefaultAccounts(userId)
        return transaction {
            AccountsTable.selectAll()
                .where { (AccountsTable.userId eq userId) and (AccountsTable.deleted eq false) }
                .orderBy(AccountsTable.id)
                .first()[AccountsTable.id]
        }
    }

    fun seedIfNeeded() {
        transaction {
            // 幂等补齐一级分类：按 (name, bill_type) 查存在再插入，已存在的自动跳过。
            // 既有库升级后在此补上新增分类，id 按列表顺序追加续排，与 App 端 seed 顺序逐字一致。
            seedCategories()
            if (AccountsTable.selectAll().empty()) {
                seedAccounts()
            }
            // 幂等补齐二级分类：每次启动检查缺失项；既有库也补全（含老分类追加的新子项）
            seedSubCategories()
        }
    }

    /** 完整一级分类。顺序即 id 顺序，必须与 App 端 BillRepositoryImpl.seedCategories() 逐字一致；
     *  只允许追加，不许重排。 */
    private fun seedCategories() {
        val existing = CategoriesTable.selectAll()
            .map { it[CategoriesTable.name] to it[CategoriesTable.billType] }
            .toSet()
        val expenseCategories = listOf(
            "三餐" to "meals",
            "日用" to "daily",
            "交通" to "transport",
            "学习" to "study",
            "运动" to "sports",
            "娱乐" to "entertainment",
            "网购" to "shopping",
            "医疗" to "medical",
            "居家" to "home",
            "人情" to "social",
            "宠物" to "pet",
            "美妆个护" to "beauty",
            "服饰" to "clothing",
            "母婴" to "baby",
            "汽车" to "car",
            "数码" to "digital",
            "保险" to "insurance",
            "旅行" to "travel"
        )
        expenseCategories.forEach { (name, icon) ->
            if (name to "EXPENSE" in existing) return@forEach
            CategoriesTable.insert {
                it[CategoriesTable.name] = name
                it[CategoriesTable.iconName] = icon
                it[CategoriesTable.billType] = "EXPENSE"
            }
        }

        val incomeCategories = listOf(
            "工资" to "salary",
            "兼职" to "parttime",
            "理财" to "finance",
            "其他" to "other",
            "报销" to "reimburse",
            "二手转卖" to "resale",
            "红包礼金" to "redpacket"
        )
        incomeCategories.forEach { (name, icon) ->
            if (name to "INCOME" in existing) return@forEach
            CategoriesTable.insert {
                it[CategoriesTable.name] = name
                it[CategoriesTable.iconName] = icon
                it[CategoriesTable.billType] = "INCOME"
            }
        }
    }

    /** 完整二级分类。顺序即 id 顺序，必须与 App 端 seedSubCategories() 逐字一致；
     *  只允许追加，不许重排（budgets.sub_category_id 引用此 id）。 */
    private fun seedSubCategories() {
        val subMap = mapOf(
            "三餐" to listOf("早餐", "午餐", "晚餐", "零食", "外卖", "饮品"),
            "交通" to listOf("公交", "地铁", "打车", "加油", "停车费", "火车机票", "共享单车"),
            "日用" to listOf("洗衣", "洗漱", "家居", "纸品清洁"),
            "学习" to listOf("书籍", "文具", "培训", "考试", "课程"),
            "运动" to listOf("健身", "跑步", "球类"),
            "娱乐" to listOf("电影", "游戏", "旅游", "演出", "KTV"),
            "网购" to listOf("淘宝", "京东", "快递"),
            "医疗" to listOf("门诊", "药品", "体检", "口腔", "眼镜"),
            "居家" to listOf("房租", "房贷", "物业", "水电燃气", "宽带"),
            "人情" to listOf("红包礼金", "礼物", "请客", "随礼"),
            "宠物" to listOf("粮食", "医疗", "用品", "洗护"),
            "美妆个护" to listOf("护肤彩妆", "理发美发", "美容"),
            "服饰" to listOf("衣裤", "鞋帽", "配饰"),
            "母婴" to listOf("奶粉尿布", "玩具", "早教"),
            "汽车" to listOf("加油", "保养维修", "保险", "洗车"),
            "数码" to listOf("手机电脑", "配件", "软件会员"),
            "保险" to listOf("社保商保", "车险"),
            "旅行" to listOf("机票火车", "酒店", "景点门票"),
            "工资" to listOf("基本工资", "奖金", "补贴"),
            "兼职" to listOf("劳务", "项目", "其他"),
            "理财" to listOf("利息", "基金", "股票"),
            "其他" to listOf("红包", "返还", "其他收入"),
            "报销" to listOf("差旅报销", "日常报销"),
            "二手转卖" to listOf("闲置出售", "回款"),
            "红包礼金" to listOf("收红包", "压岁钱", "礼金")
        )
        val existing = SubCategoriesTable.selectAll()
            .map { it[SubCategoriesTable.parentCategoryId] to it[SubCategoriesTable.name] }
            .toSet()
        for ((catName, subNames) in subMap) {
            val catId = CategoriesTable.selectAll()
                .where { CategoriesTable.name eq catName }
                .singleOrNull()?.get(CategoriesTable.id) ?: continue
            for (subName in subNames) {
                if (catId to subName in existing) continue
                SubCategoriesTable.insert {
                    it[SubCategoriesTable.name] = subName
                    it[SubCategoriesTable.parentCategoryId] = catId
                }
            }
        }
    }

    /** 全局种子（user_id 为空的遗留兜底行）：与 ensureDefaultAccounts 同口径，只建「无账户」，
     *  微信/支付宝等真实钱包一律由用户自建。 */
    private fun seedAccounts() {
        val accounts = listOf(
            Triple("无账户", "#F97D1D", "OTHER")
        )
        accounts.forEach { (name, color, iconKey) ->
            AccountsTable.insert {
                it[AccountsTable.name] = name
                it[AccountsTable.iconColor] = color
                it[AccountsTable.iconKey] = iconKey
                it[AccountsTable.balanceMinor] = 0L
                it[AccountsTable.balance] = 0.0
            }
        }
    }

    data class MonthStats(
        val totalExpenseMinor: Long,
        val totalIncomeMinor: Long,
        val topExpenseCategories: List<Pair<String, Long>>,
        // 日报需要总笔数与收入分类；收入侧此前是空实现，见 InsightService.dailyReport。
        val topIncomeCategories: List<Pair<String, Long>> = emptyList(),
        val billCount: Long = 0
    )

    /**
     * Aggregates a single month with SQL SUM/GROUP BY instead of loading every
     * bill into memory. Used by the insight endpoints.
     */
    fun monthlyStats(userId: Long, monthStart: Long, nextMonthStart: Long): MonthStats = transaction {
        val totalExpense = BillsTable.select(BillsTable.amountMinor.sum())
            .where {
                (BillsTable.userId eq userId) and
                    (BillsTable.deleted eq false) and
                    (BillsTable.billType eq "EXPENSE") and
                    (BillsTable.date greaterEq monthStart) and
                    (BillsTable.date less nextMonthStart)
            }
            .first()[BillsTable.amountMinor.sum()] ?: 0L
        val totalIncome = BillsTable.select(BillsTable.amountMinor.sum())
            .where {
                (BillsTable.userId eq userId) and
                    (BillsTable.deleted eq false) and
                    (BillsTable.billType eq "INCOME") and
                    (BillsTable.date greaterEq monthStart) and
                    (BillsTable.date less nextMonthStart)
            }
            .first()[BillsTable.amountMinor.sum()] ?: 0L
        val topCategories = BillsTable.select(BillsTable.categoryName, BillsTable.amountMinor.sum())
            .where {
                (BillsTable.userId eq userId) and
                    (BillsTable.deleted eq false) and
                    (BillsTable.billType eq "EXPENSE") and
                    (BillsTable.date greaterEq monthStart) and
                    (BillsTable.date less nextMonthStart)
            }
            .groupBy(BillsTable.categoryName)
            .orderBy(BillsTable.amountMinor.sum() to SortOrder.DESC)
            .limit(5)
            .map { it[BillsTable.categoryName] to (it[BillsTable.amountMinor.sum()] ?: 0L) }

        val topIncomeCategories = BillsTable.select(BillsTable.categoryName, BillsTable.amountMinor.sum())
            .where {
                (BillsTable.userId eq userId) and
                    (BillsTable.deleted eq false) and
                    (BillsTable.billType eq "INCOME") and
                    (BillsTable.date greaterEq monthStart) and
                    (BillsTable.date less nextMonthStart)
            }
            .groupBy(BillsTable.categoryName)
            .orderBy(BillsTable.amountMinor.sum() to SortOrder.DESC)
            .limit(5)
            .map { it[BillsTable.categoryName] to (it[BillsTable.amountMinor.sum()] ?: 0L) }
        // 用 Query.count() 而不是手工 select(id.count())：后者要构造两次等价表达式，
        // 依赖 Exposed 表达式相等语义才能从结果行取值；count() 直接走聚合计数，无此隐患。
        val billCount = BillsTable.selectAll()
            .where {
                (BillsTable.userId eq userId) and
                    (BillsTable.deleted eq false) and
                    (BillsTable.date greaterEq monthStart) and
                    (BillsTable.date less nextMonthStart)
            }
            .count()

        // 整数分聚合已无浮点漂移，无需再四舍五入。
        MonthStats(
            totalExpense,
            totalIncome,
            topCategories,
            topIncomeCategories,
            billCount
        )
    }

    fun getCategories(): List<CategoryDTO> = transaction {
        // Load all subcategories in one query and group by parent — avoids the
        // per-category SELECT that was the N+1 here.
        val subGroups = SubCategoriesTable.selectAll()
            .orderBy(SubCategoriesTable.id to SortOrder.ASC)
            .groupBy { it[SubCategoriesTable.parentCategoryId] }
            .mapValues { (_, rows) ->
                rows.map {
                    SubCategoryDTO(
                        id = it[SubCategoriesTable.id],
                        name = it[SubCategoriesTable.name],
                        parentCategoryId = it[SubCategoriesTable.parentCategoryId]
                    )
                }
            }
        CategoriesTable.selectAll()
            .orderBy(CategoriesTable.billType to SortOrder.ASC, CategoriesTable.id to SortOrder.ASC)
            .map { row ->
                CategoryDTO(
                    id = row[CategoriesTable.id],
                    name = row[CategoriesTable.name],
                    iconName = row[CategoriesTable.iconName],
                    billType = row[CategoriesTable.billType],
                    subCategories = subGroups[row[CategoriesTable.id]] ?: emptyList()
                )
            }
    }

    fun updateAccountBalance(id: Long, balanceMinor: Long, userId: Long): AccountDTO? = transaction {
        val row = AccountsTable.selectAll()
            .where { (AccountsTable.id eq id) and (AccountsTable.userId eq userId) }
            .singleOrNull()
            ?: return@transaction null
        val now = System.currentTimeMillis()
        AccountsTable.update({ AccountsTable.id eq id }) {
            it[AccountsTable.balanceMinor] = balanceMinor
            it[AccountsTable.balance] = Money.fromMinor(balanceMinor)
            it[AccountsTable.updatedAt] = now
        }
        row.toAccountDto().copy(balanceMinor = balanceMinor, balance = Money.fromMinor(balanceMinor), updatedAt = now)
    }

    fun getBill(id: Long, userId: Long): BillDTO? = transaction {
        BillsTable.selectAll()
            .where { (BillsTable.id eq id) and (BillsTable.userId eq userId) }
            .singleOrNull()?.toBillDto()
    }

    private fun ResultRow.toBillDto() = BillDTO(
        id = this[BillsTable.id],
        amountMinor = this[BillsTable.amountMinor] ?: 0L,
        amount = Money.fromMinor(this[BillsTable.amountMinor] ?: 0L),
        billType = this[BillsTable.billType],
        categoryId = this[BillsTable.categoryId],
        categoryName = this[BillsTable.categoryName],
        subCategoryName = this[BillsTable.subCategoryName],
        accountId = this[BillsTable.accountId],
        remark = this[BillsTable.remark],
        date = this[BillsTable.date],
        source = this[BillsTable.billSource],
        createdAt = this[BillsTable.createdAt],
        updatedAt = this[BillsTable.updatedAt],
        deleted = this[BillsTable.deleted],
        sortOrder = this[BillsTable.sortOrder],
        latitude = this[BillsTable.latitude],
        longitude = this[BillsTable.longitude]
    )
}
