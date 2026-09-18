package com.example.rinklnote.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.example.rinklnote.data.db.entity.Place
import kotlinx.coroutines.flow.Flow

/** 常去地点 DAO（Room v17）。数据量小（只累计用户主动打点），全部读走全量 + 内存几何匹配。 */
@Dao
interface PlaceDao {
    @Insert
    suspend fun insert(place: Place): Long

    @Update
    suspend fun update(place: Place)

    /** 全量（最近使用在前）：匹配/建议在内存里做几何计算，量级小无需 SQL 空间函数。 */
    @Query("SELECT * FROM places ORDER BY last_used DESC")
    suspend fun getAll(): List<Place>

    /** 最近使用（响应式，供将来「常去地点」展示）。 */
    @Query("SELECT * FROM places ORDER BY last_used DESC LIMIT 20")
    fun observeRecent(): Flow<List<Place>>

    @Query("DELETE FROM places")
    suspend fun deleteAll()
}
