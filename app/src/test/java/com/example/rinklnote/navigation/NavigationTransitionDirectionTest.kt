package com.example.rinklnote.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 过渡方向必须只由 tab 顺序决定（+1 从右滑入 / -1 从左滑入），
 * 与 popUpTo(start) 造成的 push/pop 无关。
 */
class NavigationTransitionDirectionTest {

    private fun direction(from: String?, to: String?): Int = transitionDirection(from, to)

    @Test
    fun forwardToHigherTabSlidesInFromRight() {
        assertEquals(1, direction("plan", "bookkeeping"))
        assertEquals(1, direction("bookkeeping", "assets"))
        assertEquals(1, direction("assets", "profile"))
        assertEquals(1, direction("plan", "profile"))
    }

    @Test
    fun backwardToLowerTabSlidesInFromLeft() {
        assertEquals(-1, direction("bookkeeping", "plan"))
        assertEquals(-1, direction("assets", "bookkeeping"))
        assertEquals(-1, direction("profile", "assets"))
        assertEquals(-1, direction("profile", "plan"))
    }

    /** 记账是 start destination：这条切换会走 pop 分支，但方向仍是前进。 */
    @Test
    fun navigationIntoStartDestinationStillCountsAsForward() {
        assertEquals(1, direction("plan", "bookkeeping"))
    }

    /** AI 不在 tab 列表里，视为记账页右侧的子页。 */
    @Test
    fun aiIsTreatedAsPageRightOfEveryTab() {
        assertEquals(1, direction("bookkeeping", "ai"))
        assertEquals(1, direction("profile", "ai"))
        assertEquals(-1, direction("ai", "bookkeeping"))
        assertEquals(-1, direction("ai", "plan"))
    }
}
