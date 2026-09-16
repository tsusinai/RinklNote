package com.example.rinklnote.server.plugins

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.tables.*
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction

/**
 * 运行时数据库类型（Database.kt 感知），供管理端 /api/admin/overview 展示。
 * 只暴露类型名（"H2" / "PostgreSQL"），绝不暴露连接 URL / 凭据。
 */
object DbRuntimeInfo {
    @Volatile var typeName: String = "unknown"
}

fun Application.configureDatabase() {
    val url = System.getenv("DATABASE_URL")
        ?: environment.config.propertyOrNull("database.url")?.getString()
        ?: "jdbc:h2:mem:rinklnote;DB_CLOSE_DELAY=-1"

    val isH2 = url.startsWith("jdbc:h2")

    val config = HikariConfig().apply {
        jdbcUrl = url
        if (!isH2) {
            username = environment.config.propertyOrNull("database.user")?.getString() ?: "rinklnote"
            password = environment.config.propertyOrNull("database.password")?.getString() ?: "rinklnote"
        }
        driverClassName = if (isH2) "org.h2.Driver" else "org.postgresql.Driver"
        maximumPoolSize = if (isH2) 2 else 10
    }

    Database.connect(HikariDataSource(config))

    transaction {
        ensureAccountIconKeyColumn()
        SchemaUtils.createMissingTablesAndColumns(UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable, BillsTable, VoiceKeywordsTable, CorrectionLogTable, BotConfigTable, BillTemplatesTable, BudgetsTable, ChallengesTable, WebhookEventTable, PushLogTable, AiApiTokensTable)

        // Performance indexes (not created by createMissingTablesAndColumns)
        runMigrations()
    }

    val billService = BillService()
    billService.seedIfNeeded()
    DbRuntimeInfo.typeName = if (isH2) "H2" else "PostgreSQL"
    log.info("Database initialized (${DbRuntimeInfo.typeName}) and seeded")
}

/**
 * v16 迁移：账户增加稳定图标 key。
 * 必须在 createMissingTablesAndColumns 之前判断列是否存在，只在首次补列时按旧账户名回填，
 * 避免服务重启时覆盖用户后来显式选择的 WALLET。
 */
private fun Transaction.ensureAccountIconKeyColumn() {
    val tableExists = exec(
        """
        SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES
        WHERE UPPER(TABLE_NAME) = 'ACCOUNTS'
        """.trimIndent()
    ) { rs -> rs.next() && rs.getInt(1) > 0 } ?: false
    if (!tableExists) return

    val exists = exec(
        """
        SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
        WHERE UPPER(TABLE_NAME) = 'ACCOUNTS' AND UPPER(COLUMN_NAME) = 'ICON_KEY'
        """.trimIndent()
    ) { rs -> rs.next() && rs.getInt(1) > 0 } ?: false
    if (exists) return

    exec("ALTER TABLE accounts ADD COLUMN icon_key VARCHAR(32) DEFAULT 'WALLET'")
    exec(
        """
        UPDATE accounts
        SET icon_key = CASE name
            WHEN '微信' THEN 'WECHAT'
            WHEN '支付宝' THEN 'ALIPAY'
            WHEN '无账户' THEN 'OTHER'
            ELSE 'WALLET'
        END
        """.trimIndent()
    )
}

private fun Transaction.runMigrations() {
    // v10 迁移：账户改为每用户（ug_accounts_user_name 替代旧 accounts(name) 唯一索引）。
    // 旧唯一索引不移除，则会禁止跨用户同名账户；createMissingTablesAndColumns 不会 drop 旧索引。
    listOf("accounts_name", "accounts_name_unique", "index_accounts_name", "index_accounts_name_unique")
        .forEach { name ->
            try { exec("DROP INDEX IF EXISTS $name") } catch (_: Exception) {}
        }

    // v13 迁移：budgets 支持分类/子分类维度后，(user_id, month_start) 唯一索引不再成立
    // （同月可共存总额/分类/子分类多条预算）。先 drop 旧唯一索引，再建普通索引。
    try { exec("DROP INDEX IF EXISTS uq_budgets_user_month") } catch (_: Exception) {}
    exec("CREATE INDEX IF NOT EXISTS idx_budgets_user_month ON budgets(user_id, month_start)")

    val indexes = listOf(
        "CREATE INDEX IF NOT EXISTS idx_bills_user_id ON bills(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_bills_user_date ON bills(user_id, date)",
        "CREATE INDEX IF NOT EXISTS idx_corrections_processed ON correction_log(processed, user_id)",
        "CREATE INDEX IF NOT EXISTS idx_templates_user ON bill_templates(user_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_push_log_user_type_day ON push_log(user_id, type, day_key)",
        "CREATE INDEX IF NOT EXISTS idx_ai_tokens_user ON ai_api_tokens(user_id)",
        // B1 通道底座补索引：feishu_open_id / wechat_openid / wecom_userid 三列在 UsersTable 里声明的
        // uniqueIndex 只对「新建库」生效 —— createMissingTablesAndColumns 给既有库加列时不会建唯一索引
        // （Exposed 的已知限制）。这里照上方幂等模式手动补齐，保证既有库的多通道身份列同样唯一。
        // 语义安全：Postgres/H2 的唯一索引均允许多行 NULL（未绑定用户不受影响）。
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_users_feishu_open_id ON users(feishu_open_id)",
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_users_wechat_openid ON users(wechat_openid)",
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_users_wecom_userid ON users(wecom_userid)",
        // 邮箱登录（2026-09-17）：email 列同属「给既有库补唯一索引」的范畴 ——
        // createMissingTablesAndColumns 只加列不建索引，这里幂等补齐；多行 NULL（未填邮箱）不受影响。
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_users_email ON users(email)",
        // v13 补充：各层预算的唯一性约束（部分唯一索引，Postgres/H2 均支持）。
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_budgets_total_month ON budgets(user_id, month_start) WHERE category_id IS NULL AND sub_category_id IS NULL",
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_budgets_category_month ON budgets(user_id, month_start, category_id) WHERE category_id IS NOT NULL AND sub_category_id IS NULL",
        "CREATE UNIQUE INDEX IF NOT EXISTS uq_budgets_subcategory_month ON budgets(user_id, month_start, category_id, sub_category_id) WHERE sub_category_id IS NOT NULL",
    )
    indexes.forEach { sql ->
        try {
            exec(sql)
        } catch (_: Exception) {
            // Ignore "index already exists" errors
        }
    }

    // v11 迁移：users.phone/password_hash 改为可空，以支持「QQ openid 自动开户」。
    // createMissingTablesAndColumns 只加表/加列，不会改动已有列 nullability，需手动 ALTER。
    listOf("ALTER TABLE users ALTER COLUMN phone SET NULL",
            "ALTER TABLE users ALTER COLUMN password_hash SET NULL").forEach { sql ->
        try { exec(sql) } catch (_: Exception) {}
    }

    // v14 迁移：users 日报推送三列（子开关 + 时刻）。createMissingTablesAndColumns 通常会自动补列，
    // 这里按幂等 ALTER 兜底，防 Exposed 对既有表漏检（PostgreSQL/H2 均支持 ADD COLUMN IF NOT EXISTS）。
    listOf("ALTER TABLE users ADD COLUMN IF NOT EXISTS daily_report_enabled BOOLEAN DEFAULT FALSE",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS daily_report_hour INT DEFAULT 9",
            "ALTER TABLE users ADD COLUMN IF NOT EXISTS daily_report_minute INT DEFAULT 0").forEach { sql ->
        try { exec(sql) } catch (_: Exception) {}
    }

    // v12 迁移：清理 accounts 上残留的「仅 name（不含 user_id）」唯一约束/索引，确保 (user_id, name) 复合唯一。
    // createMissingTablesAndColumns 只会加表/加列：既不会 drop 旧唯一约束，也不会给既有表补新的唯一索引。
    // 当唯一约束从「name 全局唯一」改成「(user_id, name)」时，旧 schema 留下的唯一约束（H2 自动命名，
    // 如 ACCOUNTS_NAME_UNIQUE_INDEX_6 / CONSTRAINT_INDEX_A 形式的 backing index）会残留。
    // 它禁止跨用户同名账户（微信/支付宝/无账户）插入，于是 ensureDefaultAccounts 抛唯一冲突 → GET /api/accounts 500
    // → Web/App 账户下拉为空、模板新建失败。
    //
    // 注意：H2 2.3 的 INFORMATION_SCHEMA.INDEX_COLUMNS / KEY_COLUMN_USAGE / CONSTRAINT_COLUMN_USAGE 均为空，
    // 无法据此反查「某约束/索引覆盖哪些列」。因此不按列匹配，而是：
    //   1) 用 TABLE_CONSTRAINTS 找出 ACCOUNTS 上的所有 UNIQUE 约束并 DROP CONSTRAINT（会连带删除其 backing index）；
    //   2) 再用 INDEXES 兜底枚举 ACCOUNTS 上仍残留的 UNIQUE 索引（排除主键与复合 uq_accounts_user_name）并 DROP INDEX；
    //   3) 最后确保 (user_id, name) 复合唯一索引存在。
    // 三类 DDL 各自 try/catch，幂等，PostgreSQL 上同样安全（对应视图存在）。
    val staleConstraints: List<String>? = exec(
        "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE UPPER(TABLE_NAME) = 'ACCOUNTS' AND CONSTRAINT_TYPE = 'UNIQUE'"
    ) { rs ->
        val names = mutableListOf<String>()
        while (rs.next()) names.add(rs.getString(1))
        names
    }
    staleConstraints?.forEach { name ->
        try { exec("ALTER TABLE accounts DROP CONSTRAINT IF EXISTS \"$name\"") } catch (_: Exception) {}
    }

    val staleIndexes: List<String>? = exec(
        """
        SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES
        WHERE UPPER(TABLE_NAME) = 'ACCOUNTS'
          AND UPPER(INDEX_TYPE_NAME) LIKE 'UNIQUE%'
          AND UPPER(INDEX_NAME) NOT LIKE 'UQ_ACCOUNTS_USER_NAME%'
          AND UPPER(INDEX_NAME) NOT LIKE 'PRIMARY_KEY%'
        """.trimIndent()
    ) { rs ->
        val names = mutableListOf<String>()
        while (rs.next()) names.add(rs.getString(1))
        names
    }
    staleIndexes?.forEach { name ->
        try { exec("DROP INDEX IF EXISTS \"$name\"") } catch (_: Exception) {}
    }

    try { exec("CREATE UNIQUE INDEX IF NOT EXISTS uq_accounts_user_name ON accounts(user_id, name)") } catch (_: Exception) {}

    // v15 迁移：金额统一为整数分。新增 amount_minor / balance_minor（BIGINT）承载权威值，
    // 旧浮点列 amount / balance 暂保留（过渡期兼容旧客户端，勿直接读取）。
    // createMissingTablesAndColumns 已自动加列（可空，历史数据不会因 NOT NULL 报错），
    // 这里幂等回填历史数据、补默认值 0 并置 NOT NULL。
    // 加列用 ADD COLUMN IF NOT EXISTS 避免与 Exposed 自动建列冲突（PostgreSQL/H2 均支持）。
    listOf(
        "ALTER TABLE bills ADD COLUMN IF NOT EXISTS amount_minor BIGINT",
        "UPDATE bills SET amount_minor = CAST(ROUND(amount * 100) AS BIGINT) WHERE amount_minor IS NULL",
        "ALTER TABLE bills ALTER COLUMN amount_minor SET DEFAULT 0",
        "ALTER TABLE bills ALTER COLUMN amount_minor SET NOT NULL",
        "ALTER TABLE budgets ADD COLUMN IF NOT EXISTS amount_minor BIGINT",
        "UPDATE budgets SET amount_minor = CAST(ROUND(amount * 100) AS BIGINT) WHERE amount_minor IS NULL",
        "ALTER TABLE budgets ALTER COLUMN amount_minor SET DEFAULT 0",
        "ALTER TABLE budgets ALTER COLUMN amount_minor SET NOT NULL",
        "ALTER TABLE bill_templates ADD COLUMN IF NOT EXISTS amount_minor BIGINT",
        "UPDATE bill_templates SET amount_minor = CAST(ROUND(amount * 100) AS BIGINT) WHERE amount_minor IS NULL",
        "ALTER TABLE bill_templates ALTER COLUMN amount_minor SET DEFAULT 0",
        "ALTER TABLE bill_templates ALTER COLUMN amount_minor SET NOT NULL",
        "ALTER TABLE accounts ADD COLUMN IF NOT EXISTS balance_minor BIGINT",
        "UPDATE accounts SET balance_minor = CAST(ROUND(balance * 100) AS BIGINT) WHERE balance_minor IS NULL",
        "ALTER TABLE accounts ALTER COLUMN balance_minor SET DEFAULT 0",
        "ALTER TABLE accounts ALTER COLUMN balance_minor SET NOT NULL"
    ).forEach { sql ->
        try { exec(sql) } catch (_: Exception) {}
    }
}
