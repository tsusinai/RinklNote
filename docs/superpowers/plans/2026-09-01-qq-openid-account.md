# QQ openid 自动开户 + QQ 登录码 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 QQ 用户第一条消息即自动开通记账账号（无需 App/手机号前置），并可用「QQ 登录码」在 Web 登录同账号看到账单。

**Architecture:** 目前 `UsersTable.phone`/`passwordHash` 非空且 phone 唯一，纯 openid 账号无法存在；`QQMessageProcessor` 对未绑定用户只发 6 位绑定码、不自动开户。方案：① 建表字段改为可空，加生产库 `ALTER COLUMN ... SET NULL` 迁移；② `UserService.createByQqOpenid` 以 openid 开户；③ `QQMessageProcessor` 首消息自动开户并支持「登录码」指令；④ 复用 `QQBotService.generateBindCode/consumeBindCode`（已是 code→openid）做 Web 登录码兑换，新增 `POST /api/auth/qq-login`；⑤ Web 登录屏加「QQ 登录」输入框。

**Tech Stack:** Ktor + Exposed + H2/PostgreSQL（服务端）、Vanilla JS 单文件 SPA（web）、JUnit 4 + TestDatabase（测试）。

## Global Constraints

- 服务端测试命令：`export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test`（JDK 由 `D:/gradle/gradle.properties` 用 17；Windows 用户路径含撇号，必须先导出 `GRADLE_USER_HOME=D:/gradle`）。
- Exposed `createMissingTablesAndColumns` 只加表/加列，**不会**改已有列 nullability —— 生产 H2 文件库必须手动 `ALTER TABLE ... ALTER COLUMN ... SET NULL`。
- 提交时按路径显式暂存源码，不 `git add -A`（server/build 产物已被追踪会污染）。暂存 `server/src/...` 与 `web/index.html`，跳过 `server/build`、`.claude/settings.local.json`。
- 账号身份核心是 `qqOpenid`，openid 账号 `phone`/`passwordHash` 为 null；JWT 登录码/手机登录都走同一 `userId`。
- UI 文案用中文。

---

### Task 1: Server — UsersTable 支持 openid 账号（schema + 迁移 + UserService）

**Files:**
- Modify: `server/src/main/kotlin/.../tables/UsersTable.kt:6-9`
- Modify: `server/src/main/kotlin/.../plugins/Database.kt:44-66`（`runMigrations()`）
- Modify: `server/src/main/kotlin/.../services/UserService.kt:12-19,101-160`
- Test: `server/src/test/kotlin/.../services/UserServiceTest.kt`

**Interfaces:**
- Consumes: 无（Task 1 基础）。
- Produces:
  - `UserService.createByQqOpenid(openid: String): UserInfo` —— 以 openid 开户（phone/passwordHash=null），已存在则返回既有用户。
  - `UserService.generateToken(userId: Long, phone: String?): String`（由 private 改 public，phone 可为 null）。
  - `UserInfo.phone: String?`（改为可空）。

- [ ] **Step 1: 改建表定义 —— phone/passwordHash 可空**

`UsersTable.kt` 第 6-7 行：
```kotlin
val phone = varchar("phone", 20).nullable().uniqueIndex()
val passwordHash = varchar("password_hash", 255).nullable()
```
（`qqNumber`/`qqOpenid` 已是 nullable。H2 唯一索引允许多个 NULL，不影响既有非空手机号用户。）

- [ ] **Step 2: 生产库迁移 —— 手动 ALTER 列可空**

`Database.kt` 的 `runMigrations()` 末尾（现有 indexes 循环之后、`}` 之前）追加：
```kotlin
// v11 迁移：users.phone/password_hash 改为可空，以支持「QQ openid 自动开户」。
// createMissingTablesAndColumns 只加表/加列，不会改动已有列 nullability，需手动 ALTER。
listOf("ALTER TABLE users ALTER COLUMN phone SET NULL",
        "ALTER TABLE users ALTER COLUMN password_hash SET NULL").forEach { sql ->
    try { exec(sql) } catch (_: Exception) {}
}
```

- [ ] **Step 3: UserService —— phone 可空 + createByQqOpenid + generateToken 可空**

`UserService.kt`：
a. `UserInfo`（第 12-13 行）`phone: String` 改 `phone: String?`。
b. `generateToken`（第 152-160 行）改 private→public、`phone: String?`：
```kotlin
fun generateToken(userId: Long, phone: String?): String {
    val builder = JWT.create()
        .withAudience(jwtAudience)
        .withIssuer(jwtIssuer)
        .withClaim("userId", userId)
    if (phone != null) builder.withClaim("phone", phone)
    return builder
        .withExpiresAt(Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000))
        .sign(Algorithm.HMAC256(jwtSecret))
}
```
c. 新增 `createByQqOpenid`（放在 `findByQqOpenid` 之后）：
```kotlin
/** QQ openid 自动开户：phone/passwordHash 为空，身份即 openid。已存在则返回既有用户。 */
fun createByQqOpenid(openid: String): UserInfo {
    return findByQqOpenid(openid) ?: transaction {
        val userId = UsersTable.insert {
            it[UsersTable.qqOpenid] = openid
            it[createdAt] = LocalDateTime.now().toString()
        } get UsersTable.id
        UsersTable.selectAll().where { UsersTable.id eq userId }.singleOrNull()!!.toUserInfo()
    }
}
```
d. `register`/`login` 内部调用 `generateToken(userId, phone)`（phone 非空）不变；`generateToken` 签名变化，`login` 第 48 行 `generateToken(userId, phone)` 仍成立（phone 为 String）。

- [ ] **Step 4: 写失败测试**

`UserServiceTest.kt` 追加：
```kotlin
@Test
fun `createByQqOpenid creates openid account with null phone and is idempotent`() {
    val u1 = service.createByQqOpenid("openid-xxx")
    assertNotNull(u1)
    assertNull(u1.phone)
    assertEquals("openid-xxx", u1.qqOpenid)
    assertNotNull(service.findById(u1.id))
    assertNull(service.findById(u1.id)!!.phone)

    // 同一 openid 再调用返回同一账号，不重复建
    val u2 = service.createByQqOpenid("openid-xxx")
    assertEquals(u1.id, u2.id)
}

@Test
fun `openid account cannot be phone-logged-in and token works for null phone`() {
    val u = service.createByQqOpenid("openid-yyy")
    assertNull(service.login("", "whatever"))
    assertNull(service.findByPhone(""))

    // generateToken 接受 null phone，产出合法 JWT（三段）
    val token = service.generateToken(u.id, u.phone)
    assertEquals(3, token.split(".").size)
}
```

- [ ] **Step 5: 运行确认失败**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test --tests "com.example.rinklnote.server.services.UserServiceTest" -q`
Expected: 编译失败（`createByQqOpenid` 未定义 / `phone: String?` 赋值不匹配），直到 Step 3 全部改完——运行 `:server:test` 全绿。

- [ ] **Step 6: 运行确认通过**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test -q`
Expected: BUILD SUCCESSFUL，全部测试通过（新增 2 项）。

- [ ] **Step 7: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/tables/UsersTable.kt server/src/main/kotlin/com/example/rinklnote/server/plugins/Database.kt server/src/main/kotlin/com/example/rinklnote/server/services/UserService.kt server/src/test/kotlin/com/example/rinklnote/server/services/UserServiceTest.kt
git commit -m "feat(server): users.phone/password_hash 可空 + createByQqOpenid 开户基础"
```

---

### Task 2: Server — POST /api/auth/qq-login 登录码兑换 JWT

**Files:**
- Modify: `server/src/main/kotlin/.../routes/AuthRoutes.kt:29-37,45-94`（`MeResponse`、`authRoutes`、加 endpoint + `QqLoginRequest`）
- Modify: `server/src/main/kotlin/.../Application.kt:180`
- Test: `server/src/test/kotlin/.../services/QQBotServiceTest.kt`（新增，测 bind-code 往返）

**Interfaces:**
- Consumes: `UserService.createByQqOpenid`、`UserService.generateToken(userId, phone: String?)`、`QQBotService.consumeBindCode(code): String?`、`UserService.findByQqOpenid`。
- Produces: `POST /api/auth/qq-login`，body `{code}`，成功返回 `AuthResponse(userId, token)`；登录码无效→401「登录码无效或已过期」；openid 无账号→404「该QQ尚未开通账号，请先给机器人发消息」。
- `MeResponse.phone: String?`。

- [ ] **Step 1: 改 MeResponse.phone 可空**

`AuthRoutes.kt` 第 30-37 行 `MeResponse`：`phone: String` → `phone: String? = null`。

- [ ] **Step 2: 加 QqLoginRequest + qq-login endpoint**

`AuthRoutes.kt` 第 40 行后加：
```kotlin
@Serializable
data class QqLoginRequest(val code: String)
```
`authRoutes` 签名改为 `fun Route.authRoutes(userService: UserService, qqBotService: QQBotService)`，并在 `/login` 之后（`route("/api/auth")` 内、`authenticate("auth-jwt")` 之前）加：
```kotlin
post("/qq-login") {
    val ip = call.request.local.remoteHost
    if (loginLimiter.isBlocked(ip)) {
        call.respond(HttpStatusCode.TooManyRequests, MessageResponse("尝试次数过多，请稍后再试"))
        return@post
    }
    val body = call.receive<QqLoginRequest>()
    if (body.code.isBlank()) {
        call.respond(HttpStatusCode.BadRequest, MessageResponse("登录码不能为空"))
        return@post
    }
    val openid = qqBotService.consumeBindCode(body.code)
        ?: return@post call.respond(HttpStatusCode.Unauthorized, MessageResponse("登录码无效或已过期"))
    val user = userService.findByQqOpenid(openid)
        ?: return@post call.respond(HttpStatusCode.NotFound, MessageResponse("该QQ尚未开通账号，请先给机器人发消息"))
    loginLimiter.recordSuccess(ip)
    val token = userService.generateToken(user.id, user.phone)
    call.respond(AuthResponse(user.id, token))
}
```
已 import：`QqLoginRequest` 需要 `@Serializable`（文件已 import）。需加 import `com.example.rinklnote.server.services.QQBotService`。

- [ ] **Step 3: Application.kt 接线**

`Application.kt` 第 180 行 `authRoutes(userService)` → `authRoutes(userService, qqBotService)`。

- [ ] **Step 4: 写 bind-code 往返测试（QQBotServiceTest 新增）**

创建 `server/src/test/kotlin/.../services/QQBotServiceTest.kt`：
```kotlin
package com.example.rinklnote.server.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QQBotServiceTest {

    @Test
    fun `bind code roundtrip maps code back to openid`() {
        val svc = QQBotService()
        val code = svc.generateBindCode("openid-roundtrip")
        assertEquals("openid-roundtrip", svc.consumeBindCode(code))
    }

    @Test
    fun `consumed bind code cannot be reused`() {
        val svc = QQBotService()
        val code = svc.generateBindCode("openid-reuse")
        svc.consumeBindCode(code)
        assertNull(svc.consumeBindCode(code))
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test -q`
Expected: BUILD SUCCESSFUL（`createByQqOpenid`/`generateToken(null)` 兼容，新增 QQBotServiceTest 2 项通过）。

- [ ] **Step 6: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/routes/AuthRoutes.kt server/src/main/kotlin/com/example/rinklnote/server/Application.kt server/src/test/kotlin/com/example/rinklnote/server/services/QQBotServiceTest.kt
git commit -m "feat(server): POST /api/auth/qq-login 登录码兑换 JWT"
```

---

### Task 3: Web — 登录屏「QQ 登录」输入码

**Files:**
- Modify: `web/index.html:187,296-318`（S 状态 + `renderLogin`）

**Interfaces:**
- Consumes: `POST /api/auth/qq-login`（body `{code}` → `{userId, token}`）。
- Produces: 无对外，登录后设 `S.token` + `localStorage rkl_token` + `loadData()`。

- [ ] **Step 1: S 状态加 qqLoginCode**

`web/index.html` 第 187 行 `phone: '', pwd: '', qq: '', smode: 'login', bindCode: '',` 末尾加 `qqLoginCode: '',`。

- [ ] **Step 2: renderLogin 加 QQ 登录块**

`renderLogin`（第 297-318 行）在 `if (S.msg) ...` 之前、三个字段框之后追加：
```js
div.append(h('hr', { style:'margin:16px 0' }));
div.append(h('input', { type:'text', placeholder:'QQ 登录码（向机器人发「登录」获取）', value:S.qqLoginCode, oninput:e=>S.qqLoginCode=e.target.value, style:'margin-bottom:8px' }));
div.append(h('button', { className:'btn btn-primary', style:'width:100%', onclick: async () => {
  const r = await api('/api/auth/qq-login', { method:'POST', body:JSON.stringify({ code:S.qqLoginCode }) });
  if (r.token) { S.token = r.token; localStorage.setItem('rkl_token', r.token); S.msg = 'QQ登录成功!'; S.smode = 'login'; await loadData(); }
  else { S.msg = r.message || 'QQ登录失败'; render(); }
}}, 'QQ 登录'));
```

- [ ] **Step 3: /me 无 phone 时优雅展示**

若 `renderMe`（约第 863 行附近）展示 `S.me.phone`，当 `S.me.phone` 为 null 时显示「QQ账号」。搜索 `S.me.phone` 处，将其展示文本改为 `(S.me && S.me.phone) ? S.me.phone : 'QQ账号'`。

- [ ] **Step 4: 手动冒烟**

本地起 server（`export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:run` 或已部署实例），浏览器登录屏点「QQ 登录」输码，确认能拿到 token 并进入看账页。无码时输错误码应显示「登录码无效或已过期」。

- [ ] **Step 5: Commit**

```bash
git add web/index.html
git commit -m "feat(web): 登录屏支持 QQ 登录码登录"
```

---

### Task 4: Server — QQMessageProcessor 自动开户 + 「登录码」指令

**Files:**
- Modify: `server/src/main/kotlin/.../services/QQMessageProcessor.kt:75-94`（process 内绑定/开户逻辑）
- Test: `server/src/test/kotlin/.../services/QQIntentRouterTest.kt`（如涉及，不需改动——本任务主要是 process 的 openid 分支，无独立单测，靠增量补 QQIntentRouter 或用 service 层覆盖）

**Interfaces:**
- Consumes: `UserService.findByQqOpenid`、`UserService.createByQqOpenid`、`QQBotService.generateBindCode(openid)`。
- Produces: 新用户首消息自动开户；「登录|登录码|验证码|网页登录|扫码」触发登录码回复；新账号回复前缀欢迎语。

- [ ] **Step 1: 自动开户 + 欢迎语**

`QQMessageProcessor.kt` process() 第 76-94 行，将：
```kotlin
val user = userService.findByQqOpenid(openid)
if (user == null) {
    val code = qqBotService.generateBindCode(openid)
    if (groupOpenid != null) { qqBotService.sendGroupMessage(groupOpenid, "你还未绑定账号。绑定码: $code\n请在网页设置中输入此码完成绑定。", msgId) }
    else { qqBotService.sendC2CMessage(openid, "你还未绑定账号。\n绑定码: $code\n请在网页设置中输入此码完成绑定。", msgId) }
    return
}
val router = QQIntentRouter(...)
val reply = router.route(content, user.id)
if (groupOpenid != null) { qqBotService.sendGroupMessage(groupOpenid, reply, msgId) }
else { qqBotService.sendC2CMessage(openid, reply, msgId) }
```
替换为：
```kotlin
// 自动开户：openid 即账号，无需手机号/App 前置（QQ 即账号）。
val existing = userService.findByQqOpenid(openid)
val isNew = existing == null
val user = existing ?: userService.createByQqOpenid(openid)
logger.info("QQ user resolved: id=${user.id} new=$isNew")

// 「登录码」指令：返回一次性 QQ 登录码供网页登录。
if (LOGIN_CODE.containsMatchIn(content)) {
    val code = qqBotService.generateBindCode(openid)
    val reply = "网页登录码: $code\n在网页「QQ 登录」输入此码即可登录你的记账账号。"
    if (groupOpenid != null) { qqBotService.sendGroupMessage(groupOpenid, reply, msgId) }
    else { qqBotService.sendC2CMessage(openid, reply, msgId) }
    return
}

val router = QQIntentRouter(billService, budgetService, insightService, nluService)
val reply = router.route(content, user.id)
val out = if (isNew) "欢迎！已开通 QQ 记账账号。用中文说「午餐20元」即可记账；回复「登录」可获取网页登录码。\n\n$reply" else reply
if (groupOpenid != null) { qqBotService.sendGroupMessage(groupOpenid, out, msgId) }
else { qqBotService.sendC2CMessage(openid, out, msgId) }
```
并在 companion/顶层加：
```kotlin
private val LOGIN_CODE = Regex("登录|登录码|验证码|网页登录|扫码", RegexOption.IGNORE_CASE)
```
（`LOGIN_CODE` 放在 `QQMessageProcessor` 顶层 `private val`，与 `logger` 同级。）

- [ ] **Step 2: 编译 + 全量测试**

Run: `export GRADLE_USER_HOME=D:/gradle && ./gradlew :server:test -q`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 人工冒烟（连线上 QQ 机器人）**

给机器人发「午餐20元」→ 若为新 openid 应收到「欢迎！…已记录：三餐 ¥20.00」；再发「登录」→ 收到网页登录码；网页「QQ 登录」输码 → 进入同账号看到该笔账单。

- [ ] **Step 4: Commit**

```bash
git add server/src/main/kotlin/com/example/rinklnote/server/services/QQMessageProcessor.kt
git commit -m "feat(server): QQ 首消息自动开户 + 登录码指令（去 App 绑定前置）"
```

---

## 部署 & 验证（可选，用户自动部署）

- 服务端：`./gradlew :server:installDist` → tar → `scp jmbot` → `systemctl restart rinklnote`（见 user_preferences.md 部署流程）。
- 验证：`systemctl is-active rinklnote` + `curl http://127.0.0.1:8080/api/bills/categories` 200；`POST /api/auth/qq-login` 无 token 时 `{"code":"x"}` 返回 401/400 而非 500。
- Web 部署：`sudo cp web/index.html /var/www/rinklnote/index.html`（root 所有）。

---

## Self-Review

- **Spec/Week8 覆盖**：item 1（openid 自动开户）由 Task 4 + Task 1/2 完成；去 App 绑定前置达成；FR8「首消息开通 + ≤3 条消息记第一笔」满足（首消息即可记账）。item 6（Web 登录同账号）由 Task 2/3 的 qq-login 覆盖。item 5（多端同步）复用现有 sync 协议，未改动。item 2/3/4 已存在，本计划不重复实现。
- **已知边界（不阻塞，注释在计划外）**：已有手机号账号 + 纯 openid 账号并存时是两条记录；后续「认领/合并」需独立实现。qq-login 与既有 `/api/qq-bot/bind`（手机账号绑 openid）为两套不同语义，共用 6 位码机制、互不冲突。
- **类型一致性**：`generateToken(userId, phone: String?)` 在 Task 1 定义、Task 2 用 `user.phone`（String?）调用一致；`findByQqOpenid`/`createByQqOpenid`/`consumeBindCode` 签名跨任务一致；`MeResponse.phone`/`UserInfo.phone` 均 String?。`LOGIN_CODE` 在 Task 4 定义为顶层 `private val`。
- **迁移**：`ALTER COLUMN ... SET NULL` 已含 try/catch，fresh DB 与生产 DB 均安全。
