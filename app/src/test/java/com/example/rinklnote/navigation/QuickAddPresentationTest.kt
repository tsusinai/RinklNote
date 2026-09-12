package com.example.rinklnote.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickAddPresentationTest {

    @Test
    fun `amount tap hides drawer before keypad appears`() {
        val transitioning = QuickAddSurface.Drawer.onAmountTap()

        assertFalse(transitioning.drawerVisible)
        assertFalse(transitioning.keypadVisible)

        val keypad = transitioning.onTransitionFinished()
        assertEquals(QuickAddSurface.Keypad, keypad)
        assertTrue(keypad.keypadVisible)
    }

    @Test
    fun `keypad dismiss hides keypad before drawer returns`() {
        val transitioning = QuickAddSurface.Keypad.onKeypadDismissed()

        assertFalse(transitioning.drawerVisible)
        assertFalse(transitioning.keypadVisible)

        val drawer = transitioning.onTransitionFinished()
        assertEquals(QuickAddSurface.Drawer, drawer)
        assertTrue(drawer.drawerVisible)
    }

    @Test
    fun `closing during a transition always finishes closed`() {
        val closed = QuickAddSurface.Drawer
            .onAmountTap()
            .close()
            .onTransitionFinished()

        assertEquals(QuickAddSurface.Closed, closed)
        assertFalse(closed.drawerVisible)
        assertFalse(closed.keypadVisible)
    }

    @Test
    fun `irrelevant transition finish keeps current surface`() {
        assertEquals(
            QuickAddSurface.Drawer,
            QuickAddSurface.Drawer.onTransitionFinished()
        )
        assertEquals(
            QuickAddSurface.Keypad,
            QuickAddSurface.Keypad.onTransitionFinished()
        )
    }
}
