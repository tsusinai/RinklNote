# QuickAdd 效率提升 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add NLP input, one-click templates, and smart suggestions to QuickAdd drawer to reduce repetitive manual entry.

**Architecture:** Templates stored in DB and synced across devices. NLP parsing reuses existing NLUService endpoint. Smart suggestions computed server-side from recent bill history, configurable via Web UI. All three components share the QuickAdd drawer space.

**Tech Stack:** Ktor (server), Kotlin/Compose (Android), Vanilla JS/Single-file SPA (Web)

## Global Constraints

- All UI text in Chinese
- QuickAdd two-step confirmation: skip for template/suggestion, keep for manual entry
- Server API responses use `mapOf` (all string values) for JSON
- Android Room `@Upsert` requires minSdk ≥ 28 (already met)
- Database migrations: use `createMissingTablesAndColumns` for server, Room `Migration` for Android

---

### Task 1: Server — BillTemplates table + DB migration

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/tables/BillTemplatesTable.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/plugins/Database.kt:32`

**Interfaces:**
- Produces: `BillTemplatesTable` object (id, userId, label, amount, categoryId, categoryName, subCategoryName, accountId, sortOrder, createdAt, updatedAt)

- [ ] **Step 1: Create table**

```kotlin
package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object BillTemplatesTable : Table("bill_templates") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id)
    val label = varchar("label", 30)
    val amount = double("amount")
    val categoryId = long("category_id").references(CategoriesTable.id)
    val categoryName = varchar("category_name", 50)
    val subCategoryName = varchar("sub_category_name", 50).nullable()
    val accountId = long("account_id").references(AccountsTable.id)
    val sortOrder = integer("sort_order").default(0)
    val createdAt = long("created_at")
    val updatedAt = long("updated_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
```

- [ ] **Step 2: Add to Database migration**

In `Database.kt`, add `BillTemplatesTable` to the `createMissingTablesAndColumns` call:
```kotlin
SchemaUtils.createMissingTablesAndColumns(
    UsersTable, CategoriesTable, SubCategoriesTable, AccountsTable,
    BillsTable, VoiceKeywordsTable, CorrectionLogTable, BotConfigTable,
    BillTemplatesTable  // add this
)
```

- [ ] **Step 3: Add index in runMigrations**

In `Database.kt`, add to the indexes list:
```kotlin
"CREATE INDEX IF NOT EXISTS idx_templates_user ON bill_templates(user_id)",
```

- [ ] **Step 4: Compile and commit**

```bash
./gradlew :server:compileKotlin
git add server/
git commit -m "feat: add BillTemplates table with DB migration"
```

---

### Task 2: Server — TemplateService + CRUD API

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/services/TemplateService.kt`
- Create: `server/src/main/kotlin/com/example/rinklnote/server/routes/TemplateRoutes.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/Application.kt:89-96`

**Interfaces:**
- Consumes: `BillTemplatesTable` from Task 1
- Produces: `TemplateService.create/list/update/delete/reorder`, `TemplateRoutes`

- [ ] **Step 1: Write TemplateService**

```kotlin
package com.example.rinklnote.server.services

import com.example.rinklnote.server.tables.BillTemplatesTable
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class TemplateDTO(
    val id: Long, val label: String, val amount: Double,
    val categoryId: Long, val categoryName: String,
    val subCategoryName: String? = null, val accountId: Long,
    val sortOrder: Int = 0
)

class TemplateService {
    fun list(userId: Long): List<TemplateDTO> = transaction {
        BillTemplatesTable.selectAll()
            .where { BillTemplatesTable.userId eq userId }
            .orderBy(BillTemplatesTable.sortOrder)
            .map {
                TemplateDTO(
                    id = it[BillTemplatesTable.id], label = it[BillTemplatesTable.label],
                    amount = it[BillTemplatesTable.amount], categoryId = it[BillTemplatesTable.categoryId],
                    categoryName = it[BillTemplatesTable.categoryName],
                    subCategoryName = it[BillTemplatesTable.subCategoryName],
                    accountId = it[BillTemplatesTable.accountId], sortOrder = it[BillTemplatesTable.sortOrder]
                )
            }
    }

    fun create(userId: Long, dto: TemplateDTO): TemplateDTO {
        val now = System.currentTimeMillis()
        val id = transaction {
            BillTemplatesTable.insert {
                it[BillTemplatesTable.userId] = userId
                it[label] = dto.label; it[amount] = dto.amount
                it[categoryId] = dto.categoryId; it[categoryName] = dto.categoryName
                it[subCategoryName] = dto.subCategoryName; it[accountId] = dto.accountId
                it[sortOrder] = dto.sortOrder
                it[createdAt] = now; it[updatedAt] = now
            } get BillTemplatesTable.id
        }
        return dto.copy(id = id)
    }

    fun update(userId: Long, templateId: Long, dto: TemplateDTO): Boolean {
        val now = System.currentTimeMillis()
        return transaction {
            BillTemplatesTable.update({
                (BillTemplatesTable.id eq templateId) and (BillTemplatesTable.userId eq userId)
            }) {
                it[label] = dto.label; it[amount] = dto.amount
                it[categoryId] = dto.categoryId; it[categoryName] = dto.categoryName
                it[subCategoryName] = dto.subCategoryName; it[accountId] = dto.accountId
                it[updatedAt] = now
            }
        } > 0
    }

    fun delete(userId: Long, templateId: Long): Boolean = transaction {
        BillTemplatesTable.deleteWhere {
            (BillTemplatesTable.id eq templateId) and (BillTemplatesTable.userId eq userId)
        } > 0
    }

    fun reorder(userId: Long, ids: List<Long>) = transaction {
        ids.forEachIndexed { index, id ->
            BillTemplatesTable.update({
                (BillTemplatesTable.id eq id) and (BillTemplatesTable.userId eq userId)
            }) { it[sortOrder] = index }
        }
    }
}
```

- [ ] **Step 2: Write TemplateRoutes**

```kotlin
package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.TemplateDTO
import com.example.rinklnote.server.services.TemplateService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class ReorderRequest(val ids: List<Long>)

fun Route.templateRoutes(templateService: TemplateService) {
    authenticate("auth-jwt") {
        route("/api/templates") {
            get {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)
                call.respond(templateService.list(userId))
            }

            post {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<TemplateDTO>()
                val created = templateService.create(userId, body)
                call.respond(HttpStatusCode.Created, created)
            }

            put("/{id}") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized)
                val tid = call.parameters["id"]?.toLongOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest)
                val body = call.receive<TemplateDTO>()
                if (templateService.update(userId, tid, body))
                    call.respond(mapOf("message" to "已更新"))
                else call.respond(HttpStatusCode.NotFound, mapOf("message" to "模板不存在"))
            }

            delete("/{id}") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized)
                val tid = call.parameters["id"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)
                if (templateService.delete(userId, tid))
                    call.respond(mapOf("message" to "已删除"))
                else call.respond(HttpStatusCode.NotFound, mapOf("message" to "模板不存在"))
            }

            post("/reorder") {
                val userId = call.principal<JWTPrincipal>()?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)
                val body = call.receive<ReorderRequest>()
                templateService.reorder(userId, body.ids)
                call.respond(mapOf("message" to "已排序"))
            }
        }
    }
}
```

- [ ] **Step 3: Register in Application.kt**

Add import and route registration:
```kotlin
import com.example.rinklnote.server.services.TemplateService
// ...
val templateService = TemplateService()

routing {
    // ... existing routes ...
    templateRoutes(templateService)
}
```

- [ ] **Step 4: Compile and commit**

```bash
./gradlew :server:compileKotlin
git add server/
git commit -m "feat: add TemplateService + CRUD API for bill templates"
```

---

### Task 3: Server — NLU parse endpoint + Smart suggestion

**Files:**
- Modify: `server/.../routes/BillRoutes.kt` — add `/api/bills/parse`
- Modify: `server/.../services/insight/InsightService.kt` — add `suggestDailyPattern`
- Modify: `server/.../routes/InsightRoutes.kt` — add suggest + config endpoints

**Interfaces:**
- Consumes: `NLUService.parse(text, userId)` from existing, `BillService.syncBills` from existing
- Produces: `POST /api/bills/parse`, `GET /api/insights/suggest`, `GET/PUT /api/insights/suggest-config`

- [ ] **Step 1: Add parse endpoint to BillRoutes**

In `BillRoutes.kt`, inside the `route("/api/bills")` block, before `post`:
```kotlin
@Serializable
data class ParseRequest(val text: String)

@Serializable
data class ParseResponse(
    val amount: String, val categoryName: String,
    val subCategoryName: String, val remark: String
)

// Add nluService parameter to billRoutes function signature:
// fun Route.billRoutes(billService: BillService, nluService: NLUService? = null)
// ... or add a separate parse endpoint in BillRoutes (constructor receives nluService)

post("/parse") {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal?.payload?.getClaim("userId")?.asLong()
        ?: return@post call.respond(HttpStatusCode.Unauthorized)
    val body = call.receive<ParseRequest>()
    val result = nluService!!.parse(body.text, userId)
    if (result.amount == null) {
        return@post call.respond(HttpStatusCode.BadRequest,
            mapOf("message" to "无法识别金额"))
    }
    call.respond(mapOf(
        "amount" to result.amount.toString(),
        "categoryName" to (result.categoryName ?: "三餐"),
        "remark" to (result.remark ?: body.text),
        "subCategoryName" to ""
    ))
}
```

- [ ] **Step 2: Update BillRoutes signature to accept nluService**

```kotlin
fun Route.billRoutes(billService: BillService, nluService: NLUService? = null) {
```

- [ ] **Step 3: Update Application.kt route registration**

```kotlin
billRoutes(billService, nluService)
```

- [ ] **Step 4: Add suggestDailyPattern to InsightService**

```kotlin
fun suggestDailyPattern(userId: Long): Map<String, String>? {
    val bills = transaction { syncBills(userId, null).bills }
    val now = LocalDate.now(ZoneId.of("Asia/Shanghai"))
    val hour = java.time.LocalTime.now(ZoneId.of("Asia/Shanghai")).hour

    val config = loadSuggestConfig()
    if (!config.enabled) return null

    val window = config.timeWindows.find { hour in it.startHour until it.endHour }
        ?: return null

    val lookbackStart = now.minusDays(config.lookbackDays.toLong())
        .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
    val recent = bills.filter { it.date >= lookbackStart && it.billType == "EXPENSE" }

    val timeBills = recent.filter {
        val h = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(it.date), ZoneId.of("Asia/Shanghai"))
            .atTime(java.time.LocalTime.of(0, 0))
        // Simplify: match by bill date (hour not available in current data model)
        true
    }

    val grouped = timeBills.groupBy { Pair(it.categoryName, it.amount) }
    val best = grouped.maxByOrNull { it.value.size } ?: return null
    if (best.value.size < config.minOccurrences) return null

    return mapOf(
        "label" to window.label,
        "categoryName" to best.key.first,
        "amount" to best.key.second.toString()
    )
}

data class SuggestConfig(
    val enabled: Boolean = true,
    val lookbackDays: Int = 7,
    val minOccurrences: Int = 3,
    val displayDuration: Int = 5000,
    val timeWindows: List<TimeWindow> = listOf(
        TimeWindow("早餐", 7, 10),
        TimeWindow("午餐", 11, 14),
        TimeWindow("晚餐", 17, 20)
    )
)

data class TimeWindow(val label: String, val startHour: Int, val endHour: Int)

private fun loadSuggestConfig(): SuggestConfig {
    return try {
        transaction {
            val row = BotConfigTable.selectAll()
                .where { BotConfigTable.key eq "suggest_config" }.singleOrNull()
            if (row != null) {
                Json { ignoreUnknownKeys = true }.decodeFromString<SuggestConfig>(row[BotConfigTable.value])
            } else SuggestConfig()
        }
    } catch (_: Exception) { SuggestConfig() }
}

fun saveSuggestConfig(config: SuggestConfig) {
    val json = Json { encodeDefaults = true }.encodeToString(SuggestConfig.serializer(), config)
    transaction {
        val exists = BotConfigTable.selectAll()
            .where { BotConfigTable.key eq "suggest_config" }.singleOrNull()
        if (exists != null) {
            BotConfigTable.update({ BotConfigTable.key eq "suggest_config" }) {
                it[value] = json
            }
        } else {
            BotConfigTable.insert {
                it[key] = "suggest_config"
                it[value] = json
            }
        }
    }
}
```

Need to add imports: `kotlinx.serialization.encodeToString`, `kotlinx.serialization.decodeFromString`

- [ ] **Step 5: Add endpoints to InsightRoutes**

```kotlin
get("/suggest") {
    val principal = call.principal<JWTPrincipal>()
    val userId = principal?.payload?.getClaim("userId")?.asLong()
        ?: return@get call.respond(HttpStatusCode.Unauthorized)
    val suggestion = insightService.suggestDailyPattern(userId)
    if (suggestion != null) call.respond(suggestion)
    else call.respond(mapOf<String, String>())
}

get("/suggest-config") {
    call.respond(insightService.loadSuggestConfig())
}

put("/suggest-config") {
    val body = call.receive<SuggestConfig>()
    insightService.saveSuggestConfig(body)
    call.respond(mapOf("message" to "配置已保存"))
}
```

- [ ] **Step 6: Compile and commit**

```bash
./gradlew :server:compileKotlin
git add server/
git commit -m "feat: add NLU parse endpoint, smart suggestion API with config"
```

---

### Task 4: Android — Data layer (BillTemplate entity + DAO + DTOs + ApiService)

**Files:**
- Create: `app/src/main/java/com/example/rinklnote/data/db/entity/BillTemplate.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/db/dao/BillTemplateDao.kt` (create)
- Modify: `app/src/main/java/com/example/rinklnote/data/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/network/dto/DTOs.kt`
- Modify: `app/src/main/java/com/example/rinklnote/data/network/ApiService.kt`

**Interfaces:**
- Produces: `BillTemplate` entity, `BillTemplateDao`, updated `ApiService`

- [ ] **Step 1: Create BillTemplate entity**

```kotlin
package com.example.rinklnote.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@androidx.compose.runtime.Immutable
@Entity(
    tableName = "bill_templates",
    indices = [Index("server_id", unique = true)]
)
data class BillTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "server_id") val serverId: Long? = null,
    val label: String,
    val amount: Double,
    @ColumnInfo(name = "category_id") val categoryId: Long,
    @ColumnInfo(name = "category_name") val categoryName: String,
    @ColumnInfo(name = "sub_category_name") val subCategoryName: String? = null,
    @ColumnInfo(name = "account_id") val accountId: Long,
    @ColumnInfo(name = "sort_order") val sortOrder: Int = 0
)
```

- [ ] **Step 2: Create BillTemplateDao**

```kotlin
package com.example.rinklnote.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.example.rinklnote.data.db.entity.BillTemplate
import kotlinx.coroutines.flow.Flow

@Dao
interface BillTemplateDao {
    @Query("SELECT * FROM bill_templates ORDER BY sort_order")
    fun observeAll(): Flow<List<BillTemplate>>

    @Upsert
    suspend fun upsertAll(templates: List<BillTemplate>)

    @Query("DELETE FROM bill_templates")
    suspend fun deleteAll()

    @Query("DELETE FROM bill_templates WHERE id = :id")
    suspend fun deleteById(id: Long)
}
```

- [ ] **Step 3: Update AppDatabase**

Add BillTemplate to entities list, bump version to 7, add migration:
```kotlin
@Database(
    entities = [Bill::class, Category::class, SubCategory::class, Account::class, BillTemplate::class],
    version = 7,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun billTemplateDao(): BillTemplateDao
    // ...

    companion object {
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS bill_templates (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        server_id INTEGER DEFAULT NULL UNIQUE,
                        label TEXT NOT NULL,
                        amount REAL NOT NULL,
                        category_id INTEGER NOT NULL,
                        category_name TEXT NOT NULL,
                        sub_category_name TEXT DEFAULT NULL,
                        account_id INTEGER NOT NULL,
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())
            }
        }
        // add MIGRATION_6_7 to addMigrations() list
    }
}
```

- [ ] **Step 4: Update DTOs**

Add to DTOs.kt:
```kotlin
@Serializable
data class TemplateDTO(
    val id: Long = 0, val label: String, val amount: Double,
    val categoryId: Long, val categoryName: String,
    val subCategoryName: String? = null, val accountId: Long,
    val sortOrder: Int = 0
)

@Serializable
data class ParseRequest(val text: String)

@Serializable
data class ParseResponse(
    val amount: String = "",
    val categoryName: String = "",
    val remark: String = "",
    val subCategoryName: String = ""
)

@Serializable
data class ReorderRequest(val ids: List<Long>)
```

- [ ] **Step 5: Update ApiService**

```kotlin
// Templates
@GET("api/templates")
suspend fun getTemplates(): List<TemplateDTO>

@POST("api/templates")
suspend fun createTemplate(@Body template: TemplateDTO): TemplateDTO

@PUT("api/templates/{id}")
suspend fun updateTemplate(@Path("id") id: Long, @Body template: TemplateDTO): MessageResponse

@DELETE("api/templates/{id}")
suspend fun deleteTemplate(@Path("id") id: Long): MessageResponse

@POST("api/templates/reorder")
suspend fun reorderTemplates(@Body request: ReorderRequest): MessageResponse

// NLP Parse
@POST("api/bills/parse")
suspend fun parseBill(@Body request: ParseRequest): ParseResponse

// Smart Suggestion
@GET("api/insights/suggest")
suspend fun getSuggestion(): Map<String, String>

@GET("api/insights/suggest-config")
suspend fun getSuggestConfig(): Map<String, String>

@PUT("api/insights/suggest-config")
suspend fun updateSuggestConfig(@Body config: Map<String, String>): MessageResponse
```

- [ ] **Step 6: Compile and commit**

```bash
./gradlew :app:compileDebugKotlin
git add app/
git commit -m "feat: add BillTemplate entity, DAO, DTOs, and API endpoints for templates/parse/suggest"
```

---

### Task 5: Android — SyncManager template sync

**Files:**
- Modify: `app/src/main/java/com/example/rinklnote/sync/SyncManager.kt`

**Interfaces:**
- Consumes: `BillTemplateDao` from Task 4, `ApiService` template methods from Task 4

- [ ] **Step 1: Add BillTemplateDao to SyncManager constructor + syncTemplates**

```kotlin
class SyncManager(
    private val api: ApiService,
    private val tokenManager: TokenManager,
    private val billDao: BillDao,
    private val templateDao: BillTemplateDao  // add
) {
    // Add to sync() method, after bill push:
    // 4. Sync templates
    try {
        val serverTemplates = api.getTemplates()
        val local = templateDao.observeAll().first()
        if (serverTemplates != local.map { it.toDTO() }) {
            templateDao.deleteAll()
            templateDao.upsertAll(serverTemplates.map { it.toEntity() })
        }
    } catch (_: Exception) {}

    // Add to RinklNoteApp.kt constructor:
    // SyncManager(api, tokenManager, billDao, billTemplateDao)
}

// Extension functions
private fun TemplateDTO.toEntity() = BillTemplate(
    serverId = id, label = label, amount = amount,
    categoryId = categoryId, categoryName = categoryName,
    subCategoryName = subCategoryName, accountId = accountId,
    sortOrder = sortOrder
)

private fun BillTemplate.toDTO() = TemplateDTO(
    id = serverId ?: 0, label = label, amount = amount,
    categoryId = categoryId, categoryName = categoryName,
    subCategoryName = subCategoryName, accountId = accountId,
    sortOrder = sortOrder
)
```

- [ ] **Step 2: Update RinklNoteApp.kt constructor**

```kotlin
val syncManager = SyncManager(api, tokenManager, db.billDao(), db.billTemplateDao())
```

- [ ] **Step 3: Compile and commit**

```bash
./gradlew :app:compileDebugKotlin
git commit -am "feat: sync templates in SyncManager"
```

---

### Task 6: Android — QuickAdd UI: NLP input + template row + suggestion bubble

**Files:**
- Modify: `app/.../ui/viewmodel/QuickAddViewModel.kt`
- Modify: `app/.../ui/screen/home/QuickAddDrawer.kt` (or the drawer composable)

**Interfaces:**
- Consumes: `ApiService` parse/suggest, `BillTemplateDao.observeAll()`, `SyncManager`
- Produces: Updated QuickAddState with templates, NLP input, suggestion

- [ ] **Step 1: Add state fields to QuickAddState**

```kotlin
data class QuickAddState(
    // ... existing fields ...
    val templates: List<BillTemplate> = emptyList(),
    val nlpInput: String = "",
    val isParsing: Boolean = false,
    val suggestion: SuggestionData? = null,
    val suggestionDismissed: Boolean = false
)

@DataClass
data class SuggestionData(
    val label: String, val categoryName: String, val amount: Double
)
```

- [ ] **Step 2: Add ViewModel methods**

```kotlin
fun onNlpSubmit() {
    val text = _state.value.nlpInput.trim()
    if (text.isBlank()) return
    _state.update { it.copy(isParsing = true) }
    viewModelScope.launch {
        try {
            val result = api.parseBill(ParseRequest(text))
            val cat = state.value.expenseCategories.find {
                it.name == result.categoryName
            } ?: state.value.incomeCategories.find { it.name == result.categoryName }
            if (cat != null && result.amount.isNotBlank()) {
                _state.update {
                    it.copy(
                        amount = result.amount,
                        selectedCategory = cat,
                        billType = cat.billType,
                        remark = result.remark,
                        nlpInput = "",
                        isParsing = false
                    )
                }
                // Auto-confirm: skip two-step for NLP entries
                _effects.trySend(QuickAddEffect.ConfirmRequested)
            }
        } catch (_: Exception) {
            _state.update { it.copy(isParsing = false) }
            _effects.trySend(QuickAddEffect.ParseError)
        }
    }
}

fun onTemplateClick(template: BillTemplate) {
    val cat = _state.value.expenseCategories.find { it.id == template.categoryId }
        ?: _state.value.incomeCategories.find { it.id == template.categoryId } ?: return
    val acct = _state.value.accounts.find { it.id == template.accountId } ?: return
    _state.update {
        it.copy(
            amount = template.amount.toString(),
            selectedCategory = cat,
            billType = cat.billType,
            selectedSubCategory = template.subCategoryName?.let { name ->
                cat.subCategories?.find { it.name == name }
            },
            selectedAccount = acct,
            remark = null
        )
    }
    viewModelScope.launch { finalConfirm() }
}

fun onSuggestionClick() {
    val s = _state.value.suggestion ?: return
    val cat = (_state.value.expenseCategories + _state.value.incomeCategories)
        .find { it.name == s.categoryName } ?: return
    val acct = _state.value.accounts.first()
    _state.update {
        it.copy(
            amount = s.amount.toString(),
            selectedCategory = cat,
            billType = cat.billType,
            selectedAccount = acct,
            remark = null,
            suggestion = null,
            suggestionDismissed = true
        )
    }
    viewModelScope.launch { finalConfirm() }
}

fun loadTemplates(templates: List<BillTemplate>) {
    _state.update { it.copy(templates = templates) }
}

fun loadSuggestion() {
    viewModelScope.launch {
        try {
            val result = api.getSuggestion()
            if (result.isNotEmpty() && result.containsKey("categoryName")) {
                _state.update {
                    it.copy(suggestion = SuggestionData(
                        label = result["label"] ?: "",
                        categoryName = result["categoryName"] ?: "",
                        amount = result["amount"]?.toDoubleOrNull() ?: 0.0
                    ))
                }
                delay(_state.value.suggestion?.let { 5000L } ?: 0) // displayDuration
                _state.update { it.copy(suggestion = null) }
            }
        } catch (_: Exception) {}
    }
}
```

- [ ] **Step 3: Update QuickAddDrawer UI layout**

In the drawer Composable, add above the category selection:

```kotlin
// NLP Input Bar
Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
    OutlinedTextField(
        value = state.nlpInput,
        onValueChange = { viewModel.onEvent(QuickAddEvent.NlpInput(it)) },
        placeholder = { Text("午餐25元", fontSize = 13.sp) },
        singleLine = true,
        modifier = Modifier.weight(1f).animateContentSize(),
        enabled = !state.isParsing
    )
    Spacer(Modifier.width(8.dp))
    Button(onClick = { viewModel.onNlpSubmit() }, enabled = !state.isParsing) {
        Text(if (state.isParsing) "..." else "识别")
    }
}

// Template Row
if (state.templates.isNotEmpty()) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(state.templates) { tmpl ->
            AssistChip(
                onClick = { viewModel.onTemplateClick(tmpl) },
                label = { Text("${tmpl.label} ¥${tmpl.amount.toInt()}", fontSize = 12.sp) }
            )
        }
        item {
            IconButton(onClick = { /* save current as template */ }) {
                Icon(Icons.Default.Add, "新建模板")
            }
        }
    }
}

// Suggestion Bubble
if (state.suggestion != null && !state.suggestionDismissed) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("💡 ${state.suggestion.label}", modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { viewModel.onSuggestionClick() }) { Text("记一笔") }
        }
    }
}
```

- [ ] **Step 4: Wire loadTemplates and loadSuggestion in drawer open**

In the Composable that opens the drawer (AppNavigation or similar), add LaunchedEffect:
```kotlin
LaunchedEffect(Unit) {
    app.billTemplateDao.observeAll().collect { templates ->
        quickAddVM.loadTemplates(templates)
    }
    quickAddVM.loadSuggestion()
}
```

- [ ] **Step 5: Compile and commit**

```bash
./gradlew :app:compileDebugKotlin
git commit -am "feat: add NLP input, template row, and suggestion bubble to QuickAdd"
```

---

### Task 7: Web — Template management + Suggest config

**Files:**
- Modify: `web/index.html`

**Interfaces:**
- Consumes: Template CRUD API from Task 2, suggest config API from Task 3

- [ ] **Step 1: Add template management to renderSettings**

In `renderSettings`, after the Bot Settings section, add:

```javascript
// Template Management
pg.append(h('h3',{style:'margin-top:24px;margin-bottom:12px'},'记账模板'));
const tmplBox = h('div',{className:'card',style:'margin-bottom:16px'});
renderTemplateSection(tmplBox);
pg.appendChild(tmplBox);

// Suggest Config
pg.append(h('h3',{style:'margin-top:24px;margin-bottom:12px'},'智能推荐'));
const cfgBox = h('div',{className:'card',style:'margin-bottom:16px'});
renderSuggestConfig(cfgBox);
pg.appendChild(cfgBox);
```

- [ ] **Step 2: Add template section rendering and API**

```javascript
function renderTemplateSection(box) {
  box.innerHTML = '';
  const list = h('div',{style:'max-height:250px;overflow:auto'});
  
  const addRow = h('div',{className:'field-row'});
  ['label','amount'].forEach(k => {
    addRow.appendChild(h('input',{type:'text',placeholder:k==='label'?'标签(如:早餐)':'金额',
      value:S['tmpl_'+k]||'',oninput:e=>S['tmpl_'+k]=e.target.value,style:'width:'+(k==='label'?'100px':'70px')}));
  });
  const catSel = h('select',{onchange:e=>{S.tmpl_catId=e.target.value;S.tmpl_catName=e.target.selectedOptions[0].text}});
  catSel.appendChild(h('option',{value:''},'分类'));
  S.cats.forEach(c => catSel.appendChild(h('option',{value:c.id},c.name)));
  addRow.appendChild(catSel);
  
  const acctSel = h('select',{onchange:e=>S.tmpl_acctId=e.target.value});
  acctSel.appendChild(h('option',{value:''},'账户'));
  S.accts.forEach(a => acctSel.appendChild(h('option',{value:a.id},a.name)));
  addRow.appendChild(acctSel);
  
  addRow.appendChild(h('button',{className:'btn btn-primary',style:'font-size:12px;padding:6px 12px',
    onclick:async()=>{
      await api('/api/templates',{method:'POST',body:JSON.stringify({
        label:S.tmpl_label||'',amount:parseFloat(S.tmpl_amount||'0'),categoryId:parseInt(S.tmpl_catId||'0'),
        categoryName:S.tmpl_catName||'',accountId:parseInt(S.tmpl_acctId||'0'),sortOrder:0
      })});
      S.msg='模板已保存';renderTemplateSection(box);
    }
  },'添加'));
  box.appendChild(addRow);

  // Load and render existing templates
  (async()=>{
    try {
      const tms = await api('/api/templates');
      tms.forEach(t => {
        const row = h('div',{className:'field-row'});
        row.appendChild(h('span',{style:'flex:1'},t.label+' ¥'+t.amount+' → '+t.categoryName));
        row.appendChild(h('button',{className:'btn btn-outline',style:'padding:2px 8px;font-size:11px',
          onclick:async()=>{await api('/api/templates/'+t.id,{method:'DELETE'});renderTemplateSection(box);}
        },'删除'));
        list.appendChild(row);
      });
    } catch(e) {}
  })();
  box.appendChild(list);
}

function renderSuggestConfig(box) {
  box.innerHTML = '';
  const loadCfg = async () => {
    try {
      const c = await api('/api/insights/suggest-config');
      if (c && c.enabled !== undefined) {
        box.innerHTML = '';
        const toggle = h('label',{style:'display:flex;align-items:center;gap:8px'});
        toggle.appendChild(h('input',{type:'checkbox',checked:c.enabled==='true'||c.enabled===true,
          onchange:async e=>{c.enabled=e.target.checked;await saveCfg(c);}}));
        toggle.appendChild(h('span',null,'启用智能推荐'));
        box.appendChild(toggle);
        
        ['lookbackDays','minOccurrences','displayDuration'].forEach(k => {
          const row = h('div',{className:'field-row',style:'margin-top:8px'});
          row.appendChild(h('label',{style:'minWidth:120px'},k));
          row.appendChild(h('input',{type:'number',value:c[k]||'',style:'width:80px',
            onchange:e=>{c[k]=parseInt(e.target.value);saveCfg(c);}}));
          box.appendChild(row);
        });
      }
    } catch(e) {}
  };
  const saveCfg = async(cfg) => await api('/api/insights/suggest-config',{method:'PUT',body:JSON.stringify(cfg)});
  loadCfg();
}
```

- [ ] **Step 2: Commit**

```bash
git add web/
git commit -m "feat: add template management and suggest config to Web settings"
```

---

### Task 8: Deploy and end-to-end test

- [ ] **Step 1: Build server and deploy**

```bash
./gradlew :server:installDist
tar -czf /tmp/rinklnote-server.tar.gz -C server/build/install server/
scp /tmp/rinklnote-server.tar.gz jmbot:/tmp/
scp web/index.html jmbot:/tmp/web-index.html
ssh jmbot "sudo systemctl stop rinklnote && cd /home/admin && rm -rf server.old && mv server server.old && tar -xzf /tmp/rinklnote-server.tar.gz && chown -R admin:admin server/ && cp /tmp/web-index.html /var/www/rinklnote/index.html && systemctl start rinklnote"
```

- [ ] **Step 2: Verify endpoints**

```bash
# Template CRUD
curl -s http://118.31.184.221/api/templates -H "Authorization: Bearer $TOKEN"
curl -s -X POST http://118.31.184.221/api/templates -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"label":"早餐","amount":15,"categoryId":1,"categoryName":"三餐","accountId":1,"sortOrder":0}'

# Parse
curl -s -X POST http://118.31.184.221/api/bills/parse -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{"text":"午餐25元"}'

# Suggest
curl -s http://118.31.184.221/api/insights/suggest -H "Authorization: Bearer $TOKEN"
```

- [ ] **Step 3: Build Android APK and test on device**

```bash
./gradlew assembleDebug
# Install and test: open drawer → see template row → type "午餐30元" → see parse result → check suggestion
```

- [ ] **Step 4: Commit final state**

```bash
git commit -am "chore: deploy and verify quickadd efficiency features"
```
