package com.example.rinklnote.server.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.rinklnote.server.plugins.AdminIdentities
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import com.example.rinklnote.server.tables.UsersTable
import java.time.LocalDateTime
import java.util.*

data class UserInfo(
    val id: Long,
    val phone: String?,
    // 邮箱身份（2026-09-17 优化登录方式）：与 phone 同级，均可空。
    val email: String? = null,
    val qqNumber: String?,
    val qqOpenid: String?,
    // 多通道 bot 身份（B1 通道底座）：与 qqOpenid 同构，一个用户可同时绑定多个通道。
    val feishuOpenId: String? = null,
    val wechatOpenid: String? = null,
    val wecomUserid: String? = null,
    val createdAt: String? = null,
    val aiDisabled: Boolean = false,
    val dailyReportEnabled: Boolean = false,
    val dailyReportHour: Int = 9,
    val dailyReportMinute: Int = 0,
    // 个人资料（2026-09-17）：全量同步字段，均可空（未设置走客户端回落）。
    val nickname: String? = null,
    val signature: String? = null,
    val birthday: String? = null,
    val avatarUrl: String? = null,
    val showcaseBadges: String? = null
)

class UserService(
    private val jwtSecret: String,
    private val jwtIssuer: String,
    private val jwtAudience: String
) {
    /** 旧签名兼容入口：手机号注册（唯一性由路由层查重，这里直接落库）。 */
    fun register(phone: String, password: String): Pair<Long, String> = register(phone, null, password)

    /**
     * 注册（2026-09-17 起支持邮箱身份）：phone / email 至少给一个，都给则同时写入。
     * 邮箱统一以小写存取（大小写不敏感，见 [normalizeEmail]）；唯一性查重在路由层完成。
     */
    fun register(phone: String?, email: String?, password: String): Pair<Long, String> {
        val hash = BCrypt.hashpw(password, BCrypt.gensalt())
        val userId = transaction {
            UsersTable.insert {
                it[UsersTable.phone] = phone?.takeIf { p -> p.isNotBlank() }
                it[UsersTable.email] = normalizeEmail(email)
                it[passwordHash] = hash
                it[createdAt] = LocalDateTime.now().toString()
            } get UsersTable.id
        }
        val token = generateToken(userId, phone)
        return Pair(userId, token)
    }

    /** 手机号登录（唯一性由 phone 唯一索引保证）。 */
    fun login(phone: String, password: String): Pair<Long, String>? =
        loginByIdentity(UsersTable.phone, phone, password)

    /** 邮箱登录（2026-09-17）：与手机号登录同构，邮箱按小写匹配。 */
    fun loginByEmail(email: String, password: String): Pair<Long, String>? =
        loginByIdentity(UsersTable.email, normalizeEmail(email), password)

    /** 通用身份登录：按列匹配 + BCrypt 校验，失败返回 null。 */
    private fun loginByIdentity(column: Column<String?>, value: String?, password: String): Pair<Long, String>? {
        val user = transaction {
            UsersTable.selectAll().where { column eq value }.singleOrNull()
        } ?: return null

        val hash = user[UsersTable.passwordHash] ?: return null
        if (!BCrypt.checkpw(password, hash)) return null

        val userId = user[UsersTable.id]
        val token = generateToken(userId, user[UsersTable.phone])
        return Pair(userId, token)
    }

    fun bindQQ(userId: Long, qqNumber: String): Boolean {
        return transaction {
            val existing = UsersTable.selectAll().where { UsersTable.qqNumber eq qqNumber }.singleOrNull()
            if (existing != null && existing[UsersTable.id] != userId) return@transaction false

            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.qqNumber] = qqNumber
            }
            true
        }
    }

    fun findById(id: Long): UserInfo? {
        return transaction {
            UsersTable.selectAll().where { UsersTable.id eq id }.singleOrNull()?.toUserInfo()
        }
    }

    fun findByPhone(phone: String): UserInfo? {
        return transaction {
            UsersTable.selectAll().where { UsersTable.phone eq phone }.singleOrNull()?.toUserInfo()
        }
    }

    /** 按邮箱查用户（小写归一后匹配，与注册/登录同构）。 */
    fun findByEmail(email: String): UserInfo? {
        return transaction {
            UsersTable.selectAll().where { UsersTable.email eq normalizeEmail(email) }.singleOrNull()?.toUserInfo()
        }
    }

    /** 邮箱归一：去空白 + 转小写；null 原样返回。三端约定邮箱身份大小写不敏感。 */
    private fun normalizeEmail(email: String?): String? = email?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    fun findByQQ(qqNumber: String): UserInfo? {
        return transaction {
            UsersTable.selectAll().where { UsersTable.qqNumber eq qqNumber }.singleOrNull()?.toUserInfo()
        }
    }

    fun findByQqOpenid(openid: String): UserInfo? {
        return transaction {
            UsersTable.selectAll().where { UsersTable.qqOpenid eq openid }.singleOrNull()?.toUserInfo()
        }
    }

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

    /**
     * 所有已绑定 QQ（qqOpenid 非空）的用户，用于主动推送枚举。
     * 已被多通道版 [findAllPushUsers] 取代（B1 通道底座后主代码无调用方，仅测试保留）；
     * 保留只读，勿在新代码中调用。
     */
    @Deprecated("用 findAllPushUsers()（按 飞书 > 企业微信 > QQ 选目标通道）", ReplaceWith("findAllPushUsers()"))
    fun findAllBoundQq(): List<UserInfo> = transaction {
        UsersTable.selectAll().where { UsersTable.qqOpenid.isNotNull() }.map { it.toUserInfo() }
    }

    // ── 多通道 bot 身份（B1 通道底座）──
    // 每通道照 QQ 三件套（findBy* / createBy* / bind*）各补一套，实现走下方通用私有助手；
    // 飞书 open_id / 企业微信 userid / 订阅号 openid 与 QQ openid 同构：openid 即账号，
    // 自动开户 phone/passwordHash 为空。

    /** 飞书：按 open_id 查用户。 */
    fun findByFeishuOpenId(openId: String): UserInfo? = findByChannelColumn(UsersTable.feishuOpenId, openId)

    /** 飞书：open_id 自动开户（幂等，已存在则返回既有用户）。 */
    fun createByFeishuOpenId(openId: String): UserInfo = createByChannelColumn(UsersTable.feishuOpenId, openId)

    /** 飞书：把 open_id 绑到既有账号（open_id 已被其他账号占用时拒绝）。 */
    fun bindFeishuByOpenId(userId: Long, openId: String): Boolean = bindByChannelColumn(userId, UsersTable.feishuOpenId, openId)

    /** 飞书：解绑（B2 管理路由用，与 [unbindQq] 同构）。 */
    fun unbindFeishu(userId: Long) {
        transaction {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.feishuOpenId] = null
            }
        }
    }

    /** 企业微信：按 userid 查用户。 */
    fun findByWecomUserid(userid: String): UserInfo? = findByChannelColumn(UsersTable.wecomUserid, userid)

    /** 企业微信：userid 自动开户（幂等）。 */
    fun createByWecomUserid(userid: String): UserInfo = createByChannelColumn(UsersTable.wecomUserid, userid)

    /** 企业微信：把 userid 绑到既有账号（已被其他账号占用时拒绝）。 */
    fun bindWecomByUserid(userId: Long, userid: String): Boolean = bindByChannelColumn(userId, UsersTable.wecomUserid, userid)

    /** 企业微信：解绑（Phase D 管理路由用，与 [unbindFeishu] 同构）。 */
    fun unbindWecom(userId: Long) {
        transaction {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.wecomUserid] = null
            }
        }
    }

    /** 订阅号：按 openid 查用户。 */
    fun findByWechatOpenid(openid: String): UserInfo? = findByChannelColumn(UsersTable.wechatOpenid, openid)

    /** 订阅号：openid 自动开户（幂等）。 */
    fun createByWechatOpenid(openid: String): UserInfo = createByChannelColumn(UsersTable.wechatOpenid, openid)

    /** 订阅号：把 openid 绑到既有账号（已被其他账号占用时拒绝）。 */
    fun bindWechatByOpenid(userId: Long, openid: String): Boolean = bindByChannelColumn(userId, UsersTable.wechatOpenid, openid)

    /**
     * 任一「可主动推送」通道（飞书 / 企业微信 / QQ）已绑定的用户，供 PushScheduler 枚举日报等推送。
     * 订阅号只收不推（wechat_openid 不参与判定）。
     */
    fun findAllPushUsers(): List<UserInfo> = transaction {
        UsersTable.selectAll().where {
            UsersTable.feishuOpenId.isNotNull() or UsersTable.wecomUserid.isNotNull() or UsersTable.qqOpenid.isNotNull()
        }.map { it.toUserInfo() }
    }

    /**
     * 全库已绑定企微（wecomUserid 非空）的用户数。
     * 企微主动推送是「群 webhook 维度」（消息发到配置 webhook 的群会话，不按人单聊），
     * 多用户绑企微时私有日报会进同一个群 —— PushScheduler 用此计数判定企微通道是否可用
     * （仅全库唯一企微绑定用户时允许，安全评审修复）。
     */
    fun countWecomBoundUsers(): Int = transaction {
        UsersTable.selectAll().where { UsersTable.wecomUserid.isNotNull() }.count().toInt()
    }

    private fun findByChannelColumn(column: Column<String?>, value: String): UserInfo? = transaction {
        UsersTable.selectAll().where { column eq value }.singleOrNull()?.toUserInfo()
    }

    private fun createByChannelColumn(column: Column<String?>, value: String): UserInfo {
        return findByChannelColumn(column, value) ?: transaction {
            val userId = UsersTable.insert {
                it[column] = value
                it[createdAt] = LocalDateTime.now().toString()
            } get UsersTable.id
            UsersTable.selectAll().where { UsersTable.id eq userId }.singleOrNull()!!.toUserInfo()
        }
    }

    private fun bindByChannelColumn(userId: Long, column: Column<String?>, value: String): Boolean {
        return transaction {
            val existing = UsersTable.selectAll().where { column eq value }.singleOrNull()
            if (existing != null && existing[UsersTable.id] != userId) return@transaction false

            UsersTable.update({ UsersTable.id eq userId }) {
                it[column] = value
            }
            true
        }
    }

    fun setAiDisabled(userId: Long, disabled: Boolean) {
        transaction { UsersTable.update({ UsersTable.id eq userId }) { it[aiDisabled] = disabled } }
    }

    /** 日报推送设置：enabled 是日报子开关（总闸仍是 ai_disabled），hour/minute 为 Asia/Shanghai 时刻。 */
    fun setDailyReport(userId: Long, enabled: Boolean, hour: Int, minute: Int) {
        transaction {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[dailyReportEnabled] = enabled
                it[dailyReportHour] = hour
                it[dailyReportMinute] = minute
            }
        }
    }

    fun isAiDisabled(userId: Long): Boolean = transaction {
        UsersTable.selectAll().where { UsersTable.id eq userId }.singleOrNull()?.get(UsersTable.aiDisabled) ?: false
    }

    private fun ResultRow.toUserInfo() = UserInfo(
        id = this[UsersTable.id],
        phone = this[UsersTable.phone],
        email = this[UsersTable.email],
        qqNumber = this[UsersTable.qqNumber],
        qqOpenid = this[UsersTable.qqOpenid],
        feishuOpenId = this[UsersTable.feishuOpenId],
        wechatOpenid = this[UsersTable.wechatOpenid],
        wecomUserid = this[UsersTable.wecomUserid],
        createdAt = this[UsersTable.createdAt],
        aiDisabled = this[UsersTable.aiDisabled],
        dailyReportEnabled = this[UsersTable.dailyReportEnabled],
        dailyReportHour = this[UsersTable.dailyReportHour],
        dailyReportMinute = this[UsersTable.dailyReportMinute],
        nickname = this[UsersTable.nickname],
        signature = this[UsersTable.signature],
        birthday = this[UsersTable.birthday],
        avatarUrl = this[UsersTable.avatarUrl],
        showcaseBadges = this[UsersTable.showcaseBadges]
    )

    /**
     * 整体替换文字资料（PUT /api/auth/profile 的落库实现）。
     * 语义是「整体替换」：每个字段传什么存什么（null 即清除），头像走独立上传接口不在此改。
     * 展示徽章调用方需先校验数量 ≤ 3、key 合法（路由层负责），这里只透传。
     */
    fun updateProfile(
        userId: Long,
        nickname: String?,
        signature: String?,
        birthday: String?,
        showcaseBadges: String?
    ): UserInfo? {
        transaction {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.nickname] = nickname?.trim()?.takeIf { v -> v.isNotEmpty() }
                it[UsersTable.signature] = signature?.trim()?.takeIf { v -> v.isNotEmpty() }
                it[UsersTable.birthday] = birthday?.trim()?.takeIf { v -> v.isNotEmpty() }
                it[UsersTable.showcaseBadges] = showcaseBadges?.trim()?.takeIf { v -> v.isNotEmpty() }
            }
        }
        return findById(userId)
    }

    /** 头像上传成功后回写相对 URL；传 null 表示清除头像（上传接口目前只写非空路径）。 */
    fun updateAvatarUrl(userId: Long, avatarUrl: String?) {
        transaction {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.avatarUrl] = avatarUrl
            }
        }
    }

    fun bindByQqOpenid(userId: Long, openid: String): Boolean {
        return transaction {
            val existing = UsersTable.selectAll().where { UsersTable.qqOpenid eq openid }.singleOrNull()
            if (existing != null && existing[UsersTable.id] != userId) return@transaction false

            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.qqOpenid] = openid
            }
            true
        }
    }

    fun unbindQq(userId: Long) {
        transaction {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.qqOpenid] = null
            }
        }
    }

    fun unbindQQNumber(userId: Long) {
        transaction {
            UsersTable.update({ UsersTable.id eq userId }) {
                it[UsersTable.qqNumber] = null
            }
        }
    }

    fun updatePassword(userId: Long, oldPassword: String, newPassword: String): Boolean {
        return transaction {
            val row = UsersTable.selectAll()
                .where { UsersTable.id eq userId }
                .singleOrNull() ?: return@transaction false
            val hash = row[UsersTable.passwordHash]
            if (hash != null) {
                // 已有密码：正常改密，校验旧密码
                if (!BCrypt.checkpw(oldPassword, hash)) return@transaction false
            }
            // 无密码（QQ 等通道注册）视为首次设密：跳过旧密码校验，直接设置新密码。
            // 新密码规则仍由路由层 PasswordPolicy 把关。
            UsersTable.update({ UsersTable.id eq userId }) {
                it[passwordHash] = BCrypt.hashpw(newPassword, BCrypt.gensalt())
            }
            true
        }
    }

    fun generateToken(userId: Long, phone: String?): String {
        val builder = JWT.create()
            .withAudience(jwtAudience)
            .withIssuer(jwtIssuer)
            .withClaim("userId", userId)
        if (phone != null) builder.withClaim("phone", phone)
        // 双保险之一：名单内的身份签发 admin claim（签发时刻的快照）。
        // 名单本身仍是每请求的活判定，token 24h 过期，claim 只是辅助授信通道。
        if (AdminIdentities.matches(phone, userId)) builder.withClaim("admin", true)
        return builder
            .withExpiresAt(Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000))
            .sign(Algorithm.HMAC256(jwtSecret))
    }
}
