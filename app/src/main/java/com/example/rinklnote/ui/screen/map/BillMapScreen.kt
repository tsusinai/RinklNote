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
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas as IconCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.rinklnote.R
import com.example.rinklnote.RinklNoteApp
import com.example.rinklnote.data.db.entity.Bill
import com.example.rinklnote.domain.BillType
import com.example.rinklnote.ui.component.DefaultHazeBackground
import com.example.rinklnote.ui.component.applyCardGlass
import com.example.rinklnote.ui.component.rinkShadow
import com.example.rinklnote.ui.theme.LocalRinklColors
import com.example.rinklnote.util.LocationGrabber
import com.example.rinklnote.util.Money
import com.example.rinklnote.util.SpendGeoProfile
import dev.chrisbanes.haze.HazeState
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

/** 「回到我的位置」标记在 overlays 里的专用 id：重建账单标记时按此豁免，避免定位圆点被误清。 */
private const val MY_LOCATION_MARKER_ID = "my_location"

/** 定位采集超时（毫秒）：首次自动定位与手动按钮共用。 */
private const val LOCATE_TIMEOUT_MS = 5000L

/** 同位置聚合网格：0.0001 度 ≈ 11 米（见 [buildBillMarkers] 注释）。 */
private const val BILL_GRID_DEG = 10_000.0

/**
 * 「账单地图」（路由 `bill-map`）——真实数据版。
 *
 * 取数：[RinklNoteApp] 服务定位器自取全量账单 Flow（`BillDao.observeAll` 已过滤 deleted=0），
 * 内存过滤 latitude/longitude 双非空（只有记账/编辑时点过「位置」的账单才上地图），
 * 再按 ≈11 米网格聚合成标记（见 [buildBillMarkers]）。
 *
 * 流程仍为三态：
 * ① 确认卡：说明「账单地图按消费地点展示账单，需开启定位权限」，「开启」同时请求
 *    ACCESS_FINE_LOCATION + ACCESS_COARSE_LOCATION（[RequestMultiplePermissions]）→
 * ② 地图态：osmdroid [MapView] + 高德瓦片源（[AmapTileSource]，**无需 API key**、国内可直连；
 *    OSM MAPNIK 会封锁 osmdroid 类默认 UA 导致 AccessBlocked，故弃用）。
 *    标记为 Canvas 合成位图：常驻「分类名 ¥金额」椭圆白雾气泡 + 经典水滴大头针
 *    （支出/收入色随收支令牌 expenseColor / incomeColor，标签烧进图标常驻显示，
 *    见 [buildMarkerDrawable]）。点击标记 = 该点平移居中 + [onOpenBill] 打开代表账单
 *    （同位置多笔时为最近一笔，气泡附 ×N）。右下角「回到我的位置」悬浮钮：
 *    [LocationGrabber] 采集一次 → animateTo 并画定位圆点，失败 Toast；
 *    首次进入地图也会自动尝试一次定位（失败保持默认中心，静默降级）。
 *    无带位置账单时地图中央显示半透明空态提示卡。
 * ③ 拒绝态：引导卡（说明 + 「去开启」重试 + 返回）。
 *
 * **坐标系说明**：账单位置三端统一存 WGS-84 原始度值（[LocationGrabber] 直出，无需换算）；
 * 高德底图为 GCJ-02（火星坐标），国内打点会有数百米级视觉偏移——本页直接按原始值打点
 * 不做纠偏（纠偏算法非官方、三端各自实现反而引入不一致），「看消费地点分布」的用途下可接受。
 *
 * 材质沿用 App 既有规范：确认/引导卡 15dp 圆角 + rinkShadow + applyCardGlass，
 * 字阶 18/14/16，页面水平 14dp；有自选背景时卡片透出照片（hazeState 非空即挂毛玻璃能力）。
 *
 * @param backgroundUri 自选背景照片 uri（nav 层已整窗铺满时不再自绘背景）；null = 无照片背景
 * @param hazeState nav 层毛玻璃状态；null = 纯色背景兜底
 * @param onBack 返回（顶栏返回键与「返回」按钮共用）
 * @param onRequestEnable 用户在确认卡点「开启」后的后续动作回调（当前可空实现，由调用方注入）
 * @param onOpenBill 点击账单标记后的打开回调（参数 = 代表账单 id，同位置多笔时为最近一笔）；
 *                    带默认值空实现——导航层未接线前本页可独立编译运行
 * @param focusBillId 定向聚焦：非空时进入地图后首次定位到该账单标记并居中放大
 *                    （供「从账单看位置」类入口传入）；该账单未上地图/无位置时保持默认视野
 */
@Composable
fun BillMapScreen(
    backgroundUri: String?,
    hazeState: HazeState?,
    onBack: () -> Unit,
    onRequestEnable: () -> Unit,
    onOpenBill: (Long) -> Unit = {},
    focusBillId: Long? = null
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

            MapStep.Map -> MapContent(
                onBack = onBack,
                onOpenBill = onOpenBill,
                focusBillId = focusBillId
            )
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
 * 聚合后的地图标记模型（真实账单驱动，替代旧演示点）。
 *
 * @param representativeBillId 代表账单 id：同位置多笔时取**最近一笔**（bills 按日期倒序，
 *                              分组首笔即代表），点击标记即打开它
 * @param position 标记坐标（组内代表账单的经纬度，WGS-84 原始度值）
 * @param label 气泡文案：「分类名 ¥金额（×N）」，金额走 [Money.formatPlain]
 * @param isExpense 支出/收入（随代表账单），决定大头针与文字配色
 * @param count 该位置聚合的账单笔数（>1 时气泡金额后附 ×N）
 */
private data class BillMarkerItem(
    val representativeBillId: Long,
    val position: GeoPoint,
    val label: String,
    val isExpense: Boolean,
    val count: Int
)

/**
 * 带位置账单 → 标记聚合。
 *
 * **同位置多笔的策略（聚合计数）**：分组键 = 经纬度各自四舍五入到 0.0001 度（≈11 米网格）。
 * 不按精确 double 匹配的原因：[LocationGrabber] 每次采集天然带米级抖动，同一店铺连续记账
 * 的两笔几乎不可能得到逐位相同的坐标；11 米内的几笔视为「同一位置」聚成一枚标记，
 * 气泡显示最近一笔的「分类名 ¥金额」并附 ×N，点击打开最近一笔（[BillMarkerItem.representativeBillId]）。
 * 入参 bills 来自 `BillDao.observeAll`（date 倒序），因此遍历首笔即组内最近一笔。
 */
private fun buildBillMarkers(bills: List<Bill>): List<BillMarkerItem> {
    if (bills.isEmpty()) return emptyList()
    val representativeByKey = LinkedHashMap<Pair<Long, Long>, Bill>()
    val countByKey = HashMap<Pair<Long, Long>, Int>()
    bills.forEach { bill ->
        val lat = bill.latitude ?: return@forEach
        val lng = bill.longitude ?: return@forEach
        val key = (lat * BILL_GRID_DEG).roundToLong() to (lng * BILL_GRID_DEG).roundToLong()
        if (!representativeByKey.containsKey(key)) representativeByKey[key] = bill
        countByKey[key] = (countByKey[key] ?: 0) + 1
    }
    return representativeByKey.map { (key, bill) ->
        val count = countByKey.getValue(key)
        BillMarkerItem(
            representativeBillId = bill.id,
            position = GeoPoint(bill.latitude!!, bill.longitude!!),
            label = buildString {
                append(bill.categoryName)
                append(" ¥")
                append(Money.formatPlain(bill.amountMinor))
                if (count > 1) {
                    append(" ×")
                    append(count)
                }
            },
            isExpense = bill.billType == BillType.EXPENSE,
            count = count
        )
    }
}

/** 默认初始视野：上海人民广场一带（无 focusBillId 且自动定位失败时的固定兜底中心）。 */
private val DefaultCenter = GeoPoint(31.2304, 121.4737)

/**
 * 合成标记图标产物：位图 + 针尖纵向锚点（v）——后者交给 [Marker.setAnchor]，
 * 使针尖精确压在坐标点上（气泡再高也不影响定位）。
 * 「我的位置」圆点标记复用本类，此时 tipAnchorV = 0.5（位图正中）。
 */
private class MarkerIcon(val drawable: BitmapDrawable, val tipAnchorV: Float)

/**
 * Canvas 自绘「常驻标签 + 水滴大头针」合成标记图标：把「分类名 ¥金额」椭圆气泡与水滴针
 * 画进同一张位图，标签常驻显示、随缩放平移跟手，且无需任何 InfoWindow 状态管理。
 *
 * 自上而下绘制：① 椭圆白雾气泡底图（复用 [R.drawable.map_bubble_bg]，白雾底 + 1dp
 * DefaultCardBorder 描边，观感与原 InfoWindow 气泡一致）；② 12sp 标签文字（支出/收入
 * 各走收支令牌色），宽度按 measureText 自适应、超长省略号截断；③ 经典水滴大头针
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
    // 防御性截断：文字最宽约 140dp，超出省略号（真实分类名/金额文案较长时也安全）
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

/**
 * Canvas 合成「我的位置」标记图标：经典三层同心圆——外圈淡色光晕（主题令牌色 20% 透明）、
 * 白色描边环、主题色实心圆点；锚点取位图正中（0.5, 0.5，与账单水滴针的针尖锚点不同）。
 */
private fun buildMyLocationIcon(context: Context, themeColorArgb: Int): MarkerIcon {
    val density = context.resources.displayMetrics.density
    val haloR = 21f * density
    val ringR = 12f * density
    val dotR = 6.5f * density
    val side = ceil(haloR * 2f).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val c = side / 2f
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // 光晕：仅替换 alpha 字节为 0x33（20%），保留主题色 RGB
    paint.color = (themeColorArgb and 0x00FFFFFF) or 0x33000000
    canvas.drawCircle(c, c, haloR, paint)
    paint.color = 0xFFFFFFFF.toInt()
    canvas.drawCircle(c, c, ringR, paint)
    paint.color = themeColorArgb
    canvas.drawCircle(c, c, dotR, paint)
    return MarkerIcon(BitmapDrawable(context.resources, bitmap), 0.5f)
}

/**
 * 在地图上放置/更新「我的位置」圆点标记：先移除旧圆点（按 id 识别），再画新的并刷新。
 * 返回新标记实例，供调用方持有以便下一次更新。
 */
private fun placeMyLocationMarker(
    mapView: MapView,
    context: Context,
    previous: Marker?,
    themeColorArgb: Int,
    lat: Double,
    lng: Double
): Marker {
    previous?.let { mapView.overlays.remove(it) }
    val icon = buildMyLocationIcon(context, themeColorArgb)
    val marker = Marker(mapView).apply {
        id = MY_LOCATION_MARKER_ID
        position = GeoPoint(lat, lng)
        setIcon(icon.drawable)
        // 圆点标记以位图正中对准坐标（账单针是针尖锚点，二者不同）
        setAnchor(Marker.ANCHOR_CENTER, icon.tipAnchorV)
    }
    mapView.overlays.add(marker)
    mapView.invalidate()
    return marker
}

/**
 * 地图态：真实带位置账单标记 + 定位聚焦 + 点击开账单。
 *
 * 取数：[RinklNoteApp] 服务定位器自取 `BillRepository.observeAllBills()`（Room 响应式，
 * 记账/删除后地图实时增减标记），内存过滤「经纬度双非空」（DAO 已滤 deleted=0）。
 */
@Composable
private fun MapContent(
    onBack: () -> Unit,
    onOpenBill: (Long) -> Unit,
    focusBillId: Long?
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 主题令牌：支出/收入配色与「我的位置」圆点、悬浮钮都走令牌（自定义主题一键换色生效）
    val rinklColors = LocalRinklColors.current
    val themeColor = rinklColors.themeColor
    val expenseColorArgb = rinklColors.expenseColor.toArgb()
    val incomeColorArgb = rinklColors.incomeColor.toArgb()
    val themeColorArgb = themeColor.toArgb()

    // 取数：全量账单 Flow → 内存过滤带位置账单（observeAll 已按日期倒序、已滤 deleted=0）
    val app = context.applicationContext as RinklNoteApp
    val allBills by remember(app.repository) { app.repository.observeAllBills() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val locatedBills = remember(allBills) {
        allBills.filter { it.latitude != null && it.longitude != null }
    }

    // 聚合成标记（账单增删改 → locatedBills 变化 → 重算，地图实时刷新）
    val markerItems = remember(locatedBills) { buildBillMarkers(locatedBills) }

    // 消费地理画像（Task 4.3）：0.01° 网格热力 + 地点排行（仅主动打点的支出账单）
    val heatCells = remember(locatedBills) { SpendGeoProfile.aggregateGrid(locatedBills) }
    var showRanking by remember { mutableStateOf(false) }

    // 点击回调经 rememberUpdatedState 固定取最新值，避免把 lambda 放进重组 keyed 效应里
    val currentOnOpenBill by rememberUpdatedState(onOpenBill)

    // 「我的位置」圆点标记实例（自动定位与手动按钮共用，更新时先移除旧实例）
    var myLocationMarker by remember { mutableStateOf<Marker?>(null) }

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
        }
    }

    // 标记随账单数据重建：清掉旧账单标记与旧热力圆（「我的位置」圆点按 id 豁免）后整批重画。
    // 个人账单量级小，整批重建开销可忽略；LaunchedEffect 默认跑在主线程，
    // 满足 osmdroid「overlay 只能在主线程操作」的要求。
    LaunchedEffect(mapView, markerItems, heatCells, expenseColorArgb, incomeColorArgb) {
        mapView.overlays.removeAll { (it is Marker && it.id != MY_LOCATION_MARKER_ID) || it is Polygon }
        // 热力/聚合圆点图层（Task 4.3）：先画（压在标记下层），半径随网格支出占比放大。
        // osmdroid 6.1.20 无 Circle overlay，用 Polygon.pointsAsCircle 等价实现；
        // GCJ-02 纠偏 TODO 维持原状：热力是「片区级」观感，数百米偏移不影响看分布。
        val topTotal = heatCells.firstOrNull()?.totalMinor ?: 0L
        heatCells.forEach { cell ->
            val polygon = Polygon(mapView).apply {
                setPoints(Polygon.pointsAsCircle(GeoPoint(cell.cellLat, cell.cellLng), SpendGeoProfile.radiusMeters(cell.totalMinor, topTotal)))
                fillPaint.color = (expenseColorArgb and 0x00FFFFFF) or 0x2E000000.toInt()
                outlinePaint.color = android.graphics.Color.TRANSPARENT
                outlinePaint.strokeWidth = 0f
            }
            mapView.overlays.add(polygon)
        }
        markerItems.forEach { item ->
            val colorArgb = if (item.isExpense) expenseColorArgb else incomeColorArgb
            val markerIcon = buildMarkerDrawable(context, colorArgb, item.label)
            mapView.overlays.add(
                Marker(mapView).apply {
                    // 标记 ↔ 账单关联：id 存代表账单 id，点击时解析回传
                    id = item.representativeBillId.toString()
                    position = item.position
                    setIcon(markerIcon.drawable)
                    setAnchor(Marker.ANCHOR_CENTER, markerIcon.tipAnchorV)
                    setOnMarkerClickListener { marker, targetMap ->
                        // 先把该点平移居中，再回调打开账单（同位置多笔 = 打开最近一笔）
                        targetMap.controller.animateTo(marker.position)
                        marker.id.toLongOrNull()?.let(currentOnOpenBill)
                        true
                    }
                }
            )
        }
        mapView.invalidate()
    }

    // 定向聚焦（focusBillId 优先于自动定位，两个视野不打架）：定位到指定账单标记并居中放大。
    // 等 locatedBills 首次到齐后再尝试一次；目标账单无位置/已删除时保持默认视野（不重试）。
    var focusDone by remember { mutableStateOf(false) }
    LaunchedEffect(mapView, locatedBills) {
        if (focusBillId == null || focusDone || locatedBills.isEmpty()) return@LaunchedEffect
        focusDone = true
        val target = locatedBills.firstOrNull { it.id == focusBillId }
        if (target != null) {
            mapView.controller.setZoom(17.0)
            mapView.controller.animateTo(GeoPoint(target.latitude!!, target.longitude!!))
        }
    }

    // 首次进入地图自动尝试一次定位（focusBillId 已指定时跳过）：成功 → 画「我的位置」圆点
    // 并 animateTo；失败（无权限/无 provider/超时）→ 保持默认中心（上海人民广场一带）
    // 静默降级，不弹 Toast 打扰首次浏览。
    LaunchedEffect(mapView) {
        if (focusBillId != null) return@LaunchedEffect
        val grabbed = LocationGrabber.grab(context, LOCATE_TIMEOUT_MS) ?: return@LaunchedEffect
        val (lat, lng) = grabbed
        myLocationMarker = placeMyLocationMarker(
            mapView, context, myLocationMarker, themeColorArgb, lat, lng
        )
        mapView.controller.animateTo(GeoPoint(lat, lng))
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

        // 空态：还没有任何带位置账单时，地图中央半透明提示卡（文案中文、≥12sp、令牌配色）
        if (markerItems.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 40.dp)
                        .rinkShadow(RoundedCornerShape(15.dp))
                        .clip(RoundedCornerShape(15.dp))
                        // surface 半透明垫底保证瓦片深浅下都可读，再叠 applyCardGlass 雾面
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.66f))
                        .then(applyCardGlass(RoundedCornerShape(15.dp)))
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = "还没有带位置的账单",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = rinklColors.fontColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "记账时点「位置」即可上地图",
                        fontSize = 12.sp,
                        color = rinklColors.fontColor.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // 地点排行（Task 4.3）：左下角胶囊开关 + 展开的排行卡（Top 8 片区，支出合计降序）。
        if (heatCells.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (showRanking) {
                    GeoRankingPanel(cells = heatCells)
                }
                GeoRankingTogglePill(expanded = showRanking, onClick = { showRanking = !showRanking })
            }
        }

        // 右下角「回到我的位置」悬浮钮：点击采集一次定位 → 成功 animateTo 并画定位圆点；
        // 失败 Toast 提示。MyLocation 图标在 material-icons-extended（项目未引入该依赖，
        // 本文件也不新增依赖），故用 Canvas 手绘同款「准星」造型：外环 + 中心实心点 + 四向刻度，
        // 颜色走主题令牌 themeColor（IconCanvas 别名是为了避开 android.graphics.Canvas 重名）。
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 24.dp)
                .rinkShadow(CircleShape)
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface)
                .then(applyCardGlass(CircleShape))
                .clickable {
                    scope.launch {
                        val grabbed = LocationGrabber.grab(context, LOCATE_TIMEOUT_MS)
                        if (grabbed != null) {
                            val (lat, lng) = grabbed
                            myLocationMarker = placeMyLocationMarker(
                                mapView, context, myLocationMarker, themeColorArgb, lat, lng
                            )
                            mapView.controller.animateTo(GeoPoint(lat, lng))
                        } else {
                            Toast.makeText(
                                context, "定位失败，请检查定位权限", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            IconCanvas(modifier = Modifier.size(22.dp)) {
                val stroke = size.minDimension / 13f
                val ringRadius = size.minDimension / 2f * 0.56f
                val tickInner = ringRadius + stroke * 0.7f
                val tickOuter = ringRadius + stroke * 1.7f
                drawCircle(color = themeColor, radius = ringRadius, style = Stroke(width = stroke))
                drawCircle(color = themeColor, radius = stroke * 1.15f)
                listOf(
                    Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f)
                ).forEach { dir ->
                    drawLine(
                        color = themeColor,
                        start = center + dir * tickInner,
                        end = center + dir * tickOuter,
                        strokeWidth = stroke,
                        cap = StrokeCap.Round
                    )
                }
            }
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

/**
 * 「地点排行」面板（Task 4.3 消费地理画像）：0.01° 网格（≈1.1km 片区）聚合的
 * 支出 Top 8。诚实口径：仅统计**主动打点**的支出账单，无地点名（不反向地理编码），
 * 以网格内金额最大的分类代表该片区。
 */
@Composable
private fun GeoRankingPanel(cells: List<SpendGeoProfile.CellRank>) {
    Column(
        modifier = Modifier
            .widthIn(max = 260.dp)
            .rinkShadow(RoundedCornerShape(15.dp))
            .clip(RoundedCornerShape(15.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .then(applyCardGlass(RoundedCornerShape(15.dp)))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = "地点排行",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = "仅统计主动打点的支出账单 · 约 1km 片区聚合",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        cells.take(8).forEachIndexed { index, cell ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${index + 1}. ${cell.topCategory?.takeIf { it.isNotBlank() } ?: "未知分类"}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${Money.formatPlain(cell.totalMinor)} · ${cell.billCount} 笔",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 「地点排行」开合胶囊：默认收起，展开时卡片列在胶囊上方（底部左下角，与定位钮对角）。 */
@Composable
private fun GeoRankingTogglePill(expanded: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .rinkShadow(RoundedCornerShape(18.dp))
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .then(applyCardGlass(RoundedCornerShape(18.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(
            text = if (expanded) "收起排行" else "地点排行",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
