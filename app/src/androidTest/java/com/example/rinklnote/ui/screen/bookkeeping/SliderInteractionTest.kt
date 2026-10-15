package com.example.rinklnote.ui.screen.bookkeeping

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque

/** 注入带相同按下时间的完整手势，验证长按与点击不会互相误触。 */
@RunWith(AndroidJUnit4::class)
class SliderInteractionTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @Test fun `长按回中取消左滑进入编辑页而轻点打开快捷轮盘`() {
        instrumentation.startActivitySync(Intent().setClassName(instrumentation.targetContext.packageName,
            "com.example.rinklnote.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await { find("灵动滑块") != null }
        val rect = Rect().also { find("灵动滑块")!!.getBoundsInScreen(it) }
        val x = rect.exactCenterX(); val y = rect.exactCenterY()
        val distance = instrumentation.targetContext.resources.displayMetrics.density * 90
        // 原地、往左后回中、往上后回中、往右后回中都应取消，不能误开编辑或采集。
        listOf(0f to 0f, -distance to 0f, 0f to -distance, distance to 0f).forEach { (dx, dy) ->
            gesture(x, y, dx, dy, returnToCenter = true)
            assertNotNull("长按取消后必须留在首页", find("灵动滑块"))
            assertNull(find("本笔金额"))
        }
        gesture(x, y, -distance, 0f, returnToCenter = false)
        await { find("本笔金额") != null }
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        await { find("灵动滑块") != null || find("放弃修改") != null }
        find("放弃修改")?.let { click(it) }
        await { find("灵动滑块") != null }
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, x, y)
        SystemClock.sleep(60)
        send(MotionEvent.ACTION_UP, down, x, y)
        await { find("网购") != null && find("无账户") != null }
        assertNull("轻点只能打开快捷轮盘，不能进入完整编辑页", find("本笔金额"))
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        await { find("灵动滑块") != null || find("放弃修改") != null }
        find("放弃修改")?.let { click(it) }
    }
    private fun gesture(x: Float, y: Float, dx: Float, dy: Float, returnToCenter: Boolean) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, x, y)
        SystemClock.sleep(850)
        for (i in 1..10) { send(MotionEvent.ACTION_MOVE, down, x + dx * i / 10, y + dy * i / 10); SystemClock.sleep(20) }
        if (returnToCenter) {
            for (i in 9 downTo 0) { send(MotionEvent.ACTION_MOVE, down, x + dx * i / 10, y + dy * i / 10); SystemClock.sleep(20) }
        }
        send(MotionEvent.ACTION_UP, down, if (returnToCenter) x else x + dx, if (returnToCenter) y else y + dy)
        instrumentation.waitForIdleSync()
    }
    private fun send(action: Int, down: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return null
    }
    private fun click(node: AccessibilityNodeInfo) {
        var clickable = node
        while (!clickable.isClickable && clickable.parent != null) clickable = clickable.parent
        assertTrue(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!check()) { if (SystemClock.uptimeMillis() >= deadline) fail("等待滑块交互超时"); SystemClock.sleep(50) }
        instrumentation.waitForIdleSync()
    }
}
