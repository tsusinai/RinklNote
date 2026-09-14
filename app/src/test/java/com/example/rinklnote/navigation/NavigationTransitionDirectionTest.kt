package com.example.rinklnote.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** 二级页不再复用主页的横向滑动转场。 */
    @Test
    fun secondaryPagesDoNotUseHorizontalTabTransition() {
        assertFalse(usesHorizontalTabTransition("bookkeeping", "ai"))
        assertFalse(usesHorizontalTabTransition("profile", "custom-theme"))
        assertFalse(usesHorizontalTabTransition("month-detail", "bookkeeping"))
        assertFalse(usesHorizontalTabTransition("bill-edit", "month-detail"))
    }

    @Test
    fun onlyTabToTabUsesHorizontalTabTransition() {
        assertTrue(usesHorizontalTabTransition("plan", "bookkeeping"))
        assertTrue(usesHorizontalTabTransition("profile", "assets"))
    }

    @Test
    fun swipeMovesToAdjacentTabWithoutWrapping() {
        assertEquals("assets", adjacentTabRoute("bookkeeping", 1))
        assertEquals("bookkeeping", adjacentTabRoute("assets", -1))
        assertNull(adjacentTabRoute("plan", -1))
        assertNull(adjacentTabRoute("profile", 1))
        assertNull(adjacentTabRoute("ai", 1))
    }
}
