package com.example.rinklnote.data.network.dto

import kotlinx.serialization.Serializable

/** 挑战（省钱挑战）服务端行，字段与 BudgetDTO 同款：updatedAt / deleted 带默认值可省略。 */
@Serializable
data class ChallengeDTO(
    val id: Long,
    // 挑战类型：NO_SPEND_DAY | BOOKKEEPING_STREAK | WEEKLY_BUDGET。
    val type: String,
    // 周期起点：月挑战 = 当月 1 日 0 点；周挑战 = 周一 0 点；连续记账 = 承诺日 0 点。
    val periodStart: Long,
    // 目标值。单位由 type 决定：NO_SPEND_DAY / BOOKKEEPING_STREAK = 天数；WEEKLY_BUDGET = 整数分（无 Double 兼容字段）。
    val goal: Long,
    // 状态：ACTIVE | ACHIEVED | MISSED。
    val status: String,
    val createdAt: Long,
    val updatedAt: Long? = null,
    val deleted: Boolean = false
)

/** 挑战 upsert 请求体：scope 唯一键 = user + type + period_start，命中则按服务端 LWW 更新并复活。 */
@Serializable
data class UpsertChallengeRequest(
    val type: String,
    val periodStart: Long,
    // 目标值，单位由 type 决定（天数或整数分）；goal = 0 合法。
    val goal: Long,
    val status: String = "ACTIVE",
    // 客户端行的 updated_at（毫秒），供服务端 LWW 比较：旧写被拒时仍返回服务端现行行（200，非 409）。
    val updatedAt: Long? = null
)
