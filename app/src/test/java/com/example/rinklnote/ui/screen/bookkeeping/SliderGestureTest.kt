package com.example.rinklnote.ui.screen.bookkeeping

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SliderGestureTest {
    @Test fun smallMovementAndReturnToCenterCancel() {
        assertNull(resolveSliderDirection(8f, -12f, 36f))
        assertEquals(SliderDirection.LEFT, resolveSliderDirection(-80f, 4f, 36f))
        assertNull(resolveSliderDirection(-80f + 76f, 4f, 36f))
    }
    @Test fun dominantCumulativeDirectionWins() {
        assertEquals(SliderDirection.LEFT, resolveSliderDirection(-80f, -20f, 36f))
        assertEquals(SliderDirection.RIGHT, resolveSliderDirection(80f, -20f, 36f))
        assertEquals(SliderDirection.UP, resolveSliderDirection(10f, -80f, 36f))
    }
    @Test fun downwardGestureDoesNotBook() {
        assertNull(resolveSliderDirection(10f, 80f, 36f))
    }
    @Test fun boundaryIsConsistent() {
        assertNull(resolveSliderDirection(35.9f, 0f, 36f))
        assertEquals(SliderDirection.RIGHT, resolveSliderDirection(36f, 0f, 36f))
    }
}
