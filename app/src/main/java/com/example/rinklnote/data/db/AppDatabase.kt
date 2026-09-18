package com.example.rinklnote.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.rinklnote.data.db.dao.AccountDao
import com.example.rinklnote.data.db.dao.BillDao
import com.example.rinklnote.data.db.dao.BillTemplateDao
import com.example.rinklnote.data.db.dao.BudgetDao
import com.example.rinklnote.data.db.dao.CategoryDao
import com.example.rinklnote.data.db.dao.ChallengeDao
import com.example.rinklnote.data.db.dao.ChatMessageDao
import com.example.rinklnote.data.db.dao.PlaceDao
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Challenge
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.ChatMessage
import com.example.rinklnote.data.db.entity.Place
import com.example.rinklnote.data.db.entity.SubCategory

@Database(
    entities = [Bill::class, Category::class, SubCategory::class, Account::class, BillTemplate::class, Budget::class, ChatMessage::class, Challenge::class, Place::class],
    version = 17,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun billDao(): BillDao
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao
    abstract fun billTemplateDao(): BillTemplateDao
    abstract fun budgetDao(): BudgetDao
    abstract fun challengeDao(): ChallengeDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun placeDao(): PlaceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) { /* no schema change */ }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) { /* no schema change */ }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN source TEXT NOT NULL DEFAULT 'APP'")
                db.execSQL("ALTER TABLE bills ADD COLUMN server_id INTEGER DEFAULT NULL")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bills_server_id ON bills(server_id)")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN updated_at INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE bills ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bill_templates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        server_id INTEGER,
                        label TEXT NOT NULL,
                        amount REAL NOT NULL,
                        category_id INTEGER NOT NULL,
                        category_name TEXT NOT NULL,
                        sub_category_name TEXT,
                        account_id INTEGER NOT NULL,
                        sort_order INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bill_templates_server_id ON bill_templates(server_id)")
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS budgets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        server_id INTEGER,
                        month_start INTEGER NOT NULL,
                        amount REAL NOT NULL,
                        updated_at INTEGER,
                        deleted INTEGER NOT NULL,
                        dirty INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_server_id ON budgets(server_id)")
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS chat_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        role TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN base_updated_at INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE accounts ADD COLUMN server_id INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE accounts ADD COLUMN updated_at INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE accounts ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE accounts ADD COLUMN dirty INTEGER NOT NULL DEFAULT 0")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_accounts_server_id ON accounts(server_id)")
                db.execSQL("DROP INDEX IF EXISTS index_accounts_name")
            }
        }
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE budgets ADD COLUMN period_type TEXT NOT NULL DEFAULT 'MONTHLY'")
                db.execSQL("ALTER TABLE budgets ADD COLUMN category_id INTEGER")
                db.execSQL("ALTER TABLE budgets ADD COLUMN sub_category_id INTEGER")
            }
        }
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            // 拖动重排：同日显式排序名次；NULL = 未显式排序（查询按 COALESCE(sort_order, created_at) 兜底）。
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN sort_order INTEGER DEFAULT NULL")
            }
        }

        /**
         * v13：金额统一为整数分。SQLite 不能修改既有列的类型（且 Room 会校验列亲和性
         * REAL ≠ INTEGER），因此按官方建议重建四张含金额的表。
         *
         * 顺序说明：bills 外键引用 accounts（categories / accounts）。若外键约束在迁移期间
         * 处于开启状态，直接 DROP 被引用的 accounts 会失败；因此先把 bills 的数据搬到
         * 一张无约束的中转表并删掉 bills，再重建 accounts（此时已无子表引用它），
         * 最后带着外键重建 bills 并从中转表回填——该顺序在外键开与关两种状态下都安全。
         */
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1) bills 数据先转移到无约束中转表（原样保存，金额换算放到最后一步）
                db.execSQL("CREATE TABLE bills_migration_backup AS SELECT * FROM bills")
                db.execSQL("DROP TABLE bills")

                // 2) 重建 accounts：balance REAL → balance_minor INTEGER
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS accounts_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        balance_minor INTEGER NOT NULL,
                        icon_color TEXT NOT NULL,
                        server_id INTEGER,
                        updated_at INTEGER,
                        deleted INTEGER NOT NULL,
                        dirty INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO accounts_new (id, name, balance_minor, icon_color, server_id, updated_at, deleted, dirty)
                    SELECT id, name, CAST(ROUND(balance * 100) AS INTEGER), icon_color, server_id, updated_at, deleted, dirty
                    FROM accounts
                """.trimIndent())
                db.execSQL("DROP TABLE accounts")
                db.execSQL("ALTER TABLE accounts_new RENAME TO accounts")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_accounts_server_id ON accounts(server_id)")

                // 3) 重建 bills：amount REAL → amount_minor INTEGER（外键定义保持不变）
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bills_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        amount_minor INTEGER NOT NULL,
                        bill_type TEXT NOT NULL,
                        category_id INTEGER NOT NULL,
                        category_name TEXT NOT NULL,
                        sub_category_name TEXT,
                        account_id INTEGER NOT NULL,
                        remark TEXT,
                        date INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        source TEXT NOT NULL,
                        server_id INTEGER,
                        updated_at INTEGER,
                        base_updated_at INTEGER,
                        sort_order INTEGER,
                        deleted INTEGER NOT NULL,
                        dirty INTEGER NOT NULL,
                        FOREIGN KEY(category_id) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE NO ACTION,
                        FOREIGN KEY(account_id) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE NO ACTION
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO bills_new (id, amount_minor, bill_type, category_id, category_name, sub_category_name, account_id, remark, date, created_at, source, server_id, updated_at, base_updated_at, sort_order, deleted, dirty)
                    SELECT id, CAST(ROUND(amount * 100) AS INTEGER), bill_type, category_id, category_name, sub_category_name, account_id, remark, date, created_at, source, server_id, updated_at, base_updated_at, sort_order, deleted, dirty
                    FROM bills_migration_backup
                """.trimIndent())
                db.execSQL("DROP TABLE bills_migration_backup")
                db.execSQL("ALTER TABLE bills_new RENAME TO bills")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bills_category_id ON bills(category_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bills_account_id ON bills(account_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_bills_date ON bills(date)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bills_server_id ON bills(server_id)")

                // 4) budgets / bill_templates 无外键，直接重建
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS budgets_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        server_id INTEGER,
                        month_start INTEGER NOT NULL,
                        period_type TEXT NOT NULL DEFAULT 'MONTHLY',
                        category_id INTEGER,
                        sub_category_id INTEGER,
                        amount_minor INTEGER NOT NULL,
                        updated_at INTEGER,
                        deleted INTEGER NOT NULL,
                        dirty INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO budgets_new (id, server_id, month_start, period_type, category_id, sub_category_id, amount_minor, updated_at, deleted, dirty)
                    SELECT id, server_id, month_start, period_type, category_id, sub_category_id, CAST(ROUND(amount * 100) AS INTEGER), updated_at, deleted, dirty
                    FROM budgets
                """.trimIndent())
                db.execSQL("DROP TABLE budgets")
                db.execSQL("ALTER TABLE budgets_new RENAME TO budgets")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_budgets_server_id ON budgets(server_id)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bill_templates_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        server_id INTEGER,
                        label TEXT NOT NULL,
                        amount_minor INTEGER NOT NULL,
                        category_id INTEGER NOT NULL,
                        category_name TEXT NOT NULL,
                        sub_category_name TEXT,
                        account_id INTEGER NOT NULL,
                        sort_order INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO bill_templates_new (id, server_id, label, amount_minor, category_id, category_name, sub_category_name, account_id, sort_order)
                    SELECT id, server_id, label, CAST(ROUND(amount * 100) AS INTEGER), category_id, category_name, sub_category_name, account_id, sort_order
                    FROM bill_templates
                """.trimIndent())
                db.execSQL("DROP TABLE bill_templates")
                db.execSQL("ALTER TABLE bill_templates_new RENAME TO bill_templates")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bill_templates_server_id ON bill_templates(server_id)")
            }
        }

        /** v14：账户增加稳定的图标协议 key，并按旧账户名回填。 */
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE accounts ADD COLUMN icon_key TEXT NOT NULL DEFAULT 'WALLET'")
                db.execSQL(
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
        }

        /** v15：新增省钱挑战表 challenges（goal 单位由 type 决定：天数或整数分，见实体注释）。 */
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS challenges (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        server_id INTEGER,
                        type TEXT NOT NULL,
                        period_start INTEGER NOT NULL,
                        goal INTEGER NOT NULL,
                        status TEXT NOT NULL DEFAULT 'ACTIVE',
                        updated_at INTEGER,
                        deleted INTEGER NOT NULL,
                        dirty INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_challenges_server_id ON challenges(server_id)")
            }
        }

        /** v16：bills 增加账单打点经纬度（可空 REAL；NULL = 未打点，老账单不受影响）。 */
        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bills ADD COLUMN latitude REAL DEFAULT NULL")
                db.execSQL("ALTER TABLE bills ADD COLUMN longitude REAL DEFAULT NULL")
            }
        }

        /**
         * v17：新增常去地点表 places（只加新表，不动任何旧表——无 v13 重建顺序问题；
         * 不参与同步、无外键，本机数据）。
         */
        private val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS places (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        lat REAL NOT NULL,
                        lng REAL NOT NULL,
                        category_id INTEGER,
                        last_used INTEGER NOT NULL
                    )
                """.trimIndent())
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "rinklnote.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17)
                // 不再挂 fallbackToDestructiveMigration 兜底：MIGRATION_1_2 … 16_17 全链路已覆盖，
                // 未覆盖路径应升级期报错暴露（宁可崩溃也不静默清库）。
                .build()
        }
    }
}
