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
import com.example.rinklnote.data.db.dao.ChatMessageDao
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.data.db.entity.BillTemplate
import com.example.rinklnote.data.db.entity.Budget
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.ChatMessage
import com.example.rinklnote.data.db.entity.SubCategory

@Database(
    entities = [Bill::class, Category::class, SubCategory::class, Account::class, BillTemplate::class, Budget::class, ChatMessage::class],
    version = 13,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun billDao(): BillDao
    abstract fun categoryDao(): CategoryDao
    abstract fun accountDao(): AccountDao
    abstract fun billTemplateDao(): BillTemplateDao
    abstract fun budgetDao(): BudgetDao
    abstract fun chatMessageDao(): ChatMessageDao

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

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "rinklnote.db"
            )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13)
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
