package com.example.rinklnote.server.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.mindrot.jbcrypt.BCrypt
import com.example.rinklnote.server.tables.UsersTable
import java.time.LocalDateTime
import java.util.*

data class UserInfo(
    val id: Long,
    val phone: String?,
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
    val dailyReportMinute: Int = 0
)

class UserService(
    private val jwtSecret: String,
    private val jwtIssuer: String,
    private val jwtAudience: String
) {
    fun register(phone: String, password: String): Pair<Long, String> {
        val hash = BCrypt.hashpw(password, BCrypt.gensalt())
        val userId = transaction {
            UsersTable.insert {
                it[UsersTable.phone] = phone
                it[passwordHash] = hash
                it[createdAt] = LocalDateTime.now().toString()
            } get UsersTable.id
        }
        val token = generateToken(userId, phone)
        return Pair(userId, token)
    }

    fun login(phone: String, password: String): Pair<Long, String>? {
        val user = transaction {
            UsersTable.selectAll().where { UsersTable.phone eq phone }.singleOrNull()
        } ?: return null

        val hash = user[UsersTable.passwordHash]
        if (!BCrypt.checkpw(password, hash)) return null

        val userId = user[UsersTable.id]
        val token = generateToken(userId, phone)
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
        qqNumber = this[UsersTable.qqNumber],
        qqOpenid = this[UsersTable.qqOpenid],
        feishuOpenId = this[UsersTable.feishuOpenId],
        wechatOpenid = this[UsersTable.wechatOpenid],
        wecomUserid = this[UsersTable.wecomUserid],
        createdAt = this[UsersTable.createdAt],
        aiDisabled = this[UsersTable.aiDisabled],
        dailyReportEnabled = this[UsersTable.dailyReportEnabled],
        dailyReportHour = this[UsersTable.dailyReportHour],
        dailyReportMinute = this[UsersTable.dailyReportMinute]
    )

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
            val hash = UsersTable.selectAll()
                .where { UsersTable.id eq userId }
                .singleOrNull()?.get(UsersTable.passwordHash)
                ?: return@transaction false
            if (!BCrypt.checkpw(oldPassword, hash)) return@transaction false
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
        return builder
            .withExpiresAt(Date(System.currentTimeMillis() + 24 * 60 * 60 * 1000))
            .sign(Algorithm.HMAC256(jwtSecret))
    }
}
