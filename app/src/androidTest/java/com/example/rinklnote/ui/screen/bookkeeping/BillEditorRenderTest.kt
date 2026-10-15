package com.example.rinklnote.ui.screen.bookkeeping

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.rinklnote.data.db.entity.*
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.domain.ACCOUNT_ICON_BANK_CARD
import com.example.rinklnote.ui.theme.RinklNoteTheme
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.util.today
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference
import java.time.LocalDate

/** 在平台原生窗口中核对布局与输入结果，不修改真实账单数据。 */
@RunWith(AndroidJUnit4::class)
class BillEditorRenderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val date = today()
    private val bill = Bill(id = 1, amountMinor = 12850, categoryId = 1, categoryName = "三餐",
        subCategoryName = "午餐", accountId = 1,
        date = date.atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli())
    private val categories = listOf(Category(1, "三餐", ""), Category(2, "交通", ""), Category(3, "日用", ""),
        Category(4, "学习", ""), Category(5, "运动", ""), Category(6, "娱乐", ""),
        Category(8, "网购", ""), Category(9, "医疗", ""))
    private val accounts = listOf(Account(1, "无账户", iconColor = "#7EC1FC"),
        Account(2, "银行卡", iconKey = ACCOUNT_ICON_BANK_CARD, iconColor = "#7EC1FC"))
    private val saved = AtomicReference<Bill?>()
    private val cancelled = AtomicReference(false)
    private val deleted = AtomicReference(false)

    private fun show(dark: Boolean = false, narrow: Boolean = false): ComponentActivity {
        val activity = instrumentation.startActivitySync(Intent().setClassName(instrumentation.targetContext.packageName,
            ComponentActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        instrumentation.runOnMainSync {
            activity.setContent {
                RinklNoteTheme(darkTheme = dark) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                        Surface(Modifier.fillMaxSize()) {
                            Box(Modifier.fillMaxSize()) {
                                Box(if (narrow) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                                    BillEditOverlay(bill, categories, listOf(Category(7, "工资", "", BillType.INCOME)),
                                        accounts, null, null, { cancelled.set(true) }, { deleted.set(true) }, { saved.set(it) }, { id ->
                                            if (id == 1L) listOf(SubCategory(1, "早餐", 1), SubCategory(2, "午餐", 1), SubCategory(3, "晚餐", 1),
                                                SubCategory(5, "零食", 1), SubCategory(6, "外卖", 1), SubCategory(7, "饮品", 1))
                                            else if (id == 2L) listOf(SubCategory(4, "公交", 2))
                                            else if (id == 8L) listOf(SubCategory(8, "快递", 8)) else emptyList()
                                        })
                                }
                            }
                        }
                    }
                }
            }
        }
        await { find("保存") != null && find("三餐 / 午餐") != null }
        instrumentation.waitForIdleSync()
        return activity
    }

    @Test fun `浅色页金额只出现一次且触控高度合适`() {
        val activity = show()
        try {
            screenshot("editor-light.png")
            assertEquals(1, nodes().count { it.text?.toString() == "128.5" || it.text?.toString() == "128.50" })
            listOf("三餐", "1", "保存").forEach { label ->
                var node = requireNotNull(find(label))
                while (!node.isClickable && node.parent != null) node = node.parent
                val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
                assertTrue("$label 的触控高度不足", bounds.height() >= instrumentation.targetContext.resources.displayMetrics.density * 47)
            }
            val typeBounds = android.graphics.Rect().also { find("支出")!!.getBoundsInScreen(it) }
            val remarkBounds = android.graphics.Rect().also { find("添加备注 · 可选")!!.getBoundsInScreen(it) }
            val amountBounds = android.graphics.Rect().also { find("本笔金额")!!.getBoundsInScreen(it) }
            assertTrue("收支切换应在底部输入区", typeBounds.top > amountBounds.bottom)
            assertTrue("收支切换应位于备注上方", typeBounds.bottom <= remarkBounds.top)
            val meal = android.graphics.Rect().also { find("三餐")!!.getBoundsInScreen(it) }
            val transport = android.graphics.Rect().also { find("交通")!!.getBoundsInScreen(it) }
            val daily = android.graphics.Rect().also { find("日用")!!.getBoundsInScreen(it) }
            assertEquals("同一行分类文字应对齐", meal.top, daily.top)
            assertEquals("主分类应在同一排", meal.top, transport.top)
            assertTrue("主分类选项不能重叠", meal.right <= transport.left)
            var dateNode = requireNotNull(find("日期"))
            while (!dateNode.isClickable && dateNode.parent != null) dateNode = dateNode.parent
            val dateBounds = android.graphics.Rect().also(dateNode::getBoundsInScreen)
            assertTrue("单排细分类下日期选择应完整露出", dateBounds.bottom <= typeBounds.top)
            screenshot("editor-light.png")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun `窄屏标准字号深色页可以选择并保存整数金额`() {
        val activity = show(dark = true, narrow = true)
        try {
            screenshot("editor-dark-narrow.png")
            click("清空")
            await { find("0.00") != null }
            screenshot("editor-empty-amount.png")
            // 直接验证空金额无法提交，不依赖 Android 对禁用语义节点的 enabled 映射。
            find("保存")!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            instrumentation.waitForIdleSync()
            assertNull("空金额不应触发保存", saved.get())
            listOf("1", "2", ".", "5").forEach(::click)
            click("收入")
            await { find("工资") != null && isSelected("收入") }
            click("收入")
            await { find("工资") != null && isSelected("收入") }
            assertNotNull("切换类型应保留金额", find("12.5"))
            click("支出")
            await { find("交通") != null && isSelected("支出") }
            click("交通")
            await { find("公交") != null }
            click("公交")
            await { isSelected("公交") }
            click("保存")
            await { saved.get() != null }
            assertEquals(1250L, saved.get()!!.amountMinor)
            assertEquals(2L, saved.get()!!.categoryId)
            assertEquals("公交", saved.get()!!.subCategoryName)
            assertEquals(bill.accountId, saved.get()!!.accountId)
            assertNull(saved.get()!!.latitude)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun `全部分类选择后保留主面板入口且细分随分类重置`() {
        val activity = show()
        try {
            assertTrue("打开已有账单应保留细分", isSelected("午餐"))
            click("全部")
            await { find("全部支出分类") != null }
            screenshot("editor-all-categories.png")
            click("网购")
            await { find("全部支出分类") == null && find("快递") != null }
            assertTrue("其他分类应保留主面板入口", isSelected("网购"))
            click("快递")
            await { isSelected("快递") }
            click("三餐")
            await { find("三餐 / 不细分") != null }
            assertTrue("切换主分类应清掉原细分", isSelected("不细分"))
            click("午餐")
            click("银行卡")
            await { isSelected("银行卡") }
            click("保存")
            await { saved.get() != null }
            assertEquals(12850L, saved.get()!!.amountMinor)
            assertEquals(1L, saved.get()!!.categoryId)
            assertEquals("午餐", saved.get()!!.subCategoryName)
            assertEquals(2L, saved.get()!!.accountId)
            assertEquals(bill.date, saved.get()!!.date)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun `日期备注与放弃修改及删除入口仍可用`() {
        val activity = show()
        try {
            click("日期")
            await { find("确定") != null }
            // 选用当前月不同的一天，只操作内存中的编辑样例。
            val nextDate = date.withDayOfMonth(if (date.dayOfMonth == 13) 14 else 13)
            click(nextDate.dayOfMonth.toString())
            click("确定")
            await { find(nextDate.monthValue.toString() + "月" + nextDate.dayOfMonth + "日 " + weekday(nextDate)) != null }
            click("添加备注 · 可选")
            await { nodes().any { it.isEditable } }
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "编辑页验证备注")
            }
            requireNotNull(nodes().firstOrNull { it.isEditable }).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            await { find("编辑页验证备注") != null }
            instrumentation.uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK), true)
            instrumentation.uiAutomation.injectInputEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK), true)
            await { find("1") != null }
            click("返回")
            await { find("放弃未保存的修改？") != null }
            click("继续编辑")
            await { find("放弃未保存的修改？") == null }
            click("保存")
            await { saved.get() != null }
            assertEquals("编辑页验证备注", saved.get()!!.remark)
            assertEquals(nextDate.atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli(), saved.get()!!.date)
            assertFalse("继续编辑不能取消页面", cancelled.get())
            click("删除账单")
            await { deleted.get() }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun weekday(date: LocalDate) = date.format(java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.CHINESE))

    private fun nodes(): List<AccessibilityNodeInfo> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
        val result = mutableListOf<AccessibilityNodeInfo>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (!node.refresh()) continue
            result.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return result
    }
    private fun find(label: String) = nodes().firstOrNull { it.isVisibleToUser &&
        (it.text?.toString()?.lineSequence()?.any { text -> text == label } == true || it.contentDescription?.toString() == label) }
    private fun isSelected(label: String): Boolean {
        var node = find(label)
        // 单选按钮的 Compose selected 通过 Android checked 字段对外暴露。
        while (node != null) {
            if (node.isChecked || node.isSelected) return true
            if (node.isClickable) return false
            node = node.parent
        }
        return false
    }
    private fun click(label: String) {
        reveal(label)
        SystemClock.sleep(200)
        var target = requireNotNull(find(label))
        while (!target.isClickable && target.parent != null) target = target.parent
        // 优先走 Compose 暴露的单击语义；横向 LazyRow 中用坐标注入时，手势可能被父列表当成滑动。
        if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            instrumentation.waitForIdleSync()
            return
        }
        val bounds = Rect().also(target::getBoundsInScreen)
        var ancestor = target.parent
        while (ancestor != null) {
            val parentBounds = Rect().also(ancestor::getBoundsInScreen)
            if (!parentBounds.isEmpty) bounds.intersect(parentBounds)
            ancestor = ancestor.parent
        }
        val down = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
            event.recycle()
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(60)
        }
        instrumentation.waitForIdleSync()
    }
    private fun reveal(label: String) {
        if (find("全部支出分类") == null && find("确定") == null) {
            repeat(6) {
                nodes().filter { it.isScrollable }.forEach { list ->
                    list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
                }
                instrumentation.waitForIdleSync()
                SystemClock.sleep(80)
            }
        }
        repeat(8) {
            if (find(label) != null) return
            nodes().filter { it.isScrollable }.forEach { list ->
                list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(80)
        }
        await { find(label) != null }
    }
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!check()) {
            if (SystemClock.uptimeMillis() >= deadline) {
                screenshot("editor-interaction-failure.png")
                fail("等待编辑页交互超时：" + nodes().mapNotNull { it.text }.joinToString(" / "))
            }
            SystemClock.sleep(50)
        }
    }
    private fun screenshot(name: String) {
        SystemClock.sleep(350)
        val file = File(instrumentation.targetContext.getExternalFilesDir("validation"), name)
        file.parentFile!!.mkdirs()
        file.outputStream().use { requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
