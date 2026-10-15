package com.example.rinklnote.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import com.example.rinklnote.MainActivity
import com.example.rinklnote.RinklNoteApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch

internal enum class WidgetKind { OVERVIEW, QUICK_ADD, BUDGET, CATEGORIES }

/** 四个独立组件共用数据与隐私策略；桌面上不能直接解除保护。 */
abstract class RinklWidget internal constructor(private val kind: WidgetKind) : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as RinklNoteApp
        // 分类目录只用于固定快捷入口，不读取私人消费记录。
        try {
            app.repository.loadReferenceData()
        } catch (error: Exception) {
            if (error is CancellationException) throw error
        }
        val content = widgetContentFlow(app).catch {
            // 读取失败时回到保护态，不向宿主抛出含账单信息的异常页面。
            emit(WidgetContent())
        }
        provideContent {
            val state by content.collectAsState(initial = WidgetContent())
            WidgetSurface(kind, state)
        }
    }
}

// 保留原接收器和组件类，已有桌面实例升级后继续可用。
class RinklNoteAppWidget : RinklWidget(WidgetKind.OVERVIEW)
class QuickAddWidget : RinklWidget(WidgetKind.QUICK_ADD)
class BudgetWidget : RinklWidget(WidgetKind.BUDGET)
class CategoryWidget : RinklWidget(WidgetKind.CATEGORIES)

class RinklNoteAppWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RinklNoteAppWidget()

    companion object {
        const val EXTRA_OPEN_QUICK_ADD = "extra_open_quick_add"
        const val EXTRA_CATEGORY_ID = "extra_category_id"
        const val EXTRA_AMOUNT_MINOR = "extra_amount_minor"

        fun categoryAddIntent(context: Context, categoryId: Long): Intent = quickAddIntent(context)
            .putExtra(EXTRA_CATEGORY_ID, categoryId)

        /** 只预填，不直接保存；仍由应用内的一步确认完成记账。 */
        fun amountAddIntent(context: Context, amountMinor: Long): Intent = quickAddIntent(context)
            .putExtra(EXTRA_AMOUNT_MINOR, amountMinor)

        fun quickAddIntent(context: Context): Intent = appIntent(context).putExtra(EXTRA_OPEN_QUICK_ADD, true)
        fun appIntent(context: Context): Intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}

class BudgetWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BudgetWidget()
}

class CategoryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CategoryWidget()
}

/** 刷新四个组件家族；一款宿主失效不能阻断其余组件更新。 */
suspend fun updateRinklWidgets(context: Context) {
    listOf(RinklNoteAppWidget(), QuickAddWidget(), BudgetWidget(), CategoryWidget()).forEach { widget ->
        try {
            widget.updateAll(context)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.w("RinklWidgets", "桌面组件刷新失败：${widget.javaClass.simpleName}")
        }
    }
}

@Composable
internal fun WidgetSurface(kind: WidgetKind, content: WidgetContent) {
    Box(GlanceModifier.fillMaxSize().cornerRadius(WidgetStyle.Radius).background(WidgetStyle.Surface)) {
        val size = LocalSize.current
        when (kind) {
            WidgetKind.OVERVIEW -> OverviewWidgetContent(content, compact = size.width < 260.dp)
            WidgetKind.QUICK_ADD -> QuickAddWidgetContent(content)
            WidgetKind.BUDGET -> BudgetWidgetContent(content)
            WidgetKind.CATEGORIES -> CategoryWidgetContent(content)
        }
    }
}
