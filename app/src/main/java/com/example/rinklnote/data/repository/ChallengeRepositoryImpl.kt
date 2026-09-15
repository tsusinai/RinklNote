package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.AppDatabase
import com.example.rinklnote.data.db.entity.Challenge
import com.example.rinklnote.data.db.entity.ChallengeStatus
import kotlinx.coroutines.flow.Flow

internal class ChallengeRepositoryImpl(
    private val db: AppDatabase
) : ChallengeRepository {

    private val challengeDao = db.challengeDao()

    override fun observeAll(): Flow<List<Challenge>> = challengeDao.observeAll()

    override suspend fun upsertActive(type: String, periodStart: Long, goal: Long) {
        val now = System.currentTimeMillis()
        val existing = challengeDao.getByScope(type, periodStart)
        if (existing == null) {
            challengeDao.upsert(
                Challenge(
                    type = type,
                    periodStart = periodStart,
                    goal = goal,
                    status = ChallengeStatus.ACTIVE,
                    updatedAt = now,
                    deleted = false,
                    dirty = true
                )
            )
        } else {
            // 同 scope 已存在：改目标并复活为进行中；保留 id / server_id，同步续推同一行。
            challengeDao.upsert(
                existing.copy(
                    goal = goal,
                    status = ChallengeStatus.ACTIVE,
                    deleted = false,
                    dirty = true,
                    updatedAt = now
                )
            )
        }
    }

    override suspend fun updateGoal(id: Long, goal: Long) {
        challengeDao.updateGoal(id, goal, System.currentTimeMillis())
    }

    override suspend fun updateStatus(id: Long, status: String) {
        challengeDao.updateStatus(id, status, System.currentTimeMillis())
    }
}
