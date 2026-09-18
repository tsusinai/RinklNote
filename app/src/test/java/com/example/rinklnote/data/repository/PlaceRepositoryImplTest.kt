package com.example.rinklnote.data.repository

import com.example.rinklnote.data.db.dao.PlaceDao
import com.example.rinklnote.data.db.entity.Place
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * 常去地点累计（合并）逻辑单测：DAO 用 Mockito 打桩，
 * 验证「半径内合并更新 / 半径外新建」两条路径与字段刷新规则。
 */
class PlaceRepositoryImplTest {

    private lateinit var placeDao: PlaceDao
    private lateinit var repo: PlaceRepositoryImpl

    private val storeKm = Place(id = 1, name = "三餐", lat = 31.2304, lng = 121.4737, categoryId = 5, lastUsed = 1_000L)

    @Before
    fun setUp() {
        placeDao = mock()
        repo = PlaceRepositoryImpl(placeDao)
    }

    @Test
    fun `半径内打点走合并更新`() = runTest {
        whenever(placeDao.getAll()).thenReturn(listOf(storeKm))
        // 约 10 米外的再次打点（同一店铺的定位抖动）
        repo.recordPlace(31.23047, 121.47376, categoryId = 7, name = "日用")

        val captor = argumentCaptor<Place>()
        verify(placeDao).update(captor.capture())
        verify(placeDao, never()).insert(any())
        val updated = captor.firstValue
        assertEquals(1L, updated.id)               // 原行更新，不新建
        assertEquals("日用", updated.name)          // 新名字覆盖
        assertEquals(7L, updated.categoryId)       // 分类跟随最近一次记账
        assertTrue(updated.lastUsed > 1_000L)      // last_used 刷新
    }

    @Test
    fun `半径外打点走新建`() = runTest {
        whenever(placeDao.getAll()).thenReturn(listOf(storeKm))
        // 约 800 米外的新地点
        repo.recordPlace(31.2370, 121.4820, categoryId = 9, name = "交通")

        val captor = argumentCaptor<Place>()
        verify(placeDao).insert(captor.capture())
        verify(placeDao, never()).update(any())
        assertEquals("交通", captor.firstValue.name)
        assertEquals(9L, captor.firstValue.categoryId)
    }

    @Test
    fun `空库首次打点新建`() = runTest {
        whenever(placeDao.getAll()).thenReturn(emptyList())
        repo.recordPlace(31.2304, 121.4737, categoryId = 5, name = null)

        val captor = argumentCaptor<Place>()
        verify(placeDao).insert(captor.capture())
        assertEquals("未命名地点", captor.firstValue.name)  // 名字缺省兜底
        assertEquals(5L, captor.firstValue.categoryId)
    }

    @Test
    fun `合并时空名字与空分类不覆盖旧值`() = runTest {
        whenever(placeDao.getAll()).thenReturn(listOf(storeKm))
        repo.recordPlace(31.23041, 121.47371, categoryId = null, name = null)

        val captor = argumentCaptor<Place>()
        verify(placeDao).update(captor.capture())
        assertEquals("三餐", captor.firstValue.name)
        assertEquals(5L, captor.firstValue.categoryId)
    }

    @Test
    fun `findNearby命中与未命中`() = runTest {
        whenever(placeDao.getAll()).thenReturn(listOf(storeKm))
        assertNotNull(repo.findNearby(31.23044, 121.47372))  // 半径内
        assertNull(repo.findNearby(31.2370, 121.4820))       // 半径外
    }
}
