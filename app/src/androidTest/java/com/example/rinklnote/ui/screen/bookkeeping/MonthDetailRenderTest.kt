package com.example.rinklnote.ui.screen.bookkeeping

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.rinklnote.data.db.entity.Account
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.domain.buildMonthDetail
import com.example.rinklnote.ui.theme.RinklNoteTheme
import com.example.rinklnote.ui.component.BillCard
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.bookkeepingZone
import com.example.rinklnote.util.today
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

/** 原生窗口测试避开旧 Espresso 与 API 36.1 的输入兼容问题，示例账单仅在内存中。 */
@RunWith(AndroidJUnit4::class)
class MonthDetailRenderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val current = today().withDayOfMonth(1)
    private val editedBill = AtomicReference<Bill?>()
    private val copiedBill = AtomicReference<Bill?>()

    private fun label(month: LocalDate) = month.year.toString() + "年" + month.monthValue + "月"

    private fun bill(id: Long, minor: Long, day: Int, category: String, remark: String,
        type: BillType = BillType.EXPENSE) = Bill(
        id = id, amountMinor = minor, billType = type, categoryId = id, categoryName = category,
        accountId = 1, remark = remark,
        date = current.withDayOfMonth(day).atStartOfDay(bookkeepingZone()).toInstant().toEpochMilli()
    )

    private fun show(dark: Boolean = false, narrow: Boolean = false, longAmount: Boolean = false,
        home: Boolean = false): ComponentActivity {
        val selectedMonth = mutableStateOf(current)
        val monthBills = if (longAmount) listOf(bill(1, 123456789L, 5, "三餐", "午餐"))
        else listOf(bill(1, 4200L, 5, "三餐", "午餐"), bill(2, 1800L, 6, "交通", "地铁"),
            bill(3, 300000L, 10, "工资", "工资入账", BillType.INCOME))
        val activity = instrumentation.startActivitySync(Intent().setClassName(
            instrumentation.targetContext.packageName, ComponentActivity::class.java.name
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ComponentActivity
        instrumentation.runOnMainSync {
            activity.setContent {
                RinklNoteTheme(darkTheme = dark) {
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalDensity provides Density(density.density, 1f)) {
                        Surface(Modifier.fillMaxSize()) {
                            // 外层解除 Surface 的最小宽度传递，保证窄屏用例实际只有 320dp。
                            Box(Modifier.fillMaxSize()) {
                            Box(if (narrow) Modifier.width(320.dp).fillMaxHeight() else Modifier.fillMaxSize()) {
                                if (home) {
                                    val homeBills = listOf(
                                        bill(1, 4200L, 5, "三餐", "早餐"),
                                        bill(2, 1800L, 5, "交通", "地铁"),
                                        bill(3, 8800L, 5, "网购", "购买文具")
                                    )
                                    val host = androidx.compose.runtime.remember { BillDragHost() }
                                    BillCard(homeBills.first().date, "周一", -14800L, homeBills, host,
                                        onEdit = { editedBill.set(it) }, onDragFinished = { host.endDrag() },
                                        onCopyBill = { copiedBill.set(it) },
                                        modifier = Modifier.padding(top = 32.dp))
                                } else {
                                val month = selectedMonth.value
                                val bills = if (month == current) monthBills else emptyList()
                                MonthDetailOverlay(
                                    monthLabel = label(month), bills = bills, month = month,
                                    expenseTotal = bills.filter { it.billType == BillType.EXPENSE }.sumOf { it.amountMinor },
                                    incomeTotal = bills.filter { it.billType == BillType.INCOME }.sumOf { it.amountMinor },
                                    monthDetail = buildMonthDetail(bills, month.lengthOfMonth()),
                                    backgroundUri = null, hazeState = null,
                                    accounts = listOf(Account(1, "微信", iconColor = "#7EC1FC")),
                                    onSelectMonth = { selectedMonth.value = it },
                                    onEditBill = { editedBill.set(it) }, onBack = {}
                                )
                                }
                            }
                            }
                        }
                    }
                }
            }
        }
        await { if (home) find("三餐") != null else find(label(current)) != null && find("支出分析") != null }
        instrumentation.waitForIdleSync()
        return activity
    }

    private fun chooseMonth(from: LocalDate, target: LocalDate) {
        click(label(from))
        await { find("选择月份") != null }
        repeat(kotlin.math.abs(target.year - from.year)) {
            click(if (target.year < from.year) "上一年" else "下一年")
        }
        click(label(target))
        await { find("选择月份") == null && find(label(target)) != null }
    }

    @Test
    fun monthPickerResetsDayFilterBetweenEmptyMonths() {
        val activity = show()
        try {
            screenshot("month-detail-light.png")
            click(label(current))
            await { find("选择月份") != null }
            screenshot("month-detail-picker.png")
            val future = if (current.monthValue < 12) label(current.plusMonths(1)) else "下一年"
            // 通过真实点击验证禁用行为，避免平台对虚拟节点 enabled 字段映射不一致。
            click(future)
            SystemClock.sleep(200)
            assertNotNull("点击未来月份不能关闭选择框", find("选择月份"))
            assertNotNull("点击禁用的下一年不能改变年份", find(current.year.toString() + "年"))
            click("取消")
            await { find("选择月份") == null }

            val previous = current.minusMonths(1)
            chooseMonth(current, previous)
            await { find("0笔") != null }
            val day = previous.monthValue.toString() + "月5日"
            click(day + "，无支出")
            await { find(day + "暂无账单") != null }
            val secondEmpty = current.minusMonths(2)
            chooseMonth(previous, secondEmpty)
            await { find("本月暂无账单") != null }
            assertNull(find(day + "暂无账单"))
            screenshot("month-detail-empty.png")

            click(label(secondEmpty))
            await { find("选择月份") != null }
            click("回到本月")
            await { find(label(current)) != null && find("3笔") != null }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test
    fun dayAndCategoryFiltersKeepMonthlyTotalsAndAllowBillEditing() {
        val activity = show()
        try {
            val day = current.monthValue.toString() + "月5日，支出 " + Money.format(4200L)
            click(day)
            await { find("1笔") != null }
            reveal("午餐")
            assertNotNull(find("午餐"))
            reveal(Money.format(6000L))
            assertNotNull("日筛选不能改变整月支出合计", find(Money.format(6000L)))
            click(day)
            await { find("3笔") != null }

            click("筛选三餐账单")
            await { find("1笔") != null }
            click("午餐")
            await { editedBill.get() != null }
            assertEquals(1L, editedBill.get()!!.id)
            click("筛选三餐账单")
            await { find("3笔") != null }
            click("折线")
            await { isSelected("折线") }
            screenshot("month-detail-line.png")
            click("柱状")
            await { isSelected("柱状") }
            click("占比")
            await { isSelected("占比") }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test
    fun narrowDarkScreenFitsLargeAmountsAndCanSelectPreviousYear() {
        val activity = show(dark = true, narrow = true, longAmount = true)
        try {
            val amount = requireNotNull(find(Money.format(123456789L)))
            val income = requireNotNull(find(Money.format(0L)))
            val amountBounds = Rect().also(amount::getBoundsInScreen)
            val incomeBounds = Rect().also(income::getBoundsInScreen)
            assertTrue("两列长金额不能重叠", amountBounds.right <= incomeBounds.left)
            assertTrue("金额应完整处于 320dp 视口内", amountBounds.left >= 0 && incomeBounds.right <=
                (320 * instrumentation.targetContext.resources.displayMetrics.density).toInt())
            screenshot("month-detail-dark-narrow.png")
            val previousDecember = LocalDate.of(current.year - 1, 12, 1)
            chooseMonth(current, previousDecember)
            await { find("本月暂无账单") != null }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test
    fun homepageColoredIconsKeepEditingAndSwipeCopy() {
        val activity = show(home = true)
        try {
            screenshot("home-bill-category-icons.png")
            click("三餐")
            await { editedBill.get() != null }
            assertEquals(1L, editedBill.get()!!.id)
            reveal("三餐")
            val bounds = Rect().also { requireNotNull(find("三餐")).getBoundsInScreen(it) }
            val down = SystemClock.uptimeMillis()
            val distance = 96 * instrumentation.targetContext.resources.displayMetrics.density
            for (step in 0..9) {
                val action = when (step) {
                    0 -> MotionEvent.ACTION_DOWN
                    9 -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
                val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                    bounds.exactCenterX() + distance * step.coerceAtMost(8) / 8,
                    bounds.exactCenterY(), 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true))
                event.recycle()
                SystemClock.sleep(25)
            }
            await { copiedBill.get() != null }
            assertEquals(1L, copiedBill.get()!!.id)
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun nodes(): List<AccessibilityNodeInfo> {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        instrumentation.uiAutomation.rootInActiveWindow?.let(queue::add)
        val result = mutableListOf<AccessibilityNodeInfo>()
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            // 原生辅助功能会缓存虚拟节点；刷新后再读取切换后的计数和选中状态。
            if (!node.refresh()) continue
            result.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
        }
        return result
    }

    private fun find(label: String) = nodes().firstOrNull { node ->
        node.isVisibleToUser && (node.contentDescription?.toString() == label ||
            node.text?.toString()?.lineSequence()?.any { it == label } == true)
    }

    private fun isSelected(label: String): Boolean {
        var node = find(label)
        while (node != null) {
            if (node.isSelected || node.isChecked) return true
            if (node.isClickable) return false
            node = node.parent
        }
        return false
    }

    /** 标题始终可见；清单外的节点通过原生滚动动作重新露出。 */
    private fun reveal(label: String) {
        if (find("选择月份") != null || (label.contains("年") && label.endsWith("月"))) {
            await { find(label) != null }
            return
        }
        // 先回到顶部，避免把被悬浮顶栏遮住的图例节点误判成可点击目标。
        repeat(8) {
            val list = nodes().firstOrNull { it.isScrollable } ?: return@repeat
            if (!list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return@repeat
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
        repeat(8) {
            if (find(label) != null) return
            val list = nodes().firstOrNull { it.isScrollable } ?: return@repeat
            list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
        await { find(label) != null }
    }

    private fun click(label: String) {
        reveal(label)
        SystemClock.sleep(200)
        val bounds = Rect().also { requireNotNull(find(label)).getBoundsInScreen(it) }
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

    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (!check()) {
            if (SystemClock.uptimeMillis() >= deadline) {
                screenshot("month-detail-interaction-failure.png")
                fail("等待月度详情交互超时：" + nodes().mapNotNull { it.text }.joinToString(" / "))
            }
            SystemClock.sleep(50)
        }
    }

    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync()
        val file = File(instrumentation.targetContext.getExternalFilesDir("validation"), name)
        file.parentFile!!.mkdirs()
        file.outputStream().use {
            requireNotNull(instrumentation.uiAutomation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
