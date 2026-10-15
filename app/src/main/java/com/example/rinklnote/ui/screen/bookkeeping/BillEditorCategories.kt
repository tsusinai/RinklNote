package com.example.rinklnote.ui.screen.bookkeeping

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.example.rinklnote.R
import com.example.rinklnote.data.db.entity.Category
import com.example.rinklnote.data.db.entity.SubCategory
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.ui.component.RinklDialog
import com.example.rinklnote.ui.component.RinklTextButton
import com.example.rinklnote.ui.component.motionClickable
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.Motion
import com.example.rinklnote.ui.theme.billCategoryIconColor
import com.example.rinklnote.ui.util.categoryIconRes
import com.example.rinklnote.ui.util.subcategoryIconRes

/** 主分类与细分各占一条横向列表，当前选项自动露出，全部分类入口仍可直达。 */
@Composable
internal fun BillCategoryEditor(
    categories: List<Category>,
    selectedCategory: Category?,
    subCategories: List<SubCategory>,
    selectedSubCategory: SubCategory?,
    onCategoryClick: (Category) -> Unit,
    onSubCategoryClick: (SubCategory?) -> Unit
) {
    var showAll by remember(categories) { mutableStateOf(false) }
    val categoryState = rememberLazyListState()
    val fontScale = LocalDensity.current.fontScale
    LaunchedEffect(categories, selectedCategory?.id) {
        val index = categories.indexOfFirst { it.id == selectedCategory?.id }
        if (index >= 0 && categoryState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            categoryState.animateScrollToItem(index)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("账单分类", style = BillEditorDesign.SectionTitle, color = MaterialTheme.colorScheme.onSurface)
            Row(Modifier.heightIn(min = 32.dp).motionClickable(enabled = categories.isNotEmpty(),
                role = Role.Button, onClickLabel = "选择全部分类") { showAll = true }.padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("全部", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                    modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(4.dp))
        LazyRow(Modifier.fillMaxWidth(), state = categoryState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(categories, key = { it.id }) { category ->
                EditorCategoryOption(category, selectedCategory?.id == category.id,
                    Modifier.width((60 + category.name.length * 14 * fontScale).coerceIn(112f, 180f).dp)) {
                    onCategoryClick(category)
                }
            }
        }
        AnimatedContent(targetState = subCategories,
            transitionSpec = { fadeIn(Motion.EditorFade).togetherWith(fadeOut(Motion.EditorFade))
                .using(SizeTransform { _, _ -> Motion.EditorResize }) }, label = "细分选项切换") { subs ->
            if (subs.isNotEmpty()) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(8.dp))
                    SubcategoryHeading(selectedCategory?.name.orEmpty(), selectedSubCategory?.name)
                    Spacer(Modifier.height(4.dp))
                    val subcategoryState = rememberLazyListState()
                    val options = listOf<SubCategory?>(null) + subs
                    LaunchedEffect(subs, selectedSubCategory?.id) {
                        val index = options.indexOfFirst { it?.id == selectedSubCategory?.id }
                        if (index >= 0 && subcategoryState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                            subcategoryState.animateScrollToItem(index)
                        }
                    }
                    LazyRow(Modifier.fillMaxWidth(), state = subcategoryState,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(options, key = { it?.id ?: 0L }) { sub ->
                            val label = sub?.name ?: "不细分"
                            EditorSubcategoryOption(selectedCategory?.name.orEmpty(), label,
                                selectedSubCategory?.id == sub?.id,
                                Modifier.width((64 + label.length * 13 * fontScale).coerceIn(104f, 180f).dp)) {
                                onSubCategoryClick(sub)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showAll) {
        AllBillCategoriesDialog(categories, selectedCategory, onDismiss = { showAll = false }) {
            showAll = false
            onCategoryClick(it)
        }
    }
}

/** 分类图标保留语义色，蓝色描边和勾选只表达选中状态。 */
@Composable
private fun EditorCategoryOption(category: Category, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = LocalRinklColors.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.30f), Motion.SelectColor, label = "分类选中底色")
    val shape = RoundedCornerShape(BillEditorDesign.ChipRadius)
    Row(modifier.heightIn(min = BillEditorDesign.TouchHeight).clip(shape).background(background)
        .border(1.dp, if (selected) colors.themeColor else Color.Transparent, shape)
        .semantics(mergeDescendants = true) { this.selected = selected }
        .clickable(role = Role.RadioButton, onClickLabel = "选择分类", onClick = onClick)
        .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(categoryIconRes(category.name)), contentDescription = null,
            tint = colors.billCategoryIconColor(category.name, dark), modifier = Modifier.size(26.dp))
        Text(category.name, fontSize = 14.sp, lineHeight = 18.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (selected) EditorSelectionMark() else Spacer(Modifier.size(16.dp))
    }
}

@Composable
private fun SubcategoryHeading(categoryName: String, subcategoryName: String?) {
    Row(Modifier.fillMaxWidth().heightIn(min = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(14.dp)) {
            Box(Modifier.width(2.dp).height(14.dp).background(LocalRinklColors.current.themeColor, RoundedCornerShape(1.dp)))
            Box(Modifier.align(Alignment.BottomStart).width(11.dp).height(2.dp)
                .background(LocalRinklColors.current.themeColor, RoundedCornerShape(1.dp)))
        }
        Text(categoryName + "细分", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(categoryName + " / " + (subcategoryName ?: "不细分"), style = BillEditorDesign.Secondary,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

@Composable
private fun EditorSubcategoryOption(categoryName: String, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(BillEditorDesign.ChipRadius)
    val background by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.24f), Motion.SelectColor, label = "细分选中底色")
    Row(modifier.heightIn(min = BillEditorDesign.TouchHeight).clip(shape).background(background)
        .border(1.dp, if (selected) LocalRinklColors.current.themeColor.copy(alpha = 0.65f) else Color.Transparent, shape)
        .semantics(mergeDescendants = true) { this.selected = selected }
        .clickable(role = Role.RadioButton, onClickLabel = "选择细分分类", onClick = onClick)
        .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(subcategoryIconRes(categoryName, label)), contentDescription = null,
            modifier = Modifier.size(18.dp), tint = LocalRinklColors.current.billCategoryIconColor(categoryName,
                MaterialTheme.colorScheme.background.luminance() < 0.5f))
        Text(label, fontSize = 13.sp, lineHeight = 18.sp, textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (selected) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer) else Spacer(Modifier.size(16.dp))
    }
}

/** 勾选只做视觉标识，读屏统一读取选项的 selected 状态。 */
@Composable
internal fun EditorSelectionMark() {
    val primary = LocalRinklColors.current.themeColor
    Box(Modifier.size(16.dp).clip(CircleShape).background(primary), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(12.dp),
            tint = if (primary.luminance() > 0.4f) Color.Black else Color.White)
    }
}

/** 全部分类独立滚动，打开即露出当前分类，点选后回到原编辑表单。 */
@Composable
private fun AllBillCategoriesDialog(categories: List<Category>, selectedCategory: Category?,
    onDismiss: () -> Unit, onSelect: (Category) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(selectedCategory?.id) {
        val index = categories.indexOfFirst { it.id == selectedCategory?.id }
        if (index >= 0) listState.scrollToItem(index)
    }
    RinklDialog(onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            shape = RoundedCornerShape(BillEditorDesign.GroupRadius), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(if (categories.firstOrNull()?.billType == BillType.INCOME) "全部收入分类" else "全部支出分类",
                        style = BillEditorDesign.SectionTitle)
                    RinklTextButton(onClick = onDismiss) { Text("关闭") }
                }
                LazyRow(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().height(BillEditorDesign.TouchHeight + 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    items(categories, key = { it.id }) { category ->
                        EditorCategoryOption(category, selectedCategory?.id == category.id,
                            Modifier.width((60 + category.name.length * 14).coerceIn(112, 180).dp)) {
                            onSelect(category)
                        }
                    }
                }
            }
        }
    }
}
