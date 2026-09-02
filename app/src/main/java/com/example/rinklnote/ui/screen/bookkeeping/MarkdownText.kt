package com.example.rinklnote.ui.screen.bookkeeping

import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** 轻量、无第三方依赖的 markdown 渲染器。仅覆盖本月 AI 总结需要的子集：
 *  `#/##/###` 标题、`-`/`*`/`+` 无序列表、`1.` 有序列表、`**粗体**`、`*斜体*`。
 *  颜色/字号/行高由外层 TextStyle 统一设定，内联 span 只覆盖粗细/斜体/标题字号。 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize: TextUnit = 12.sp,
    lineHeight: TextUnit = 16.sp
) {
    BasicText(
        text = renderMarkdown(text),
        modifier = modifier,
        style = TextStyle(color = color, fontSize = fontSize, lineHeight = lineHeight)
    )
}

private fun renderMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    val lines = text.trim().lines()
    lines.forEachIndexed { index, raw ->
        val line = raw.trimEnd()
        when {
            line.startsWith("### ") -> heading(line.substring(4), level = 3)
            line.startsWith("## ") -> heading(line.substring(3), level = 2)
            line.startsWith("# ") -> heading(line.substring(2), level = 1)
            line.startsWith("- ") || line.startsWith("* ") || line.startsWith("+ ") ->
                bullet(line.substring(2))
            isOrdered(line) -> ordered(line)
            else -> appendInline(line)
        }
        if (index != lines.lastIndex) append("\n")
    }
}

private fun isOrdered(line: String): Boolean =
    Regex("""^\d+[.)]\s+\S+""").matches(line)

private fun AnnotatedString.Builder.heading(content: String, level: Int) {
    val size = when (level) {
        1 -> 15.sp
        2 -> 14.sp
        else -> 13.sp
    }
    withStyle(SpanStyle(fontSize = size, fontWeight = FontWeight.Bold)) {
        appendInline(content)
    }
}

private fun AnnotatedString.Builder.bullet(content: String) {
    append("• ")
    appendInline(content)
}

private fun AnnotatedString.Builder.ordered(line: String) {
    val match = Regex("""^(\d+[.)])\s+(.*)$""").find(line)
    if (match != null) {
        append(match.groupValues[1] + " ")
        appendInline(match.groupValues[2])
    } else {
        appendInline(line)
    }
}

/** 内联样式：解析 `**bold**`、`*italic*`。`_` 不处理（避免误伤普通文本）。 */
private fun AnnotatedString.Builder.appendInline(text: String) {
    var pos = 0
    while (pos < text.length) {
        when {
            text.startsWith("**", pos) -> {
                val end = text.indexOf("**", pos + 2)
                if (end == -1) {
                    append(text.substring(pos))
                    pos = text.length
                } else {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(pos + 2, end))
                    }
                    pos = end + 2
                }
            }
            text[pos] == '*' -> {
                val end = text.indexOf('*', pos + 1)
                if (end == -1 || end == pos + 1) {
                    append(text.substring(pos))
                    pos = text.length
                } else {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(pos + 1, end))
                    }
                    pos = end + 1
                }
            }
            else -> {
                val next = text.indexOf('*', pos)
                val end = if (next == -1) text.length else next
                append(text.substring(pos, end))
                pos = end
            }
        }
    }
}
