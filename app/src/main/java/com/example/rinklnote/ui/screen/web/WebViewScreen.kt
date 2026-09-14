package com.example.rinklnote.ui.screen.web

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.rinklnote.ui.component.RinklTopBarContentHeight
import com.example.rinklnote.ui.theme.LocalRinklColors

/**
 * 应用内 WebView 屏：在 App 内打开网页（如 QQ 机器人绑定引导页），不甩外部浏览器。
 *
 * 路由 `web-view?url={url}` 由导航层接线，[url] 为 URL 编码后的地址，
 * 调用方还需传入顶栏标题（如「QQ 机器人引导」）与返回回调。
 *
 * 背景处理：内部网页不受背景照片 / 毛玻璃模式影响，这里保持纯色背景即可。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebScreen(
    url: String,
    title: String,
    onBack: () -> Unit
) {
    val colors = LocalRinklColors.current

    // 加载进度 0~100，由 WebChromeClient.onProgressChanged 驱动；超过 95 视为完成并隐藏进度条
    var progress by remember { mutableIntStateOf(0) }
    // 持有 WebView 引用：返回键判断 canGoBack、销毁时释放
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // 返回键优先回退网页历史，无历史可退时才交给导航层关闭页面
    BackHandler {
        val webView = webViewRef
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            onBack()
        }
    }

    // 页面销毁时停止加载并释放 WebView，避免 ActivityContext 泄漏与后台继续播放音视频
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.stopLoading()
            webViewRef?.destroy()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 顶部悬浮返回栏：与其他二级页同节奏（状态栏让位 + 46dp 内容高 + 8dp 水平边距）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(RinklTopBarContentHeight)
                .padding(horizontal = 8.dp)
        ) {
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.topBarTitleColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = colors.topBarTitleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 加载进度条：仅在加载中（1~95）显示
        if (progress in 1..95) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .navigationBarsPadding(),
            factory = { context ->
                WebView(context).apply {
                    // 站内跳转留在本 WebView，不甩外部浏览器
                    webViewClient = WebViewClient()
                    // 加载进度回调驱动顶部进度条
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                        }
                    }
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // 记录本次已加载的路由地址，供 update 判断参数是否真正变化
                    tag = url
                    if (url.isNotBlank()) loadUrl(url)
                }.also { webViewRef = it }
            },
            update = { webView ->
                // 同一返回栈条目内路由参数变化时重新加载（正常导航会新建条目，一般不会走到）
                if (webView.tag != url) {
                    webView.tag = url
                    if (url.isNotBlank()) webView.loadUrl(url)
                }
            }
        )
    }
}
