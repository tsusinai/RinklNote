package com.example.rinklnote.ui.screen.profile

import com.example.rinklnote.data.network.dto.MeResponse
import com.example.rinklnote.domain.AchievementState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 个人资料页纯逻辑单测：MeResponse → 状态映射、头像 URL 拼接、
 * 徽章展示交集（服务端勾选 ∩ 实时解锁态）与勾选上限。
 */
class PersonalProfileLogicTest {

    // ── applyMeToProfileState：服务端资料整体覆盖 ──

    @Test
    fun `applyMe maps all profile fields from me response`() {
        val me = MeResponse(
            id = 1L,
            phone = "13800000000",
            nickname = "记账小能手",
            signature = "少花钱多记账",
            birthday = "2000-02-29",
            avatarUrl = "uploads/avatars/1.jpg?v=123",
            showcaseBadges = "record-30,budget-first"
        )
        val state = applyMeToProfileState(PersonalProfileState(loading = true), me)
        assertEquals(false, state.loading)
        assertEquals(true, state.isLoggedIn)
        assertEquals("记账小能手", state.nickname)
        assertEquals("少花钱多记账", state.signature)
        assertEquals("2000-02-29", state.birthday)
        assertEquals("uploads/avatars/1.jpg?v=123", state.avatarUrl)
        assertEquals(listOf("record-30", "budget-first"), state.showcaseBadges)
    }

    @Test
    fun `applyMe keeps local-only fields untouched`() {
        // 本地头像缓存 / 成就列表不属于 me 映射范围，覆盖映射不得清掉它们
        val base = PersonalProfileState(
            localAvatarUri = "file:///data/avatar.jpg",
            achievements = listOf(AchievementState("record-30", true, 30, 30))
        )
        val me = MeResponse(id = 1L, phone = "13800000000", nickname = "新昵称")
        val mapped = applyMeToProfileState(base, me)
        assertEquals("file:///data/avatar.jpg", mapped.localAvatarUri)
        assertEquals(1, mapped.achievements.size)
        assertEquals("新昵称", mapped.nickname)
    }

    @Test
    fun `applyMe tolerates old server without profile fields`() {
        val me = MeResponse(id = 1L, phone = "13800000000")
        val state = applyMeToProfileState(PersonalProfileState(), me)
        assertNull(state.nickname)
        assertNull(state.signature)
        assertNull(state.birthday)
        assertNull(state.avatarUrl)
        assertEquals(emptyList<String>(), state.showcaseBadges)
    }

    // ── resolveAvatarFullUrl：相对 URL 拼接 ──

    @Test
    fun `avatar relative url joins base url`() {
        assertEquals(
            "http://host:8080/uploads/avatars/3.jpg?v=99",
            resolveAvatarFullUrl("http://host:8080/", "uploads/avatars/3.jpg?v=99")
        )
    }

    @Test
    fun `avatar absolute url passes through and null stays null`() {
        assertEquals(
            "https://cdn.example.com/a.jpg",
            resolveAvatarFullUrl("http://host:8080/", "https://cdn.example.com/a.jpg")
        )
        assertNull(resolveAvatarFullUrl("http://host:8080/", null))
        assertNull(resolveAvatarFullUrl("http://host:8080/", ""))
    }

    // ── displayableShowcaseBadges：勾选 ∩ 解锁态 ──

    private fun badge(id: String, unlocked: Boolean) = AchievementState(id, unlocked, 1, 1)

    @Test
    fun `displayable badges intersect selection with unlocked state`() {
        val achievements = listOf(
            badge("record-30", true),
            badge("record-7", false),   // 回退失去解锁态
            badge("budget-first", true)
        )
        val shown = displayableShowcaseBadges(
            listOf("record-30", "record-7", "budget-first", "challenge-3"),
            achievements
        )
        // record-7 因解锁态回退被隐藏；challenge-3 不在派生清单里同样隐藏
        assertEquals(listOf("record-30", "budget-first"), shown)
    }

    @Test
    fun `displayable badges empty when nothing selected`() {
        assertEquals(emptyList<String>(), displayableShowcaseBadges(emptyList(), listOf(badge("record-30", true))))
    }
}
