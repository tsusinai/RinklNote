package com.example.rinklnote.ui.screen.plan

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.RinklDivider
import com.example.rinklnote.ui.component.RinklTopBar
import com.example.rinklnote.ui.component.SettingsGroupCard
import com.example.rinklnote.ui.component.SettingsRow
import com.example.rinklnote.ui.component.rememberRinklTopBarHeight
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.util.categoryIconRes
import com.example.rinklnote.util.Money
import dev.chrisbanes.haze.HazeState

/**
 * 分类预算设置页（独立路由 `budget-categories`，**该路由由主会话接线**）。
 *
 * 列出全部支出分类：每行「分类图标 + 分类名 + 已设预算额（或「未设置」）」，
 * 行高对齐 SettingsRow（48dp）。点击分类行 → [onCategoryClick]（导航层跳该分类的
 * 预算编辑页 `budget-edit`，编辑目标经共享 BudgetViewModel 传递）。
 *
 * 页面自包含：数据全部经参数注入、不依赖 ViewModel，供导航层从 BudgetState 取值传入。
 * 背景处理对齐其他二级页：有自选照片时由 nav 层整窗铺满；无照片时本页自铺
 * [DefaultHazeBackground]（hazeState 未接时内部兜底自建，保证 blur 源可用）。
 * 顶栏为悬浮式：返回键（AutoMirrored ArrowBack 24dp）+ 居中标题「分类预算」20sp Medium。
 *
 * @param categories 全部支出分类（BudgetViewModel.state.expenseCategories）
 * @param existingBudgets 分类已设预算映射（categoryId → 金额分，BudgetState.categoryBudgetAmounts）；
 *   缺失或 0 视为「未设置」
 * @param backgroundUri 自选背景照片 URI；`null` 时本页自铺默认背景
 * @param hazeState 毛玻璃状态；可空——按 `budget-edit` 同款传 `takeIf { 有照片 }` 时无照片为 null，
 *   本页会自建本地 HazeState 兜底
 * @param onBack 返回上一页
 * @param onCategoryClick 点击分类行 → (categoryId, categoryName)，导航层跳 `budget-edit`
 */
@Composable
fun CategoryBudgetScreen(
    categories: List<Category>,
    existingBudgets: Map<Long, Long>,
    backgroundUri: String?,
    hazeState: HazeState?,
    onBack: () -> Unit,
    onCategoryClick: (categoryId: Long, categoryName: String) -> Unit
) {
    // 兜底 blur 源：导航层未透传 hazeState（无照片场景）时自建，避免 DefaultHazeBackground 拿不到状态。
    val effectiveHazeState = hazeState ?: remember { HazeState() }

    val listState = rememberLazyListState()
    val topBarHeight = rememberRinklTopBarHeight()
    val listScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }
    }
    val topBarScrimAlpha by animateFloatAsState(
        targetValue = if (backgroundUri != null || listScrolled) 1f else 0f,
        label = "categoryBudgetTopBarScrim"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // 毛玻璃源：有自选照片时由 nav 层整窗铺满；无照片时本页自铺默认背景。
        if (backgroundUri == null) {
            DefaultHazeBackground(hazeState = effectiveHazeState)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 顶栏是浮层：首项垫到它下面（不留整块空白）。
            item(key = "top-inset") { Spacer(modifier = Modifier.height(topBarHeight)) }

            item(key = "category-list") {
                if (categories.isEmpty()) {
                    // 空态兜底：seed 后一般不会出现，防御首次启动分类未就绪。
                    SettingsGroupCard {
                        Text(
                            text = "暂无支出分类",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 16.dp)
                        )
                    }
                } else {
                    SettingsGroupCard(title = "支出分类") {
                        categories.forEachIndexed { index, category ->
                            val amount = existingBudgets[category.id]?.takeIf { it > 0 }
                            SettingsRow(
                                icon = categoryIconRes(category.name),
                                // 分类图标为彩色矢量，禁用 tint 保持原色。
                                iconTint = Color.Unspecified,
                                label = category.name,
                                value = amount?.let { Money.format(it) } ?: "未设置",
                                onClick = { onCategoryClick(category.id, category.name) }
                            )
                            if (index != categories.lastIndex) {
                                RinklDivider()
                            }
                        }
                    }
                }
            }

            item(key = "bottom-spacer") { Spacer(modifier = Modifier.height(16.dp)) }
        }

        // 悬浮顶栏：返回 + 居中标题（对齐月度详情页）。
        CategoryBudgetTopBar(
            scrimAlpha = topBarScrimAlpha,
            hasPhoto = backgroundUri != null,
            listScrolled = listScrolled,
            onBack = onBack
        )
    }
}

/** 分类预算设置页顶栏：返回（AutoMirrored ArrowBack 24dp）+ 居中标题「分类预算」20sp Medium。 */
@Composable
private fun CategoryBudgetTopBar(
    scrimAlpha: Float,
    hasPhoto: Boolean,
    listScrolled: Boolean,
    onBack: () -> Unit
) {
    // 文字色三态：有背景→白；无背景→自定义主题「顶栏标题色」，滚动后略淡。
    val textColor = when {
        hasPhoto -> Color.White
        !listScrolled -> LocalRinklColors.current.topBarTitleColor
        else -> LocalRinklColors.current.topBarTitleColorScrolled
    }
    RinklTopBar(
        scrimAlpha = scrimAlpha,
        horizontalPadding = 8.dp
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = "分类预算",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
