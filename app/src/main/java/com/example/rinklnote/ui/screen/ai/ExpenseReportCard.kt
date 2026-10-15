package com.example.rinklnote.ui.screen.ai

import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import com.example.rinklnote.data.network.dto.ExpenseReportResponse
import com.example.rinklnote.report.ReportHtml
import com.example.rinklnote.ui.component.RinklDialog
import com.example.rinklnote.ui.component.RinklIconButton
import com.example.rinklnote.ui.component.RinklTextButton
import com.example.rinklnote.ui.component.RinklTopBar
import com.example.rinklnote.ui.theme.LocalRinklColors
import java.io.ByteArrayInputStream

/** 报告消息占满可用宽度；长报告可展开，历史文字消息仍由原气泡显示。 */
@Composable
internal fun ExpenseReportCard(report: ExpenseReportResponse, onContentReady: (WebView) -> Unit = {}) {
    var expanded by remember(report) { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f))) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(report.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(if (report.source == "llm") "AI 页面" else "基础报告", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${report.month} · ${report.expenseCount} 笔支出", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ReportWebView(report, preview = true, modifier = Modifier.fillMaxWidth(), onReady = onContentReady)
        RinklTextButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("展开报告") }
    }
    if (expanded) {
        RinklDialog(onDismissRequest = { expanded = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                    RinklTopBar(scrimAlpha = 0f, horizontalPadding = 8.dp) {
                        RinklIconButton(onClick = { expanded = false }, modifier = Modifier.align(Alignment.CenterStart)) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "收起报告")
                        }
                        Text(
                            text = report.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                    ReportWebView(report, preview = false, modifier = Modifier.weight(1f).fillMaxWidth(), onReady = onContentReady)
                }
            }
        }
    }
}

/** 隔离的静态 HTML 阅读器：不加载远程资源，不开放脚本、文件访问、导航或原生桥。 */
@Composable
internal fun ReportWebView(
    report: ExpenseReportResponse,
    preview: Boolean,
    modifier: Modifier = Modifier,
    onReady: (WebView) -> Unit = {}
) {
    val palette = MaterialTheme.colorScheme
    val colors = LocalRinklColors.current
    val primary = colors.themeColor
    val fontScale = LocalDensity.current.fontScale
    val doc = remember(report.html, palette, colors, fontScale) {
        reportDocument(report.html, palette.onSurface.css(), palette.onSurfaceVariant.css(),
            palette.surface.css(), palette.surfaceVariant.css(), primary.css(), colors.expenseColor.css(), colors.incomeColor.css(), fontScale)
    }
    var contentHeight by remember(report.html) { mutableStateOf(180.dp) }
    val heightCallback by rememberUpdatedState<(Int) -> Unit>({ contentHeight = it.dp.coerceIn(80.dp, 300.dp) })
    val contentReadyCallback by rememberUpdatedState(onReady)
    AndroidView(
        modifier = (if (preview) modifier.height(contentHeight) else modifier)
            .semantics { contentDescription = "${report.title}：${report.plainText}" },
        factory = { context -> WebView(context).apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.blockNetworkLoads = true
            settings.blockNetworkImage = true
            settings.setSupportMultipleWindows(false)
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = !preview
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                @Suppress("DEPRECATION")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?) =
                    WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.postVisualStateCallback(0L, object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            if (!view.isAttachedToWindow || view.contentHeight <= 0) return
                            heightCallback(view.contentHeight)
                            // 等待预览高度重排和下一帧绘制，避免内容尚未显示时发布完成状态。
                            view.postOnAnimation { view.postOnAnimation { if (view.isAttachedToWindow) contentReadyCallback(view) } }
                        }
                    })
                }
            }
        } },
        onReset = null,
        onRelease = { it.stopLoading(); it.removeAllViews(); it.destroy() },
        update = { view ->
            if (view.tag != doc) {
                view.tag = doc
                view.loadDataWithBaseURL("https://reports.rinklnote.invalid/", doc, "text/html", "UTF-8", null)
            }
        }
    )
}

private fun Color.css(): String = "rgba(%d,%d,%d,%.3f)".format(java.util.Locale.US,
    (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(), alpha)

internal fun reportDocument(html: String, text: String, muted: String, surface: String, panel: String,
    primary: String, expense: String, income: String, fontScale: Float): String {
    val clean = ReportHtml.clean(html) ?: "<p>报告暂时无法显示，请重新生成。</p>"
    return """
        <!doctype html><html lang="zh-CN"><head><meta charset="utf-8" />
        <meta name="viewport" content="width=device-width,initial-scale=1" />
        <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; script-src 'none'; base-uri 'none'; form-action 'none'; frame-src 'none'" />
        <style>
        :root{--text:$text;--muted:$muted;--surface:$surface;--panel:$panel;--primary:$primary;--expense:$expense;--income:$income;--divider:$panel;font-size:${14 * fontScale}px}
        *{box-sizing:border-box}html,body{margin:0;padding:0;background:transparent;color:var(--text)}
        body{font-family:system-ui,sans-serif;font-size:1rem;line-height:1.65;overflow-wrap:anywhere}
        #llm-report{padding:6px 16px 16px;max-width:100%}main,section,article{min-width:0}
        h1,h2,h3{line-height:1.35;margin:18px 0 8px;font-weight:600}h1{font-size:1.65rem}h2{font-size:1.2rem}h3{font-size:1.05rem}
        h1:first-child,h2:first-child{margin-top:0}p{margin:8px 0}ul,ol{padding-left:20px;margin:8px 0}li{margin:6px 0}
        .muted,.label,.note,small{color:var(--muted);font-size:.9em}
        .grid,.stats-grid{display:flex;flex-wrap:wrap;gap:10px}.grid>*,.stats-grid>*{flex:1;min-width:100px}
        .metric,.kpi,.stat-card,.highlight,.warning,.insight-card{background:var(--panel);border-radius:14px;padding:14px;margin:10px 0}
        .amount{font-size:2.1rem;line-height:1.2;font-weight:600;font-variant-numeric:tabular-nums}
        .highlight{border-left:3px solid var(--primary)}.warning{border-left:3px solid var(--expense)}
        .expense,.trend-up{color:var(--expense)}.income,.trend-down{color:var(--income)}strong{font-weight:600}
        .badge{display:inline-block;border-radius:8px;padding:3px 8px;background:var(--panel);font-size:.82rem}
        .category-row,.legend-item{display:flex;justify-content:space-between;align-items:center;gap:8px;margin:10px 0}
        .progress-track{height:7px;border-radius:6px;background:var(--panel);overflow:hidden;margin:6px 0 14px}
        .progress-fill{height:100%;border-radius:6px;background:var(--primary)}
        figure{margin:16px 0}figcaption{color:var(--muted);font-size:.85rem;margin-top:6px}
        svg{display:block;width:100%;max-width:100%;height:auto}svg text{font-family:system-ui,sans-serif}
        table{width:100%;border-collapse:collapse;table-layout:fixed;font-size:.92rem}
        td,th{padding:9px 4px;text-align:left;vertical-align:top;overflow-wrap:anywhere;border-bottom:1px solid var(--divider)}
        td:not(:first-child),th:not(:first-child){text-align:right;font-variant-numeric:tabular-nums}
        hr{border:0;border-top:1px solid var(--divider);margin:18px 0}
        </style></head><body><div id="llm-report">$clean</div></body></html>
    """.trimIndent()
}
