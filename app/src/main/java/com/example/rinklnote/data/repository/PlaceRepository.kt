package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.dao.PlaceDao
import com.example.rinklnote.data.db.entity.Place
import com.example.rinklnote.util.PlaceGeometry
import kotlinx.coroutines.flow.Flow

interface PlaceRepository {
    /** 最近使用的常去地点（响应式）。 */
    fun observeRecent(): Flow<List<Place>>

    /** 半径内离 (lat, lng) 最近的已知地点；没有则 null。 */
    suspend fun findNearby(lat: Double, lng: Double): Place?

    /**
     * 记账带位置落库后的异步累计：半径内已有地点 → 合并（刷新 last_used / 常用分类），
     * 否则新建。失败不影响记账主流程（调用方 fire-and-forget）。
     */
    suspend fun recordPlace(lat: Double, lng: Double, categoryId: Long?, name: String?)
}

class PlaceRepositoryImpl(private val placeDao: PlaceDao) : PlaceRepository {

    override fun observeRecent(): Flow<List<Place>> = placeDao.observeRecent()

    override suspend fun findNearby(lat: Double, lng: Double): Place? =
        PlaceGeometry.nearestWithin(
            placeDao.getAll(), lat, lng, PlaceGeometry.MERGE_RADIUS_METERS,
            { it.lat }, { it.lng }
        )

    override suspend fun recordPlace(lat: Double, lng: Double, categoryId: Long?, name: String?) {
        val existing = findNearby(lat, lng)
        val now = System.currentTimeMillis()
        if (existing != null) {
            placeDao.update(
                existing.copy(
                    // 名称只在有更具体的新名字时覆盖；分类跟随最近一次记账
                    name = name?.takeIf { it.isNotBlank() } ?: existing.name,
                    categoryId = categoryId ?: existing.categoryId,
                    lastUsed = now
                )
            )
        } else {
            placeDao.insert(
                Place(
                    name = name?.takeIf { it.isNotBlank() } ?: "未命名地点",
                    lat = lat,
                    lng = lng,
                    categoryId = categoryId,
                    lastUsed = now
                )
            )
        }
    }
}
