package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.entity.Challenge
import kotlinx.coroutines.flow.Flow

/** 省钱挑战仓库：纯转发 ChallengeDao，状态/目标变更均置 dirty 交 SyncManager 推送。 */
interface ChallengeRepository {
    fun observeAll(): Flow<List<Challenge>>

    /** scope 唯一（type + periodStart）的 create-or-update：不存在则新建进行中挑战，
     *  已存在（含软删/已错过）则改目标并复活为进行中。 */
    suspend fun upsertActive(type: String, periodStart: Long, goal: Long)

    /** 调整目标（单位由 type 决定：天数或整数分），置 dirty=1、updatedAt=now。 */
    suspend fun updateGoal(id: Long, goal: Long)

    /** 回写状态（ACTIVE/ACHIEVED/MISSED），置 dirty=1、updatedAt=now。 */
    suspend fun updateStatus(id: Long, status: String)
}
