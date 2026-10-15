package com.example.rinklnote.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.ui.theme.billCategoryIconColor
import com.example.rinklnote.ui.util.categoryIconRes

/** 首页、月度明细与拖动浮层共用：彩色分类线条图标配同色浅圆底。 */
@Composable
fun BillCategoryIcon(
    categoryName: String,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val color = LocalRinklColors.current.billCategoryIconColor(categoryName, dark)
    val iconName = when (categoryName) {
        "餐饮" -> "三餐"
        "购物" -> "网购"
        "出行" -> "交通"
        else -> categoryName
    }
    Box(
        modifier.size(size).clip(CircleShape).background(color.copy(alpha = if (dark) 0.18f else 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        Icon(painterResource(categoryIconRes(iconName)), contentDescription = null,
            tint = color, modifier = Modifier.size(size * 0.56f))
    }
}
