package com.example.rinklnote.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** 使用原生 RemoteViews 渲染四款组件；演示数据只留在内存，不写真实账本或偏好。 */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
@RunWith(AndroidJUnit4::class)
class WidgetRenderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val totals = WidgetTotals(4800, 0, 128600, 300000, listOf(
        WidgetCategoryTotal("三餐", 62800), WidgetCategoryTotal("日用", 28200),
        WidgetCategoryTotal("娱乐", 19000), WidgetCategoryTotal("交通", 18600)))
    private val sizes = mapOf(WidgetKind.OVERVIEW to DpSize(360.dp, 72.dp),
        WidgetKind.QUICK_ADD to DpSize(174.dp, 200.dp), WidgetKind.BUDGET to DpSize(174.dp, 200.dp),
        WidgetKind.CATEGORIES to DpSize(360.dp, 200.dp))

    @Test fun fourProvidersAreRegisteredForHomeScreenOnly() {
        val providers = AppWidgetManager.getInstance(context).installedProviders.filter { it.provider.packageName == context.packageName }
        val expected = listOf(RinklNoteAppWidgetReceiver::class.java, QuickAddWidgetReceiver::class.java,
            BudgetWidgetReceiver::class.java, CategoryWidgetReceiver::class.java)
        expected.forEach { receiver ->
            val provider = providers.single { it.provider.className == receiver.name }
            assertEquals(AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, provider.widgetCategory)
            assertNotEquals(0, provider.previewLayout)
        }
    }

    @Test fun lightVisibleAndProtectedWidgetsRenderWithoutPrivateAccessibilityText() {
        renderBoard(hidden = false, dark = false)
        renderBoard(hidden = true, dark = false)
    }

    @Test fun darkAndCompactWidgetsRemainUsable() {
        renderBoard(hidden = false, dark = true)
        listOf(WidgetKind.QUICK_ADD, WidgetKind.BUDGET).forEach { kind ->
            val view = render(kind, DpSize(160.dp, 160.dp), hidden = true, dark = true)
            val nodes = textViews(view)
            assertTrue(nodes.any { it.text.toString().contains(if (kind == WidgetKind.QUICK_ADD) "＋¥50" else "已隐藏") })
            assertNoClippedText(view)
        }
    }

    @Test fun shortcutsOnlyPrefillAndDoNotSaveBills() {
        val preset = RinklNoteAppWidgetReceiver.amountAddIntent(context, 5000)
        assertTrue(preset.getBooleanExtra(RinklNoteAppWidgetReceiver.EXTRA_OPEN_QUICK_ADD, false))
        assertEquals(5000, preset.getLongExtra(RinklNoteAppWidgetReceiver.EXTRA_AMOUNT_MINOR, 0))
        val category = RinklNoteAppWidgetReceiver.categoryAddIntent(context, 3)
        assertEquals(3, category.getLongExtra(RinklNoteAppWidgetReceiver.EXTRA_CATEGORY_ID, 0))
        assertEquals("com.example.rinklnote.MainActivity", preset.component?.className)
    }

    private fun renderBoard(hidden: Boolean, dark: Boolean) {
        val views = sizes.map { (kind, size) ->
            val view = render(kind, size, hidden, dark)
            val text = textViews(view).joinToString("|") { it.text.toString() }
            assertNoClippedText(view)
            if (hidden && kind != WidgetKind.QUICK_ADD) {
                assertFalse(text, text.contains("1,286") || text.contains("1,714") || text.contains("43%"))
                assertTrue(text, text.contains(WidgetPresentation.MASK))
            }
            view
        }
        instrumentation.runOnMainSync {
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(12), dp(12), dp(12))
                setBackgroundColor(if (dark) Color.rgb(14, 20, 29) else Color.rgb(236, 241, 247))
            }
            container.addView(views[0], LinearLayout.LayoutParams(dp(360), dp(72)).apply { bottomMargin = dp(12) })
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(views[1], LinearLayout.LayoutParams(dp(174), dp(200)).apply { rightMargin = dp(12) })
            row.addView(views[2], LinearLayout.LayoutParams(dp(174), dp(200)))
            container.addView(row, LinearLayout.LayoutParams(dp(360), dp(200)).apply { bottomMargin = dp(12) })
            container.addView(views[3], LinearLayout.LayoutParams(dp(360), dp(200)))
            container.measure(View.MeasureSpec.makeMeasureSpec(dp(384), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            container.layout(0, 0, container.measuredWidth, container.measuredHeight)
            val bitmap = Bitmap.createBitmap(container.width, container.height, Bitmap.Config.ARGB_8888)
            container.draw(Canvas(bitmap))
            val file = File(context.getExternalFilesDir(null), "widgets-${if (hidden) "private" else "visible"}-${if (dark) "dark" else "light"}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    private fun render(kind: WidgetKind, size: DpSize, hidden: Boolean, dark: Boolean): View {
        val config = Configuration(context.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        val themed = context.createConfigurationContext(config)
        val data = WidgetContent(presentWidgetTotals(totals, hidden), month = 10)
        val remoteViews = runBlocking { GlanceRemoteViews().compose(themed, size) { WidgetSurface(kind, data) }.remoteViews }
        var rendered: View? = null
        instrumentation.runOnMainSync {
            val root = FrameLayout(themed)
            rendered = remoteViews.apply(themed, root)
            rendered!!.measure(View.MeasureSpec.makeMeasureSpec(dp(size.width.value.toInt()), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dp(size.height.value.toInt()), View.MeasureSpec.EXACTLY))
            rendered!!.layout(0, 0, rendered!!.measuredWidth, rendered!!.measuredHeight)
        }
        return rendered!!
    }

    private fun assertNoClippedText(view: View) {
        textViews(view).filter { it.visibility == View.VISIBLE && it.text.isNotEmpty() }.forEach { text ->
            assertTrue("文字高度被裁切：${text.text}", text.height >= text.lineHeight)
            text.layout?.let { layout ->
                assertTrue("文字被省略：${text.text}", (0 until layout.lineCount).all { layout.getEllipsisCount(it) == 0 })
            }
        }
    }

    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view)
        is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }
        else -> emptyList()
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()
}
