package com.example.rinklnote.ui.screen.ai

import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.rinklnote.ui.theme.RinklNoteTheme
import com.example.rinklnote.report.ReportHtml
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.rinklnote.data.network.dto.ExpenseReportResponse
import java.io.File
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 使用平台仪器与无障碍节点，避开旧 Espresso 对 API 36 私有输入方法的依赖。 */
@RunWith(AndroidJUnit4::class)
class ExpenseReportRenderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val report = ExpenseReportResponse("expense-report-v1", "analysis", "支出分析", "2026-10",
        """<!doctype html><html><head><style>
        .hero{background:var(--panel);border-radius:16px;padding:18px}
        .eyebrow{color:var(--muted);font-size:.85rem}.hero h1{font-size:1.4rem;margin:6px 0 14px}
        .hero .amount{color:var(--expense);font-size:2.4rem}
        .report-stats{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin:14px 0}
        .report-stats .metric{margin:0;padding:12px}.stat-value{font-size:1.2rem;font-weight:600}
        .report-section{margin:22px 0}.chart-panel{background:var(--panel);border-radius:14px;padding:12px}
        </style></head><body><main>
        <header class="hero"><span class="eyebrow">2026年10月 · 支出月报</span><h1>本月支出一览</h1><div class="amount">¥23.00</div><p class="muted">三餐是本月的主要支出。</p></header>
        <div class="report-stats"><div class="metric"><div class="label">支出笔数</div><div class="stat-value">3 笔</div></div><div class="metric"><div class="label">有支出日的日均</div><div class="stat-value">¥11.50</div></div></div>
        <section class="report-section"><h2>支出结构</h2><div class="category-row"><strong>三餐</strong><span>¥23.00 · 100%</span></div><div class="progress-track"><div class="progress-fill" style="width:100%"></div></div><p class="note">当前记录均来自三餐分类。</p></section>
        <section class="report-section"><h2>每日支出趋势</h2><figure class="chart-panel"><svg viewBox="0 0 340 140" role="img" aria-label="10月1日3元，10月2日20元"><title>每日支出趋势</title><line x1="24" y1="112" x2="314" y2="112" stroke="var(--divider)" /><polyline points="24,100 314,24" stroke="var(--primary)" stroke-width="3" fill="none" stroke-linecap="round" /><circle cx="24" cy="100" r="4" fill="var(--primary)" /><circle cx="314" cy="24" r="4" fill="var(--primary)" /><text x="24" y="130" font-size="11" fill="var(--muted)">10.01</text><text x="284" y="130" font-size="11" fill="var(--muted)">10.02</text><text x="260" y="17" font-size="12" fill="var(--text)">¥20.00</text></svg><figcaption>统计范围：有支出记录的日期</figcaption></figure></section>
        <section class="highlight"><h3>一个值得留意的波动</h3><p>10月2日支出 ¥20.00，高于有支出日日均的1.5倍，可结合当日计划核对。</p><p class="note">这只是支出波动的观察提示。</p></section>
        <section class="report-section"><h2>分类汇总</h2><table><thead><tr><th>分类</th><th>金额</th><th>占比</th></tr></thead><tbody><tr><td>三餐</td><td>¥23.00</td><td>100%</td></tr></tbody></table></section>
        <section><h2>接下来可以试试</h2><ul><li>持续记录，积累更多日期再观察消费规律。</li><li>结合原定计划核对支出较高的一天。</li></ul></section>
        <footer class="note">仅基于分类与日期聚合统计生成</footer>
        </main></body></html>""",
        "本月三餐是主要支出", "llm", 2300L, 3, false)
    private fun activity(): ComponentActivity {
        // Android 的正则实现与 JVM 不同，先验证完整页面不会误入降级提示。
        val clean = requireNotNull(ReportHtml.clean(report.html)) { "完整 HTML 在 Android 上校验失败" }
        assertTrue(clean.contains("<style>")); assertTrue(clean.contains("<svg"))
        return instrumentation.startActivitySync(
        Intent().setClassName(instrumentation.targetContext.packageName, ComponentActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as ComponentActivity
    }

    @Test fun `静态报告完成加载且禁用主动内容`() {
        val activity = activity()
        var web: WebView? = null
        try {
            instrumentation.runOnMainSync { activity.setContent {
                RinklNoteTheme(darkTheme = false) { Surface(Modifier.fillMaxSize()) { ReportWebView(report, preview = false, modifier = Modifier.fillMaxSize().safeDrawingPadding(), onReady = { web = it }) } }
            } }
            await { var ready = false; instrumentation.runOnMainSync { ready = (web?.contentHeight ?: 0) > 0 }; ready }
            instrumentation.runOnMainSync {
                assertFalse(web!!.settings.javaScriptEnabled)
                assertFalse(web!!.settings.allowFileAccess)
                assertFalse(web!!.settings.allowContentAccess)
                assertTrue(web!!.settings.blockNetworkLoads)
                assertTrue(web!!.contentHeight > 0)
            }
            screenshot("html-report-render.png")
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test fun `报告可以展开并在应用内收起`() {
        val activity = activity()
        var web: WebView? = null
        try {
            instrumentation.runOnMainSync { activity.setContent {
                RinklNoteTheme(darkTheme = false) { Surface(Modifier.fillMaxSize()) { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(14.dp)) { ExpenseReportCard(report, onContentReady = { web = it }) } } }
            } }
            await { var ready = false; instrumentation.runOnMainSync { ready = (web?.contentHeight ?: 0) > 0 }; ready }
            screenshot("html-report-preview.png")
            val preview = web
            click("展开报告")
            await { var ready = false; instrumentation.runOnMainSync { ready = web !== preview && (web?.contentHeight ?: 0) > 0 }; ready }
            await { find("收起报告") != null }
            screenshot("html-report-expanded.png")
            instrumentation.runOnMainSync { web!!.pageDown(true) }
            await { var scrolled = false; instrumentation.runOnMainSync { scrolled = web!!.scrollY > 0 && !web!!.canScrollVertically(1) }; scrolled }
            await { find("接下来可以试试") != null }
            screenshot("html-report-bottom.png")
            click("收起报告")
            await { find("展开报告") != null }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    private fun click(label: String) {
        await {
            var node = find(label)
            while (node != null && !node.isClickable) node = node.parent
            node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        }
    }
    private fun find(label: String): AccessibilityNodeInfo? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        val nodes = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let(nodes::add)
        }
        return null
    }
    private fun await(check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!check()) {
            if (SystemClock.uptimeMillis() >= deadline) fail("等待报告画面超时")
            SystemClock.sleep(50)
        }
        instrumentation.waitForIdleSync()
    }
    private fun screenshot(name: String) {
        val target = File(instrumentation.targetContext.getExternalFilesDir("validation"), name)
        target.parentFile!!.mkdirs()
        target.outputStream().use { requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
