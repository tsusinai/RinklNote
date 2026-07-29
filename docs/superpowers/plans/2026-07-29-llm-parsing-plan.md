# LLM 智能解析系统 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 RinklNote Ktor 后端实现 DeepSeek 驱动的三层 LLM 智能解析系统（解析链 → 学习环 → 消费洞察），加 Web 端关键词管理和账单编辑 UI。

**Architecture:** NLUService 接口统一解析入口，RuleBasedParser（用户+系统关键词）→ LLMParser（DeepSeek 兜底）链式匹配。LearningService 定时批量从 correction_log 学习关键词。InsightService 独立提供月度总结/异常检测/自然语言查询三个功能。

**Tech Stack:** Kotlin 2.0.21, Ktor 2.3.13, Exposed 0.51.1, kotlinx-serialization-json 1.7.3, DeepSeek API (OpenAI 兼容), Ktor Client CIO

## Global Constraints

- JVM target 17, 所有新文件包路径 `com.example.rinklnote.server.*`
- 数据库表用 Exposed DSL，启动时 `SchemaUtils.create()` 自动建表
- API 端点 JWT 认证使用现有 `auth-jwt` provider
- 配置通过环境变量 + `application.conf` 兜底，遵循已有 `System.getenv() ?: environment.config.propertyOrNull()` 模式
- 日志用 `application.environment.log`，遵循已有 Ktor 日志模式
- 所有 API 返回 JSON，请求/响应用 `@Serializable` data class

---

### Task 1: 添加 Ktor Client 依赖

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `server/build.gradle.kts`

**Interfaces:**
- Produces: `libs.ktor.client.core`, `libs.ktor.client.cio`, `libs.ktor.client.content.negotiation` 可用

- [ ] **Step 1: 在 libs.versions.toml 添加 client 库声明**

在 `libs.versions.toml` 的 `[libraries]` 块末尾 (line 89 `datastore-preferences` 之后) 添加：

```toml
# Server - Ktor Client (for LLM API calls)
ktor-client-core = { group = "io.ktor", name = "ktor-client-core", version.ref = "ktor" }
ktor-client-cio = { group = "io.ktor", name = "ktor-client-cio", version.ref = "ktor" }
ktor-client-content-negotiation = { group = "io.ktor", name = "ktor-client-content-negotiation", version.ref = "ktor" }
```

- [ ] **Step 2: 在 server/build.gradle.kts 添加依赖**

在 `server/build.gradle.kts` 的 `dependencies` 块中，`implementation(libs.kotlinx.serialization.json)` 之前添加：

```kotlin
    // Ktor Client (for LLM API calls)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
```

- [ ] **Step 3: 验证依赖解析**

```bash
./gradlew server:dependencies --configuration runtimeClasspath | grep ktor-client
```

Expected: 看到 `ktor-client-core`、`ktor-client-cio`、`ktor-client-content-negotiation` 三个依赖。

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml server/build.gradle.kts
git commit -m "build: add Ktor Client CIO dependencies for LLM API calls"
```

---

### Task 2: CorrectionLogTable 数据库表

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/tables/CorrectionLogTable.kt`

**Interfaces:**
- Produces: `CorrectionLogTable` object，可用 `CorrectionLogTable.selectAll()`, `.insert { }`, `.update { }` 操作

- [ ] **Step 1: 创建 CorrectionLogTable**

```kotlin
package com.example.rinklnote.server.tables

import org.jetbrains.exposed.sql.Table

object CorrectionLogTable : Table("correction_log") {
    val id = long("id").autoIncrement()
    val userId = long("user_id").references(UsersTable.id)
    val originalText = varchar("original_text", 500)
    val originalCategory = varchar("original_category", 50)
    val correctedCategory = varchar("corrected_category", 50)
    val processed = bool("processed").default(false)
    val correctedAt = varchar("corrected_at", 30)

    override val primaryKey = PrimaryKey(id)
}
```

- [ ] **Step 2: 在 Database.kt 注册新表**

在 `server/src/main/kotlin/com/example/rinklnote/server/plugins/Database.kt` 修改 `SchemaUtils.create()` 调用 (line 32)：

```kotlin
// Before:
SchemaUtils.create(UsersTable, CategoriesTable, AccountsTable, BillsTable, VoiceKeywordsTable)

// After:
SchemaUtils.create(UsersTable, CategoriesTable, AccountsTable, BillsTable, VoiceKeywordsTable, CorrectionLogTable)
```

- [ ] **Step 3: 编译验证**

```bash
./gradlew :server:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/tables/CorrectionLogTable.kt server/src/main/kotlin/com/example/rinklnote/server/plugins/Database.kt
git commit -m "feat: add CorrectionLogTable for tracking user classification corrections"
```

---

### Task 3: NLU 数据类和接口定义

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/services/nlu/NLUService.kt`
- Create: `server/src/main/kotlin/com/example/rinklnote/server/services/nlu/NLUModels.kt`

**Interfaces:**
- Produces: `NLUService` 接口含 `suspend fun parse(text: String, userId: Long): VoiceResult`
- Produces: `LLMParseResult` data class, `LLMParserConfig` data class

- [ ] **Step 1: 创建 NLUModels.kt**

```kotlin
package com.example.rinklnote.server.services.nlu

import kotlinx.serialization.Serializable

@Serializable
data class LLMParseResult(
    val categoryName: String? = null,
    val subCategoryName: String? = null,
    val remark: String? = null
)

data class LLMParserConfig(
    val apiKey: String,
    val baseUrl: String = "https://api.deepseek.com",
    val model: String = "deepseek-chat",
    val timeoutMs: Long = 10_000
)
```

- [ ] **Step 2: 创建 NLUService.kt 接口**

```kotlin
package com.example.rinklnote.server.services.nlu

import com.example.rinklnote.server.services.VoiceResult

interface NLUService {
    suspend fun parse(text: String, userId: Long): VoiceResult
}
```

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/nlu/
git commit -m "feat: define NLUService interface and NLU data models"
```

---

### Task 4: RuleBasedParser — 关键词规则解析

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/services/nlu/RuleBasedParser.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/services/VoiceParser.kt` (不破坏现有 API，提取系统关键词映射供 RuleBasedParser 复用)

**Interfaces:**
- Produces: `RuleBasedParser.parse(text: String, userKeywords: List<UserKeyword>): String?` 返回匹配的分类名或 null

- [ ] **Step 1: 创建 RuleBasedParser**

```kotlin
package com.example.rinklnote.server.services.nlu

import com.example.rinklnote.server.services.UserKeyword

class RuleBasedParser {
    companion object {
        val SYSTEM_KEYWORDS = mapOf(
            "餐" to "三餐", "饭" to "三餐", "吃" to "三餐", "早餐" to "三餐",
            "午餐" to "三餐", "晚餐" to "三餐", "零食" to "三餐",
            "日用" to "日用", "超市" to "日用", "百货" to "日用",
            "交通" to "交通", "打车" to "交通", "地铁" to "交通", "公交" to "交通", "加油" to "交通",
            "学习" to "学习", "书" to "学习", "教育" to "学习", "培训" to "学习",
            "运动" to "运动", "健身" to "运动",
            "娱乐" to "娱乐", "电影" to "娱乐", "游戏" to "娱乐", "旅游" to "娱乐",
            "购物" to "网购", "网购" to "网购", "淘宝" to "网购", "快递" to "网购",
            "工资" to "工资", "兼职" to "兼职", "理财" to "理财"
        )
    }

    /**
     * Try to match a category name from the input text.
     * User keywords (sorted by priority DESC) take precedence over system keywords.
     * Returns the matched category name, or null if nothing matched.
     */
    fun parse(text: String, userKeywords: List<UserKeyword>): String? {
        // Step 1: user custom keywords (already sorted by priority descending)
        for (uk in userKeywords) {
            if (text.contains(uk.keyword)) {
                return uk.categoryName
            }
        }

        // Step 2: system default keywords
        for ((keyword, category) in SYSTEM_KEYWORDS) {
            if (text.contains(keyword)) {
                return category
            }
        }

        return null
    }
}
```

- [ ] **Step 2: VoiceParser 保持不变（已有 parse 方法逻辑兼容）**

VoiceParser 已有 `parse(text, userKeywords)` 方法实现相同的两层匹配。RuleBasedParser 提取了纯关键词逻辑，VoiceParser 保持不变以保持向后兼容。后续 QQWebhook 改用 NLUService 后 VoiceParser 仍可用于其他场景。

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/nlu/RuleBasedParser.kt
git commit -m "feat: add RuleBasedParser for keyword-based category matching"
```

---

### Task 5: LLMParser — DeepSeek API 客户端

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/services/nlu/LLMParser.kt`

**Interfaces:**
- Consumes: `LLMParserConfig`, `LLMParseResult`
- Produces: `LLMParser.parse(text: String, categories: List<String>, extraContext: String?): LLMParseResult?`

- [ ] **Step 1: 创建 LLMParser**

```kotlin
package com.example.rinklnote.server.services.nlu

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class LLMParser(private val config: LLMParserConfig) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeoutMs
        }
    }

    @Serializable
    data class ChatRequest(
        val model: String,
        val messages: List<ChatMessage>,
        val response_format: ChatResponseFormat? = null
    )

    @Serializable
    data class ChatResponseFormat(val type: String)

    @Serializable
    data class ChatMessage(val role: String, val content: String)

    @Serializable
    data class ChatResponse(
        val choices: List<ChatChoice>? = null
    )

    @Serializable
    data class ChatChoice(
        val message: ChatMessage? = null
    )

    /**
     * Build a system prompt listing available categories and instruction.
     */
    private fun buildSystemPrompt(categories: List<String>): String {
        val catList = categories.joinToString("、")
        return """
你是一个记账分类助手。根据用户的输入文本，选择最合适的分类，并提取备注信息。

可用分类: $catList

规则:
1. 如果输入明确提到某个分类的关键词，选择该分类
2. 如果没有明确关键词，根据语义推断最合适的分类
3. 如果完全无法判断，选择"三餐"作为默认分类
4. 提取金额之外的描述性文字作为 remark（不要包含金额数字）
5. 如果输入中有类似子分类的信息（如"午餐"算三餐的子分类），填到 subCategoryName

你必须返回一个 JSON 对象:
{"categoryName": "分类名", "subCategoryName": "子分类名或null", "remark": "备注文字或null"}
""".trimIndent()
    }

    /**
     * Call DeepSeek API to parse the input text.
     * Returns null on failure (timeout, error, invalid response).
     */
    suspend fun parse(text: String, categories: List<String>, extraContext: String? = null): LLMParseResult? {
        val userContent = buildString {
            append("用户输入: \"$text\"")
            if (!extraContext.isNullOrBlank()) {
                append("\n$extraContext")
            }
        }

        val request = ChatRequest(
            model = config.model,
            messages = listOf(
                ChatMessage(role = "system", content = buildSystemPrompt(categories)),
                ChatMessage(role = "user", content = userContent)
            ),
            response_format = ChatResponseFormat("json_object")
        )

        return try {
            val response: ChatResponse = client.post("${config.baseUrl}/v1/chat/completions") {
                header("Authorization", "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()

            val content = response.choices?.firstOrNull()?.message?.content ?: return null
            val json = Json { ignoreUnknownKeys = true; isLenient = true }
            json.decodeFromString<LLMParseResult>(content)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * General-purpose chat method for insight/summary use cases.
     * Takes full system prompt and user message, returns JSON string response.
     */
    suspend fun chat(systemPrompt: String, userMessage: String): String? {
        val request = ChatRequest(
            model = config.model,
            messages = listOf(
                ChatMessage(role = "system", content = systemPrompt),
                ChatMessage(role = "user", content = userMessage)
            ),
            response_format = ChatResponseFormat("json_object")
        )

        return try {
            val response: ChatResponse = client.post("${config.baseUrl}/v1/chat/completions") {
                header("Authorization", "Bearer ${config.apiKey}")
                contentType(ContentType.Application.Json)
                setBody(request)
            }.body()

            response.choices?.firstOrNull()?.message?.content
        } catch (e: Exception) {
            null
        }
    }

    fun shutdown() {
        client.close()
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew :server:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/nlu/LLMParser.kt
git commit -m "feat: add LLMParser for DeepSeek API integration"
```

---

### Task 6: DefaultNLUService — 解析链编排

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/services/nlu/DefaultNLUService.kt`

**Interfaces:**
- Consumes: `RuleBasedParser`, `LLMParser`, `BillService` (for getCategories)
- Produces: `NLUService` 接口的完整实现

- [ ] **Step 1: 创建 DefaultNLUService**

```kotlin
package com.example.rinklnote.server.services.nlu

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.UserKeyword
import com.example.rinklnote.server.services.VoiceParser
import com.example.rinklnote.server.services.VoiceResult
import com.example.rinklnote.server.tables.VoiceKeywordsTable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

class DefaultNLUService(
    private val ruleBasedParser: RuleBasedParser,
    private val llmParser: LLMParser,
    private val billService: BillService
) : NLUService {

    override suspend fun parse(text: String, userId: Long): VoiceResult {
        // Step 1: extract amount (reuse VoiceParser's regex)
        val amount = extractAmount(text)

        // Step 2: lookup user custom keywords from DB
        val userKeywords = getUserKeywords(userId)

        // Step 3: try rule-based match (user keywords → system keywords)
        val categoryName = ruleBasedParser.parse(text, userKeywords)

        if (categoryName != null) {
            return VoiceResult(amount = amount, categoryName = categoryName, remark = text)
        }

        // Step 4: LLM fallback
        val categories = transaction {
            billService.getCategories().map { it.name }
        }
        val llmResult = llmParser.parse(text, categories)

        if (llmResult != null && llmResult.categoryName != null) {
            // Auto-save learned keyword (priority=5, lower than manual default 10)
            saveLearnedKeyword(userId, text, llmResult.categoryName)

            val remark = llmResult.remark ?: text
            return VoiceResult(
                amount = amount,
                categoryName = llmResult.categoryName,
                remark = remark
            )
        }

        // Ultimate fallback: return without category
        return VoiceResult(amount = amount, categoryName = null, remark = text)
    }

    private fun extractAmount(text: String): Double? {
        val pattern = Regex("""(\d+\.?\d*)\s*[元块]?""")
        return pattern.find(text)?.groupValues?.get(1)?.toDoubleOrNull()
    }

    private fun getUserKeywords(userId: Long): List<UserKeyword> {
        return transaction {
            VoiceKeywordsTable.selectAll()
                .where { VoiceKeywordsTable.userId eq userId }
                .orderBy(VoiceKeywordsTable.priority, SortOrder.DESC)
                .map {
                    UserKeyword(
                        keyword = it[VoiceKeywordsTable.keyword],
                        categoryName = it[VoiceKeywordsTable.categoryName]
                    )
                }
        }
    }

    private fun saveLearnedKeyword(userId: Long, text: String, categoryName: String) {
        // Extract a keyword fragment from text (take first 2-6 chars as keyword)
        val keyword = text.take(6).trim()
        if (keyword.length < 1) return

        transaction {
            val existing = VoiceKeywordsTable.selectAll()
                .where {
                    (VoiceKeywordsTable.userId eq userId) and
                    (VoiceKeywordsTable.keyword eq keyword)
                }.singleOrNull()

            if (existing == null) {
                VoiceKeywordsTable.insert {
                    it[VoiceKeywordsTable.userId] = userId
                    it[VoiceKeywordsTable.keyword] = keyword
                    it[VoiceKeywordsTable.categoryName] = categoryName
                    it[VoiceKeywordsTable.priority] = 5
                    it[VoiceKeywordsTable.createdAt] = LocalDateTime.now().toString()
                }
            }
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew :server:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/nlu/DefaultNLUService.kt
git commit -m "feat: add DefaultNLUService orchestrating parse chain with LLM fallback"
```

---

### Task 7: application.conf — 添加 LLM 配置项

**Files:**
- Modify: `server/src/main/resources/application.conf`

- [ ] **Step 1: 在 application.conf 末尾追加 LLM 和 Learning 配置**

```hocon
deepseek {
    apiKey = ""
    apiKey = ${?DEEPSEEK_API_KEY}
    baseUrl = "https://api.deepseek.com"
    baseUrl = ${?DEEPSEEK_BASE_URL}
    model = "deepseek-chat"
    model = ${?DEEPSEEK_MODEL}
    timeoutMs = 10000
    timeoutMs = ${?LLM_TIMEOUT_MS}
}

learning {
    intervalMin = 60
    intervalMin = ${?LEARNING_INTERVAL_MIN}
}

anomaly {
    threshold = 1.5
    threshold = ${?ANOMALY_THRESHOLD}
}
```

- [ ] **Step 2: Commit**

```bash
git add server/src/main/resources/application.conf
git commit -m "config: add DeepSeek, learning, and anomaly settings to application.conf"
```

---

### Task 8: KeywordRoutes — 用户自定义关键词 API

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/routes/KeywordRoutes.kt`

**Interfaces:**
- Consumes: JWT `auth-jwt` provider
- Produces: `GET /api/keywords`, `POST /api/keywords`, `DELETE /api/keywords/{id}`

- [ ] **Step 1: 创建 KeywordRoutes**

```kotlin
package com.example.rinklnote.server.routes

import com.example.rinklnote.server.tables.VoiceKeywordsTable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

@Serializable
data class KeywordRequest(
    val keyword: String,
    val categoryName: String,
    val priority: Int = 10
)

@Serializable
data class KeywordDTO(
    val id: Long,
    val keyword: String,
    val categoryName: String,
    val priority: Int
)

fun Route.keywordRoutes() {
    authenticate("auth-jwt") {
        route("/api/keywords") {
            get {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val keywords = transaction {
                    VoiceKeywordsTable.selectAll()
                        .where { VoiceKeywordsTable.userId eq userId }
                        .orderBy(VoiceKeywordsTable.priority, SortOrder.DESC)
                        .map {
                            KeywordDTO(
                                id = it[VoiceKeywordsTable.id],
                                keyword = it[VoiceKeywordsTable.keyword],
                                categoryName = it[VoiceKeywordsTable.categoryName],
                                priority = it[VoiceKeywordsTable.priority]
                            )
                        }
                }
                call.respond(keywords)
            }

            post {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val body = call.receive<KeywordRequest>()
                if (body.keyword.isBlank() || body.categoryName.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "关键词和分类不能为空"))
                    return@post
                }

                val id = transaction {
                    // Skip duplicate
                    val exists = VoiceKeywordsTable.selectAll()
                        .where {
                            (VoiceKeywordsTable.userId eq userId) and
                            (VoiceKeywordsTable.keyword eq body.keyword)
                        }.singleOrNull()
                    if (exists != null) {
                        VoiceKeywordsTable.update({ VoiceKeywordsTable.id eq exists[VoiceKeywordsTable.id] }) {
                            it[categoryName] = body.categoryName
                            it[priority] = body.priority
                        }
                        exists[VoiceKeywordsTable.id]
                    } else {
                        VoiceKeywordsTable.insert {
                            it[VoiceKeywordsTable.userId] = userId
                            it[VoiceKeywordsTable.keyword] = body.keyword
                            it[VoiceKeywordsTable.categoryName] = body.categoryName
                            it[VoiceKeywordsTable.priority] = body.priority
                            it[VoiceKeywordsTable.createdAt] = LocalDateTime.now().toString()
                        } get VoiceKeywordsTable.id
                    }
                }
                call.respond(HttpStatusCode.Created, mapOf("id" to id))
            }

            delete("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized)

                val keywordId = call.parameters["id"]?.toLongOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest)

                val deleted = transaction {
                    VoiceKeywordsTable.deleteWhere {
                        (VoiceKeywordsTable.id eq keywordId) and (VoiceKeywordsTable.userId eq userId)
                    }
                }
                if (deleted > 0) {
                    call.respond(mapOf("message" to "删除成功"))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "关键词不存在"))
                }
            }
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew :server:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/routes/KeywordRoutes.kt
git commit -m "feat: add keyword CRUD API endpoints"
```

---

### Task 9: CorrectionRoutes + PUT /api/bills/:id

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/routes/CorrectionRoutes.kt`
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/routes/BillRoutes.kt` (添加 PUT /api/bills/{id} 端点)

**Interfaces:**
- Produces: `POST /api/corrections`, `PUT /api/bills/{id}`

- [ ] **Step 1: 创建 CorrectionRoutes**

```kotlin
package com.example.rinklnote.server.routes

import com.example.rinklnote.server.tables.CorrectionLogTable
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

@Serializable
data class CorrectionRequest(
    val originalText: String,
    val originalCategory: String,
    val correctedCategory: String
)

fun Route.correctionRoutes() {
    authenticate("auth-jwt") {
        route("/api/corrections") {
            post {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val body = call.receive<CorrectionRequest>()
                if (body.originalText.isBlank() || body.correctedCategory.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "参数不能为空"))
                    return@post
                }

                transaction {
                    CorrectionLogTable.insert {
                        it[CorrectionLogTable.userId] = userId
                        it[originalText] = body.originalText
                        it[originalCategory] = body.originalCategory
                        it[correctedCategory] = body.correctedCategory
                        it[processed] = false
                        it[correctedAt] = LocalDateTime.now().toString()
                    }
                }
                call.respond(HttpStatusCode.Created, mapOf("message" to "修正已记录"))
            }
        }
    }
}
```

- [ ] **Step 2: 在 BillRoutes.kt 添加 PUT /api/bills/{id} 端点**

在 `billRoutes` 函数的 `authenticate("auth-jwt")` 块内，`route("/api/bills")` 中，`get("/accounts")` 之后添加：

```kotlin
            put("/{id}") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@put call.respond(HttpStatusCode.Unauthorized)

                val billId = call.parameters["id"]?.toLongOrNull()
                    ?: return@put call.respond(HttpStatusCode.BadRequest)

                val body = call.receive<CreateBillRequest>()

                val updated = transaction {
                    val row = BillsTable.selectAll()
                        .where { (BillsTable.id eq billId) and (BillsTable.userId eq userId) }
                        .singleOrNull()
                        ?: return@transaction null

                    BillsTable.update({ BillsTable.id eq billId }) {
                        it[BillsTable.amount] = body.amount
                        it[BillsTable.billType] = body.billType
                        it[BillsTable.categoryId] = body.categoryId
                        it[BillsTable.categoryName] = body.categoryName
                        it[BillsTable.subCategoryName] = body.subCategoryName
                        it[BillsTable.accountId] = body.accountId
                        it[BillsTable.remark] = body.remark
                    }

                    BillDTO(
                        id = billId,
                        amount = body.amount,
                        billType = body.billType,
                        categoryId = body.categoryId,
                        categoryName = body.categoryName,
                        subCategoryName = body.subCategoryName,
                        accountId = body.accountId,
                        remark = body.remark,
                        date = row[BillsTable.date],
                        source = row[BillsTable.billSource],
                        createdAt = row[BillsTable.createdAt]
                    )
                }

                if (updated != null) {
                    call.respond(updated)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("message" to "账单不存在"))
                }
            }
```

注意：需要在 `BillRoutes.kt` 文件顶部导入 `com.example.rinklnote.server.tables.BillsTable` 和 `org.jetbrains.exposed.sql.update`，以及 `com.example.rinklnote.server.services.BillDTO`（如已有导入则跳过）。

- [ ] **Step 3: 编译验证**

```bash
./gradlew :server:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/routes/CorrectionRoutes.kt server/src/main/kotlin/com/example/rinklnote/server/routes/BillRoutes.kt
git commit -m "feat: add correction log API and bill edit endpoint"
```

---

### Task 10: 更新 QQWebhookRoutes 使用 NLUService

**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/routes/QQWebhookRoutes.kt`

**Interfaces:**
- Consumes: `NLUService` (替代直接调用 `VoiceParser`)

- [ ] **Step 1: 修改 QQWebhookRoutes 函数签名和调用逻辑**

将 `qqWebhookRoutes` 函数的参数从 `(webhookSecret, userService, billService)` 改为 `(webhookSecret, userService, billService, nluService)`。

修改文件中的以下内容：

**Import 部分** — 将 `import com.example.rinklnote.server.services.VoiceParser` 替换为 `import com.example.rinklnote.server.services.nlu.NLUService`

**函数签名** (line 19) — 改为：
```kotlin
fun Route.qqWebhookRoutes(
    webhookSecret: String,
    userService: UserService,
    billService: BillService,
    nluService: NLUService
) {
```

**解析调用** (line 58, `val result = VoiceParser.parse(text)`) — 改为：
```kotlin
                val result = nluService.parse(text, user.id)
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew :server:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/routes/QQWebhookRoutes.kt
git commit -m "refactor: switch QQWebhookRoutes to use NLUService instead of VoiceParser directly"
```

---

### Task 11: LearningService — 批量学习定时任务

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/services/nlu/LearningService.kt`

**Interfaces:**
- Consumes: `LLMParser`, learning interval config
- Produces: `LearningService.start(scope: CoroutineScope)` 

- [ ] **Step 1: 创建 LearningService**

```kotlin
package com.example.rinklnote.server.services.nlu

import com.example.rinklnote.server.tables.CorrectionLogTable
import com.example.rinklnote.server.tables.VoiceKeywordsTable
import io.ktor.server.application.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDateTime

class LearningService(
    private val llmParser: LLMParser,
    private val intervalMinutes: Long = 60,
    private val log: io.ktor.util.logging.Logger
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(intervalMinutes * 60 * 1000)
                try {
                    processCorrections()
                } catch (e: Exception) {
                    log.warn("LearningService processCorrections failed: ${e.message}")
                }
            }
        }
    }

    private suspend fun processCorrections() {
        val unprocessed = transaction {
            CorrectionLogTable.selectAll()
                .where { CorrectionLogTable.processed eq false }
                .orderBy(CorrectionLogTable.id, SortOrder.ASC)
                .toList()
        }

        if (unprocessed.isEmpty()) return

        // Group by user
        val byUser = unprocessed.groupBy { it[CorrectionLogTable.userId] }

        for ((userId, records) in byUser) {
            val corrections = records.map {
                "'${it[CorrectionLogTable.originalText]}': ${it[CorrectionLogTable.originalCategory]} → ${it[CorrectionLogTable.correctedCategory]}"
            }

            val context = """
根据以下用户修正记录，提炼出关键词→分类的映射规则。

修正记录:
${corrections.joinToString("\n")}

请返回 JSON 格式的关键词规则列表:
{"rules": [{"keyword": "关键词", "categoryName": "分类名"}, ...]}

注意:
1. 关键词应是用户输入文本中能唯一标识分类的词语（2-4字）
2. 不要为输入文本中没有的词语创建关键词
3. 如果修正记录不足以提炼可靠规则，返回空列表
""".trimIndent()

            val categories = transaction {
                com.example.rinklnote.server.tables.CategoriesTable.selectAll().map { it[com.example.rinklnote.server.tables.CategoriesTable.name] }
            }

            val result = llmParser.chat(
                "你是一个关键词规则提炼助手。对每条修正记录，提取关键词→分类的映射。你必须返回 JSON: {\"rules\": [{\"keyword\": \"词\", \"categoryName\": \"分类\"}]}",
                context
            )

            // Parse rules from LLM response and save
            if (result != null) {
                saveRules(userId, records, result)
            }
        }
    }

    private fun saveRules(
        userId: Long,
        records: List<ResultRow>,
        jsonResponse: String
    ) {
        transaction {
            // Try to parse LLM-generated rules
            data class RuleEntry(val keyword: String, val categoryName: String)
            data class RulesResponse(val rules: List<RuleEntry>)

            val rules = try {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                json.decodeFromString<RulesResponse>(jsonResponse).rules
            } catch (_: Exception) { emptyList() }

            if (rules.isNotEmpty()) {
                for (rule in rules) {
                    if (rule.keyword.isBlank()) continue
                    val exists = VoiceKeywordsTable.selectAll()
                        .where {
                            (VoiceKeywordsTable.userId eq userId) and
                            (VoiceKeywordsTable.keyword eq rule.keyword)
                        }.singleOrNull()

                    if (exists != null) {
                        VoiceKeywordsTable.update({ VoiceKeywordsTable.id eq exists[VoiceKeywordsTable.id] }) {
                            it[categoryName] = rule.categoryName
                            it[priority] = 8
                        }
                    } else {
                        VoiceKeywordsTable.insert {
                            it[VoiceKeywordsTable.userId] = userId
                            it[VoiceKeywordsTable.keyword] = rule.keyword
                            it[VoiceKeywordsTable.categoryName] = rule.categoryName
                            it[VoiceKeywordsTable.priority] = 8
                            it[VoiceKeywordsTable.createdAt] = LocalDateTime.now().toString()
                        }
                    }
                }
            } else {
                // Fallback: extract keyword from each correction record text
                for (record in records) {
                    val text = record[CorrectionLogTable.originalText]
                    val correctedCat = record[CorrectionLogTable.correctedCategory]
                    val keyword = text.take(6).trim()
                    if (keyword.length < 1) continue

                    val exists = VoiceKeywordsTable.selectAll()
                        .where {
                            (VoiceKeywordsTable.userId eq userId) and
                            (VoiceKeywordsTable.keyword eq keyword)
                        }.singleOrNull()

                    if (exists != null) {
                        VoiceKeywordsTable.update({ VoiceKeywordsTable.id eq exists[VoiceKeywordsTable.id] }) {
                            it[categoryName] = correctedCat
                            it[priority] = 8
                        }
                    } else {
                        VoiceKeywordsTable.insert {
                            it[VoiceKeywordsTable.userId] = userId
                            it[VoiceKeywordsTable.keyword] = keyword
                            it[VoiceKeywordsTable.categoryName] = correctedCat
                            it[VoiceKeywordsTable.priority] = 8
                            it[VoiceKeywordsTable.createdAt] = LocalDateTime.now().toString()
                        }
                    }
                }
            }

            // Mark all records as processed
            val recordIds = records.map { it[CorrectionLogTable.id] }
            CorrectionLogTable.update({ CorrectionLogTable.id inList recordIds }) {
                it[processed] = true
            }
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew :server:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/nlu/LearningService.kt
git commit -m "feat: add LearningService for batch keyword learning from corrections"
```

---

### Task 12: InsightService — 消费洞察服务

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/services/insight/InsightService.kt`

**Interfaces:**
- Consumes: `LLMParser`, `BillService`
- Produces: `monthlySummary()`, `anomalyCheck()`, `naturalQuery()` 三个方法

- [ ] **Step 1: 创建 InsightService**

```kotlin
package com.example.rinklnote.server.services.insight

import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.nlu.LLMParser
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Serializable
data class MonthlySummaryResponse(
    val summary: String,
    val highlights: List<String>
)

@Serializable
data class AnomalyResponse(
    val alerts: List<AnomalyAlert>
)

@Serializable
data class AnomalyAlert(
    val level: String,
    val message: String,
    val type: String
)

@Serializable
data class QueryResponse(
    val answer: String
)

class InsightService(
    private val llmParser: LLMParser,
    private val billService: BillService,
    private val anomalyThreshold: Double = 1.5
) {
    suspend fun monthlySummary(userId: Long, month: String): MonthlySummaryResponse {
        val bills = transaction { billService.syncBills(userId, null).bills }

        // Filter bills for the target month
        val yearMonth = month.split("-")
        val targetYear = yearMonth[0].toInt()
        val targetMonth = yearMonth[1].toInt()

        val monthBills = bills.filter {
            val d = LocalDate.ofInstant(java.time.Instant.ofEpochMilli(it.date), ZoneId.of("Asia/Shanghai"))
            d.year == targetYear && d.monthValue == targetMonth
        }

        val totalExpense = monthBills.filter { it.billType == "EXPENSE" }.sumOf { it.amount }
        val totalIncome = monthBills.filter { it.billType == "INCOME" }.sumOf { it.amount }
        val byCategory = monthBills.filter { it.billType == "EXPENSE" }
            .groupBy { it.categoryName }
            .mapValues { it.value.sumOf { b -> b.amount } }
            .entries
            .sortedByDescending { it.value }
            .take(5)

        val context = """
账单数据 (${month}):
- 总支出: ¥${"%.2f".format(totalExpense)}
- 总收入: ¥${"%.2f".format(totalIncome)}
- 支出分类TOP5: ${byCategory.joinToString { "${it.key} ¥${"%.2f".format(it.value)}" }}

请你用中文写一段简洁的月度消费总结（80-150字），并列出2-3个值得关注的点(highlights)。

返回JSON: {"summary": "总结文字", "highlights": ["亮点1", "亮点2"]}
""".trimIndent()

        try {
            val jsonStr = llmParser.chat(
                "你是一个个人财务分析助手，用中文回答。你必须返回一个 JSON 对象。",
                context
            )
            if (jsonStr != null) {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                val parsed = json.decodeFromString<MonthlySummaryResponse>(jsonStr)
                return parsed
            }
        } catch (_: Exception) {}

        return MonthlySummaryResponse(
            summary = "${month} 总支出 ¥${"%.2f".format(totalExpense)}，收入 ¥${"%.2f".format(totalIncome)}",
            highlights = emptyList()
        )
    }

    suspend fun anomalyCheck(userId: Long): AnomalyResponse {
        val bills = transaction { billService.syncBills(userId, null).bills }
        val now = System.currentTimeMillis()
        val thirtyDaysAgo = now - 30L * 24 * 60 * 60 * 1000

        val recentExpenses = bills.filter { it.billType == "EXPENSE" && it.date >= thirtyDaysAgo }
        val dailyAvg = recentExpenses.sumOf { it.amount } / 30.0

        val todayStart = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val todayExpense = bills.filter { it.billType == "EXPENSE" && it.date >= todayStart }
            .sumOf { it.amount }

        val alerts = mutableListOf<AnomalyAlert>()

        if (dailyAvg > 0 && todayExpense > dailyAvg * anomalyThreshold) {
            val pct = ((todayExpense / dailyAvg - 1) * 100).toInt()
            alerts.add(AnomalyAlert(
                level = "WARN",
                message = "今天餐饮支出 ¥${"%.2f".format(todayExpense)}，超出日均 ¥${"%.2f".format(dailyAvg)} 的 $pct%",
                type = "DAILY_SPIKE"
            ))
        }

        return AnomalyResponse(alerts = alerts)
    }

    suspend fun naturalQuery(userId: Long, query: String): QueryResponse {
        val bills = transaction { billService.syncBills(userId, null).bills }
        val categories = transaction { billService.getCategories().map { it.name } }

        // Brief summary for LLM context
        val now = System.currentTimeMillis()
        val monthStart = LocalDate.now(ZoneId.of("Asia/Shanghai"))
            .withDayOfMonth(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()
        val monthBills = bills.filter { it.date >= monthStart }
        val totalExpense = monthBills.filter { it.billType == "EXPENSE" }.sumOf { it.amount }

        val context = """
用户问题: "$query"

当前可用分类: ${categories.joinToString("、")}
当月总支出: ¥${"%.2f".format(totalExpense)}

请根据问题类型返回查询参数（你可以假设你有数据）:
1. 如果是查金额→返回 {"action":"total","filters":{"category":"分类名","timeRange":"本月/上月/本年"}}
2. 如果是问建议→直接回答
3. 否则→返回最合适的回答

返回JSON: {"answer": "你的回答"}
""".trimIndent()

        try {
            val jsonStr = llmParser.chat(
                "你是一个个人财务查询助手，用中文回答。你必须返回一个 JSON 对象: {\"answer\": \"你的回答\"}",
                context
            )
            if (jsonStr != null) {
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
                val parsed = json.decodeFromString<QueryResponse>(jsonStr)
                return parsed
            }
        } catch (_: Exception) {}

        return QueryResponse(answer = "抱歉，暂时无法理解这个问题。请尝试更具体的提问，如"上个月交通支出多少？"")
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew :server:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/insight/
git commit -m "feat: add InsightService for monthly summary, anomaly detection, and natural language query"
```

---

### Task 13: InsightRoutes — 洞察 API 端点

**Files:**
- Create: `server/src/main/kotlin/com/example/rinklnote/server/routes/InsightRoutes.kt`

**Interfaces:**
- Consumes: `InsightService`, JWT auth
- Produces: `GET /api/insights/monthly`, `GET /api/insights/anomaly`, `POST /api/insights/query`

- [ ] **Step 1: 创建 InsightRoutes**

```kotlin
package com.example.rinklnote.server.routes

import com.example.rinklnote.server.services.insight.InsightService
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
data class QueryRequest(val query: String)

fun Route.insightRoutes(insightService: InsightService) {
    authenticate("auth-jwt") {
        route("/api/insights") {
            get("/monthly") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val month = call.request.queryParameters["month"]
                    ?: java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))

                val result = insightService.monthlySummary(userId, month)
                call.respond(result)
            }

            get("/anomaly") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@get call.respond(HttpStatusCode.Unauthorized)

                val result = insightService.anomalyCheck(userId)
                call.respond(result)
            }

            post("/query") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asLong()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized)

                val body = call.receive<QueryRequest>()
                if (body.query.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("message" to "查询内容不能为空"))
                    return@post
                }

                val result = insightService.naturalQuery(userId, body.query)
                call.respond(result)
            }
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew :server:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/routes/InsightRoutes.kt
git commit -m "feat: add insight API endpoints (monthly/anomaly/query)"
```

---

### Task 14: Application.kt 集成 — 组装所有新组件

**Files:**
- Modify: `server/src/main/kotlin/com/example/rinklnote/server/Application.kt`

**Interfaces:**
- Consumes: All new services and routes
- Produces: 完整的 LLM 解析系统

- [ ] **Step 1: 更新 Application.kt 以配置和注入所有新组件**

```kotlin
package com.example.rinklnote.server

import com.example.rinklnote.server.plugins.*
import com.example.rinklnote.server.routes.*
import com.example.rinklnote.server.services.BillService
import com.example.rinklnote.server.services.UserService
import com.example.rinklnote.server.services.insight.InsightService
import com.example.rinklnote.server.services.nlu.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.routing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    val jwtSecret = System.getenv("JWT_SECRET")
        ?: environment.config.propertyOrNull("jwt.secret")?.getString()
        ?: throw IllegalStateException("JWT secret not configured. Set JWT_SECRET env var.")
    val jwtIssuer = System.getenv("JWT_ISSUER")
        ?: environment.config.propertyOrNull("jwt.issuer")?.getString()
        ?: "rinklnote-server"
    val jwtAudience = System.getenv("JWT_AUDIENCE")
        ?: environment.config.propertyOrNull("jwt.audience")?.getString()
        ?: "rinklnote-app"
    val webhookSecret = System.getenv("WEBHOOK_SECRET")
        ?: environment.config.propertyOrNull("webhook.secret")?.getString()
        ?: throw IllegalStateException("Webhook secret not configured. Set WEBHOOK_SECRET env var.")

    // DeepSeek config
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
        ?: 10_000

    // Learning config
    val learningIntervalMin = System.getenv("LEARNING_INTERVAL_MIN")?.toLongOrNull()
        ?: environment.config.propertyOrNull("learning.intervalMin")?.getString()?.toLongOrNull()
        ?: 60

    // Anomaly config
    val anomalyThreshold = System.getenv("ANOMALY_THRESHOLD")?.toDoubleOrNull()
        ?: environment.config.propertyOrNull("anomaly.threshold")?.getString()?.toDoubleOrNull()
        ?: 1.5

    val userService = UserService(jwtSecret, jwtIssuer, jwtAudience)
    val billService = BillService()

    // NLU components
    val llmConfig = LLMParserConfig(
        apiKey = deepseekApiKey,
        baseUrl = deepseekBaseUrl,
        model = deepseekModel,
        timeoutMs = llmTimeoutMs
    )
    val llmParser = LLMParser(llmConfig)
    val ruleBasedParser = RuleBasedParser()
    val nluService = DefaultNLUService(ruleBasedParser, llmParser, billService)

    // Learning service
    val learningService = LearningService(
        llmParser = llmParser,
        intervalMinutes = learningIntervalMin,
        log = log
    )
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    learningService.start(appScope)

    // Insight service
    val insightService = InsightService(
        llmParser = llmParser,
        billService = billService,
        anomalyThreshold = anomalyThreshold
    )

    routing {
        authRoutes(userService)
        billRoutes(billService)
        qqWebhookRoutes(webhookSecret, userService, billService, nluService)
        keywordRoutes()
        correctionRoutes()
        insightRoutes(insightService)
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
./gradlew :server:compileKotlin
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/Application.kt
git commit -m "feat: wire up NLU, Learning, and Insight services in Application.kt"
```

---

### Task 15: Web 前端 — 关键词管理和账单编辑 UI

**Files:**
- Modify: `web/index.html`

- [ ] **Step 1: 在设置页添加关键词管理面板**

在 `renderSettings` 函数中，已登录状态下的 "退出登录" 按钮之前添加关键词管理 UI：

```javascript
  // Keyword management (after QQ bind, before logout)
  pg.append(h('h3',{style:'margin-top:24px;margin-bottom:12px'},'关键词管理'));
  pg.append(h('p',{style:'fontSize:12px;color:var(--muted);marginBottom:8px'},'自定义关键词用于快速匹配分类。如"外卖"→"三餐"'));
  
  const kwGrid = h('div',{style:'display:flex;gap:8px;marginBottom:8px'});
  const kwInput = h('input',{type:'text',placeholder:'关键词(如:外卖)',style:'flex:1'});
  const kwCat = h('select');
  kwCat.append(h('option',{value:''},'选择分类'));
  S.cats.forEach(c => kwCat.append(h('option',{value:c.name},c.name)));
  kwGrid.appendChild(kwInput);
  kwGrid.appendChild(kwCat);
  kwGrid.appendChild(h('button',{className:'btn btn-primary',style:'flex-shrink:0',onclick:async()=>{
    if(!kwInput.value||!kwCat.value){S.msg='请填写完整';render();return;}
    const r=await api('/api/keywords',{method:'POST',body:JSON.stringify({keyword:kwInput.value,categoryName:kwCat.value})});
    S.msg=r.id?'关键词已保存':(r.message||'失败');
    render();
  }},'添加'));
  pg.appendChild(kwGrid);
  
  // List existing keywords
  const kwList = h('div',{style:'maxHeight:200px;overflow:auto'});
  (S.keywords||[]).forEach(kw=>{
    const row = h('div',{style:'display:flex;align-items:center;gap:8px;padding:6px 0;borderBottom:1px solid var(--border)'});
    row.append(h('span',{style:'flex:1;fontSize:13px'},kw.keyword+' → '+kw.categoryName));
    row.append(h('span',{style:'fontSize:11px;color:var(--muted)'},'优先级:'+kw.priority));
    row.append(h('button',{className:'btn btn-outline',style:'padding:4px 10px;fontSize:11px;color:var(--expense);borderColor:var(--expense)',onclick:async()=>{
      await api('/api/keywords/'+kw.id,{method:'DELETE'});
      await loadKeywords();
      render();
    }},'删除'));
    kwList.appendChild(row);
  });
  pg.appendChild(kwList);
```

- [ ] **Step 2: 在账单列表添加编辑分类功能**

在 `renderBills` 函数中的表格行 (`tr`) 里，分类列改为可点击编辑：

替换 `tr.append(h('td',null, b.categoryName));` 为：

```javascript
    const catCell = h('td',null);
    const catSpan = h('span',{style:'cursor:pointer;borderBottom:1px dashed var(--muted)',onclick:()=>{
      // Show quick category selector
      const origCat = b.categoryName;
      const sel = h('select',{style:'fontSize:12px;padding:4px',onchange:async(e)=>{
        if(e.target.value && e.target.value!==origCat){
          // Record correction
          await api('/api/corrections',{method:'POST',body:JSON.stringify({
            originalText:b.remark||'',originalCategory:origCat,correctedCategory:e.target.value
          })});
          // Update bill
          await api('/api/bills/'+b.id,{method:'PUT',body:JSON.stringify({
            amount:b.amount,billType:b.billType,categoryId:0,categoryName:e.target.value,
            accountId:b.accountId,remark:b.remark
          })});
          S.msg='分类已更新';loadData();render();
        }
      }});
      sel.append(h('option',{value:''},'修改分类'));
      S.cats.filter(c=>c.billType===b.billType).forEach(c=>{
        sel.append(h('option',{value:c.name,selected:c.name===b.categoryName},c.name));
      });
      catCell.innerHTML='';catCell.appendChild(sel);
    }}, b.categoryName));
    catCell.appendChild(catSpan);
    tr.appendChild(catCell);
```

- [ ] **Step 3: 添加 loadKeywords 函数和在 init/loadData 中调用**

在 Helpers 区域添加：

```javascript
async function loadKeywords() {
  if (!S.token) return;
  try {
    const r = await api('/api/keywords');
    if (Array.isArray(r)) S.keywords = r;
  } catch(e) { console.error(e); }
}
```

在 `loadData` 函数末尾添加 `await loadKeywords();`。

在 `S` 对象初始化中添加 `keywords: []`。

- [ ] **Step 4: Commit**

```bash
git add web/index.html
git commit -m "feat: add keyword management and bill category editing to web UI"
```

---

### Task 16: 服务器部署与验证

**说明**: 此任务通过 SSH 在服务器 `jmbot` 上操作。

- [ ] **Step 1: 打包服务端**

```bash
./gradlew :server:installDist
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 上传新版本到服务器**

```bash
scp -r server/build/install/server/* jmbot:/home/admin/server/
```

- [ ] **Step 3: 设置 DeepSeek API Key**

```bash
echo "DEEPSEEK_API_KEY=<your-key>" | ssh jmbot "cat >> /etc/rinklnote/env"
ssh jmbot "sudo systemctl restart rinklnote"
```

Expected: `sudo systemctl status rinklnote` 显示 active (running)

- [ ] **Step 4: 更新 Web 前端**

```bash
scp web/index.html jmbot:/var/www/rinklnote/index.html
```

- [ ] **Step 5: 验证 API 端点**

```bash
# Login
curl -s -X POST http://118.31.184.221/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800138000","password":"123456"}' | jq .

# Keywords CRUD (use token from above)
curl -s http://118.31.184.221/api/keywords \
  -H "Authorization: Bearer <token>" | jq .

# Insight endpoints
curl -s "http://118.31.184.221/api/insights/monthly?month=2026-07" \
  -H "Authorization: Bearer <token>" | jq .
```

Expected: 返回合理 JSON 响应（API key 未配置时 LLM 降级返回基础总结）

- [ ] **Step 6: Commit 部署配置并记录**

```bash
git add -A
git commit -m "deploy: LLM intelligent parsing system v1 with DeepSeek integration"
```

---

### 验证清单

所有任务完成后，确认以下：

- [ ] `./gradlew :server:compileKotlin` 通过无错误
- [ ] `./gradlew :server:installDist` 打包成功
- [ ] 服务端启动日志无异常
- [ ] `POST /api/keywords` 创建关键词成功
- [ ] `GET /api/keywords` 返回关键词列表
- [ ] `DELETE /api/keywords/{id}` 删除成功
- [ ] `POST /api/corrections` 记录修正成功
- [ ] `PUT /api/bills/{id}` 编辑账单成功
- [ ] `GET /api/insights/monthly` 返回月度总结（无 API key 也行，有降级）
- [ ] `GET /api/insights/anomaly` 返回异常检测结果
- [ ] `POST /api/insights/query` 自然语言查询工作正常
- [ ] QQ Webhook 解析走 NLUService（发一条 QQ 消息验证）
- [ ] Web 端关键词管理 UI 正确渲染
- [ ] Web 端账单分类点击编辑工作正常
