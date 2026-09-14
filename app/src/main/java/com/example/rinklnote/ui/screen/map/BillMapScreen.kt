package com.example.rinklnote.ui.screen.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.BitmapDrawable
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.rinklnote.R
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.IncomeGreen
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.util.Money
import dev.chrisbanes.haze.HazeState
import java.io.File
import kotlin.math.ceil
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker

/**
 * 「账单地图」预实现（路由 `bill-map` 由主会话接线，对应目标 9）。
 *
 * 现实约束：bills 表**尚无位置字段（lat/lng）**，无真实账单地点数据——本页按三态流程预实现：
 * ① 确认卡：说明「账单地图按消费地点展示账单，需开启定位权限」，「开启」同时请求
 *    ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION（[RequestMultiplePermissions]）→
 * ② 地图态：osmdroid [MapView] + 高德瓦片源（[AmapTileSource]，**无需 API key**、国内可直连；
 *    OSM MAPNIK 会封锁 osmdroid 类默认 UA 导致 AccessBlocked，故弃用），演示标记为
 *    Canvas 合成位图：常驻「分类名 ¥金额」椭圆白雾气泡 + 经典水滴大头针（支出 tertiary 红 /
 *    收入 IncomeGreen），标签直接烧进标记图标，无需点击即始终显示（见 [buildMarkerDrawable]）；
 * ③ 拒绝态：引导卡（说明 + 「去开启」重试 + 返回）。
 *
 * **坐标系说明**：高德底图为 GCJ-02（火星坐标），与 WGS-84 存在数百米级偏移；
 * 演示标记阶段可接受，**接入真实账单位置时需 WGS-84→GCJ-02 纠偏**后再打点。
 *
 * **后续接入点**：bills 表加位置字段后，①删掉 [DemoBillPlaces] 演示标记，改为按账单
 * 聚合真实标记（含 WGS-84→GCJ-02 纠偏）；②初始视野改为按聚合点的包围盒定位
 * （或用户最近一次消费地点）。
 *
 * 材质沿用 App 既有规范：确认/引导卡 15dp 圆角 + rinkShadow + applyCardGlass，
 * 字阶 18/14/16，页面水平 14dp；有自选背景时卡片透出照片（hazeState 非空即挂毛玻璃能力）。
 *
 * @param backgroundUri 自选背景照片 uri（nav 层已整窗铺满时不再自绘背景）；null = 无照片背景
 * @param hazeState nav 层毛玻璃状态；null = 纯色背景兜底
 * @param onBack 返回（顶栏返回键与「返回」按钮共用）
 * @param onRequestEnable 用户在确认卡点「开启」后的后续动作回调（当前可空实现，由调用方注入）
 */
@Composable
fun BillMapScreen(
    backgroundUri: String?,
    hazeState: HazeState?,
    onBack: () -> Unit,
    onRequestEnable: () -> Unit
) {
    val context = LocalContext.current

    // 已授权（任一定位权限）则跳过确认卡直接进地图；仅首次进入走「确认卡 → 授权」流程
    val hasPermission = remember {
        context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
    var step by remember { mutableStateOf(if (hasPermission) MapStep.Map else MapStep.Confirm) }

    // 定位权限申请：任一授权即视为成功（粗略定位也能满足「看消费地点分布」的用途）
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        step = if (result.values.any { it }) MapStep.Map else MapStep.Denied
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景：有照片时由 nav 层整窗铺满；否则纯白底注册为毛玻璃源（与「导入账单」页一致）。
        // 地图态整屏被 MapView 盖住，此背景只服务于确认卡/引导卡两个状态。
        if (backgroundUri == null) {
            val hs = hazeState
            if (hs != null) {
                DefaultHazeBackground(hazeState = hs)
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                )
            }
        }

        when (step) {
            MapStep.Confirm -> ConfirmCard(
                hasBackground = backgroundUri != null,
                onEnable = {
                    // 确认开启后的后续动作（当前空实现，由调用方注入）
                    onRequestEnable()
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                onDecline = onBack
            )

            MapStep.Denied -> DeniedCard(
                hasBackground = backgroundUri != null,
                onRetry = {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                onBack = onBack
            )

            MapStep.Map -> MapContent(onBack = onBack)
        }
    }
}

/** 页面内部三态：确认卡 → 地图 / 拒绝引导。 */
private enum class MapStep { Confirm, Map, Denied }

// ---------------------------------------------------------------------------
// ① 确认卡 / ③ 拒绝引导卡
// ---------------------------------------------------------------------------

@Composable
private fun ConfirmCard(
    hasBackground: Boolean,
    onEnable: () -> Unit,
    onDecline: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        MapPageTopBar(hasBackground = hasBackground, onBack = onDecline)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            StepCard(
                hasBackground = hasBackground,
                title = "账单地图",
                body = "账单地图按消费地点展示账单，需开启定位权限。开启后即可在地图上查看你的消费地点分布。",
                confirmLabel = "开启",
                onConfirm = onEnable,
                dismissLabel = "暂不",
                onDismiss = onDecline
            )
        }
    }
}

@Composable
private fun DeniedCard(
    hasBackground: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        MapPageTopBar(hasBackground = hasBackground, onBack = onBack)
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            StepCard(
                hasBackground = hasBackground,
                title = "定位权限未开启",
                body = "未获得定位权限，无法在地图上标记消费地点。可重新发起授权；若此前选择了「不再询问」，请前往系统设置手动开启。",
                confirmLabel = "去开启",
                onConfirm = onRetry,
                dismissLabel = "返回",
                onDismiss = onBack
            )
        }
    }
}

/** 确认/引导共用卡：15dp 圆角玻璃卡，居中展示，标题 18sp + 说明 14sp + 底部按钮行。 */
@Composable
private fun StepCard(
    hasBackground: Boolean,
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .then(applyCardGlass(RoundedCornerShape(15.dp)))
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) {
                Text(
                    text = dismissLabel,
                    fontSize = 16.sp,
                    color = if (hasBackground) Color.White
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = onConfirm) {
                Text(text = confirmLabel, fontSize = 16.sp)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// ② 地图态
// ---------------------------------------------------------------------------

/**
 * 高德瓦片源：修复 OSM MAPNIK 对 osmdroid 类默认 UA 的 AccessBlocked 封锁，
 * 同时改善国内加载速度。webrd01~04 四台公共瓦片服务器，[XYTileSource] 经
 * getBaseUrl() 内部随机轮询分发；zoom 3-19、瓦片 256px、.png 后缀；
 * UA 已由 Configuration 统一设为应用包名，此处无需额外处理。
 *
 * 覆写说明：osmdroid 默认按「baseUrl + z/x/y.png」路径拼接瓦片地址（不支持 {x} 占位符），
 * 而高德 appmaptile 是查询参数式 URL，故覆写 [getTileURLString] 改为
 * 「baseUrl（含 lang/size/scale/style 业务参数）+ &x=…&y=…&z=…」拼接。
 */
private val AmapTileSource: XYTileSource = object : XYTileSource(
    "AmapTiles", 3, 19, 256, ".png",
    arrayOf(
        "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7",
        "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7",
        "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7",
        "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String = buildString {
        append(getBaseUrl())
        append("&x=").append(MapTileIndex.getX(pMapTileIndex))
        append("&y=").append(MapTileIndex.getY(pMapTileIndex))
        append("&z=").append(MapTileIndex.getZoom(pMapTileIndex))
    }
}

/**
 * 演示标记数据：**bills 表位置字段（lat/lng）接入前的占位**。
 * 接入后删除本列表，替换为真实账单按地点聚合的标记（见文件头注释「后续接入点」）。
 *
 * 坐标系说明：高德底图为 GCJ-02（火星坐标），与 WGS-84 存在偏移——演示标记阶段可接受，
 * **接入真实账单位置时需 WGS-84→GCJ-02 纠偏**后再打点。
 *
 * @param isExpense true = 支出（大头针/标签字色走 tertiary 红），false = 收入（IncomeGreen）
 */
private data class DemoBillPlace(
    val lat: Double,
    val lng: Double,
    val category: String,
    val amountMinor: Long,
    val isExpense: Boolean
)

private val DemoBillPlaces = listOf(
    DemoBillPlace(31.2304, 121.4737, "午餐", 2800L, isExpense = true),
    DemoBillPlace(31.2230, 121.4400, "地铁", 400L, isExpense = true),
    DemoBillPlace(31.2245, 121.4890, "超市", 9600L, isExpense = true),
    DemoBillPlace(31.2600, 121.4300, "兼职", 15000L, isExpense = false),
    DemoBillPlace(31.2380, 121.4560, "红包", 888L, isExpense = false)
)

/** 默认演示城市：上海人民广场一带（无定位数据时的固定初始视野）。 */
private val DefaultCenter = GeoPoint(31.2304, 121.4737)

/**
 * 合成标记图标产物：位图 + 针尖纵向锚点（v）——后者交给 [Marker.setAnchor]，
 * 使针尖精确压在坐标点上（气泡再高也不影响定位）。
 */
private class MarkerIcon(val drawable: BitmapDrawable, val tipAnchorV: Float)

/**
 * Canvas 自绘「常驻标签 + 水滴大头针」合成标记图标：把「分类名 ¥金额」椭圆气泡与水滴针
 * 画进同一张位图，标签常驻显示、随缩放平移跟手，且无需任何 InfoWindow 状态管理。
 *
 * 自上而下绘制：① 椭圆白雾气泡底图（复用 [R.drawable.map_bubble_bg]，白雾底 + 1dp
 * DefaultCardBorder 描边，观感与原 InfoWindow 气泡一致）；② 12sp 标签文字（支出 tertiary 红 /
 * 收入 IncomeGreen），宽度按 measureText 自适应、超长省略号截断；③ 经典水滴大头针
 * （大头圆弧 + 两条切线收拢到针尖 + 中心白点），颜色随收支类型。
 *
 * 尺寸按当前屏幕密度绘制（12sp 字号随系统字体缩放换算 px），保证各分辨率下观感一致；
 * 气泡与针均水平居中，气泡底与针位图顶相接——针头圆弧顶部自身内缩约 4.3dp，即原
 * InfoWindow 时代气泡与针的视觉间隙。
 *
 * 锚点计算：针尖位于针区 96% 高度处（防针尖抗锯齿被位图边缘裁切），故
 * v = 针尖纵坐标 / 位图实际高（约 0.97，随气泡高度浮动），u 恒为水平居中（针尖在位图中线上）。
 */
private fun buildMarkerDrawable(context: Context, colorArgb: Int, label: String): MarkerIcon {
    val metrics = context.resources.displayMetrics
    val density = metrics.density

    // —— ② 标签文字：12sp 单行（TextPaint 供 TextUtils.ellipsize 使用）——
    val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, metrics)
        color = colorArgb
    }
    // 防御性截断：文字最宽约 140dp，超出省略号（演示标签很短，防真实接入后的备注级长文本）
    val text = TextUtils.ellipsize(
        label, textPaint, 140f * density, TextUtils.TruncateAt.END
    ).toString()
    val textWidth = textPaint.measureText(text)
    val fontMetrics = textPaint.fontMetrics
    val textHeight = fontMetrics.descent - fontMetrics.ascent

    // —— 气泡几何：内边距对齐原 InfoWindow 气泡（10dp 水平 / 5dp 垂直）——
    val bubblePadH = 10f * density
    val bubblePadV = 5f * density
    val bubbleWidth = textWidth + 2 * bubblePadH
    val bubbleHeight = textHeight + 2 * bubblePadV

    // —— 大头针几何：沿用原水滴针（34x46dp，针尖位于针区 96% 高度处）——
    val pinWidth = 34f * density
    val pinHeight = 46f * density

    // —— 整体布局：气泡在上、针在下，均水平居中，气泡底与针位图顶相接 ——
    // 位图尺寸向上取整，避免针尖浮点坐标被截断裁掉
    val totalWidth = maxOf(bubbleWidth, pinWidth)
    val totalHeight = bubbleHeight + pinHeight
    val bitmap = Bitmap.createBitmap(
        ceil(totalWidth).toInt().coerceAtLeast(1),
        ceil(totalHeight).toInt().coerceAtLeast(1),
        Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)

    // ① 气泡底图：按「文字宽 + 内边距」撑出的边界绘制（白雾/描边/圆角由 drawable 自带）
    val bubbleLeft = (totalWidth - bubbleWidth) / 2f
    ContextCompat.getDrawable(context, R.drawable.map_bubble_bg)?.let { bubble ->
        bubble.setBounds(
            bubbleLeft.toInt(), 0,
            (bubbleLeft + bubbleWidth).toInt(), bubbleHeight.toInt()
        )
        bubble.draw(canvas)
    }
    // ② 标签文字：水平居中；基线 = 顶边距 + 文字 ascent 绝对值（ascent 为负），垂直居中于气泡
    canvas.drawText(text, (totalWidth - textWidth) / 2f, bubblePadV - fontMetrics.ascent, textPaint)

    // ③ 水滴轮廓：从圆左下切点起，沿大头圆弧（绕过顶部）到右下切点，再收拢到针尖；
    // 切点角 = asin(radius / 针尖距)，保证两段直线与圆弧相切、轮廓平滑无折角
    val pinLeft = (totalWidth - pinWidth) / 2f
    val radius = pinWidth * 0.36f
    val cx = pinLeft + pinWidth / 2f
    val cy = bubbleHeight + pinHeight * 0.36f
    val tipY = bubbleHeight + pinHeight * 0.96f
    val drop = Path()
    val tipDistance = (tipY - cy) / radius
    val tangentAngle = Math.toDegrees(Math.asin(1.0 / tipDistance)).toFloat()
    drop.arcTo(
        cx - radius, cy - radius, cx + radius, cy + radius,
        180f - tangentAngle, 180f + 2f * tangentAngle, false
    )
    drop.lineTo(cx, tipY)
    drop.close()

    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colorArgb }
    canvas.drawPath(drop, paint)

    // 经典大头针的中心白点（字面量 0xFFFFFFFF，避免与 Compose Color 导入冲突）
    paint.color = 0xFFFFFFFF.toInt()
    canvas.drawCircle(cx, cy, radius * 0.42f, paint)

    // 针尖纵向锚点：v = 针尖 y / 位图实际高（u 恒为水平居中），交给 Marker.setAnchor
    return MarkerIcon(BitmapDrawable(context.resources, bitmap), tipY / bitmap.height)
}

@Composable
private fun MapContent(onBack: () -> Unit) {
    val context = LocalContext.current

    // 收支配色一次性转为 android 层 ARGB int（大头针与常驻标签文字共用）：
    // 支出 = tertiary（亮色主题即 ExpenseRed #CA3032），收入 = IncomeGreen #04A433
    val expenseColor = MaterialTheme.colorScheme.tertiary.toArgb()
    val incomeColor = IncomeGreen.toArgb()

    // osmdroid 初始化只做一次：UA 必须在首个 MapView 创建前设置——OSM 官方源封锁
    // osmdroid 类默认 UA（AccessBlocked 的根源），改走高德瓦片后沿用应用包名 UA 即可；
    // 瓦片缓存落到应用 cache 目录，避开 Android 10+ 分区存储下默认外部路径不可写的问题。
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = context.packageName
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        MapView(context).also { map ->
            // 换用高德瓦片源：国内可直连、不受 MAPNIK 封锁影响（替代 TileSourceFactory.MAPNIK）
            map.setTileSource(AmapTileSource)
            map.setMultiTouchControls(true)
            // 隐藏 osmdroid 自带缩放按钮（+/-），走双指捏合手势
            map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            map.controller.setZoom(11.0)
            map.controller.setCenter(DefaultCenter)

            // 演示标记（占位）：账单位置字段接入后替换为真实聚合数据（需 WGS-84→GCJ-02 纠偏）。
            // 「分类名 ¥金额」标签常驻合成进标记位图，无需点击展开、无 InfoWindow 状态管理；
            // 针尖对准坐标点：v 锚点取针尖在合成位图中的纵向比例、u 恒为水平居中
            // （计算方式见 buildMarkerDrawable 注释）。点击标记把该点平移居中（返回 true 消费触摸）
            DemoBillPlaces.forEach { place ->
                val colorArgb = if (place.isExpense) expenseColor else incomeColor
                val label = "${place.category} ${Money.format(place.amountMinor)}"
                val markerIcon = buildMarkerDrawable(context, colorArgb, label)
                map.overlays.add(
                    Marker(map).apply {
                        position = GeoPoint(place.lat, place.lng)
                        icon = markerIcon.drawable
                        setAnchor(Marker.ANCHOR_CENTER, markerIcon.tipAnchorV)
                        setOnMarkerClickListener { marker, targetMap ->
                            targetMap.controller.animateTo(marker.position)
                            true
                        }
                    }
                )
            }
        }
    }

    // 生命周期对齐：进出页面启停瓦片下载调度，卸载时解绑（预实现阶段不做 SqlTileWriter 深清理）
    DisposableEffect(Unit) {
        mapView.onResume()
        onDispose {
            mapView.onPause()
            mapView.onDetach()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())

        // 顶部悬浮返回：ArrowBack 图章钮 + 20sp Medium 标题，配色对齐其他页顶栏；
        // 地图瓦片深浅不定，给 surface 圆底兜底可读性
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .rinkShadow(CircleShape)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .then(applyCardGlass(CircleShape))
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "账单地图",
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = LocalRinklColors.current.topBarTitleColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/** 确认卡/引导卡态的悬浮顶栏：ArrowBack + 居中标题「账单地图」（20sp Medium），对齐「导入账单」页。 */
@Composable
private fun MapPageTopBar(hasBackground: Boolean, onBack: () -> Unit) {
    val textColor = if (hasBackground) Color.White else LocalRinklColors.current.topBarTitleColor
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onBack
                )
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "返回",
                tint = textColor,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = "账单地图",
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}
