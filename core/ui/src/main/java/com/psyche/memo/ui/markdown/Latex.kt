package com.psyche.memo.ui.markdown

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import ru.noties.jlatexmath.JLatexMathDrawable
import ru.noties.jlatexmath.JLatexMathSplitter

/**
 * 数学公式渲染 —— 原版走 `flutter_math_fork`（`markdown_with_highlight.dart` 的
 * `LatexBlockScrollableMd` / 行内公式组件），Android 侧按 **RikkaHub 的做法**接：
 * `ru.noties:jlatexmath-android`（RikkaHub 维护的 fork，纯 Java）把 LaTeX 渲染成
 * `JLatexMathDrawable`，再画到 Compose 的 Canvas 上（`LatexText.kt` / `MathBlock.kt`
 * 的同构移植）。
 *
 * 两个开关对应显示设置 → 渲染页的两行：
 * - `display_enable_math_rendering_v1` → [MathConfig.enabled]（总开关）；
 * - `display_enable_dollar_latex_v1` → [MathConfig.dollarLatex]（是否把 `$…$` /
 *   `$$…$$` 当公式；关掉时 `$` 只当普通字符，`\(…\)` / `\[…\]` 仍渲染）。
 */
data class MathConfig(
    val enabled: Boolean = true,
    val dollarLatex: Boolean = true,
) {
    companion object {
        /** settings_provider.dart 的两个默认值都是 true。 */
        val DEFAULT = MathConfig()
    }
}

/** 一段文本按行内公式切开后的片段。 */
sealed interface MathSegment {
    data class Plain(val text: String) : MathSegment

    /** [latex] 已去掉定界符。 */
    data class Formula(val latex: String) : MathSegment
}

// 注意：都靠 matchEntire / matches() 表达「整串匹配」，所以不写 ^…$ 锚点
// （Kotlin 原始字符串以 $ 结尾会和模板语法冲突）。
private val DISPLAY_DOLLAR = Regex("""\$\$(.+)\$\$""", RegexOption.DOT_MATCHES_ALL)
private val DISPLAY_BRACKET = Regex("""\\\[(.+)\\\]""", RegexOption.DOT_MATCHES_ALL)

/**
 * 段落整体是一段块级公式时返回**去掉定界符**的公式体，否则 null。
 *
 * 与 RikkaHub 一致：`$$…$$` 与 `\[…\]` 都算块级；`$$` 形式受 dollarLatex 开关约束。
 * 公式体里保留换行（`$$` 跨行写法），渲染层再按需处理。
 */
fun displayMathBody(paragraph: String, config: MathConfig): String? {
    if (!config.enabled) return null
    val trimmed = paragraph.trim()
    if (trimmed.isEmpty()) return null
    if (config.dollarLatex) {
        DISPLAY_DOLLAR.matchEntire(trimmed)?.let { match ->
            return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
        }
    }
    DISPLAY_BRACKET.matchEntire(trimmed)?.let { match ->
        return match.groupValues[1].trim().takeIf { it.isNotEmpty() }
    }
    return null
}

/**
 * 把一行文本按行内公式切开（`$…$` 与 `\(…\)`）。
 *
 * 判定规则（为避免把价格一类文本误判成公式）：
 * - `\$` 转义后原样保留；
 * - `$…$` 不跨行、内容非空、首尾都不是空格、内容里不能再有 `$`；
 * - `$$` 不在这里处理（块级公式归 [displayMathBody]，混排在正文里的 `$$` 保持原样）；
 * - `\(…\)` 只在 [MathConfig.enabled] 打开时切分。
 *
 * 找不到公式时返回单个 [MathSegment.Plain]，调用方无需特判。
 */
fun splitInlineMath(text: String, config: MathConfig): List<MathSegment> {
    if (!config.enabled || text.isEmpty()) return listOf(MathSegment.Plain(text))
    val out = ArrayList<MathSegment>()
    val plain = StringBuilder()
    var i = 0

    fun flushPlain() {
        if (plain.isNotEmpty()) {
            out.add(MathSegment.Plain(plain.toString()))
            plain.setLength(0)
        }
    }

    while (i < text.length) {
        val c = text[i]
        // 转义序列：\$ 原样；\( … \) 作为公式。
        if (c == '\\' && i + 1 < text.length) {
            if (text[i + 1] == '$') {
                plain.append(c).append('$')
                i += 2
                continue
            }
            if (text[i + 1] == '(') {
                val end = text.indexOf("\\)", i + 2)
                if (end > i + 2) {
                    val body = text.substring(i + 2, end)
                    if (body.isNotBlank()) {
                        flushPlain()
                        out.add(MathSegment.Formula(body.trim()))
                        i = end + 2
                        continue
                    }
                }
            }
            plain.append(c)
            i++
            continue
        }
        if (c == '$' && config.dollarLatex) {
            if (text.getOrNull(i + 1) == '$') {
                // `$$…$$` 归块级判定（整段公式）；混排在正文里时整段原样保留，
                // 否则会被当成两个单 `$`，把中间的 `x` 误判成公式。
                val end = text.indexOf("$$", i + 2)
                val stop = if (end >= 0) end + 2 else text.length
                plain.append(text, i, stop)
                i = stop
                continue
            }
            var j = i + 1
            var close = -1
            while (j < text.length && text[j] != '\n') {
                if (text[j] == '$' && text[j - 1] != '\\') {
                    close = j
                    break
                }
                j++
            }
            if (close > i + 1) {
                val body = text.substring(i + 1, close)
                if (body.isNotBlank() &&
                    !body.startsWith(" ") && !body.endsWith(" ") &&
                    !body.contains('$')
                ) {
                    flushPlain()
                    out.add(MathSegment.Formula(body))
                    i = close + 1
                    continue
                }
            }
        }
        plain.append(c)
        i++
    }
    flushPlain()
    return if (out.isEmpty()) listOf(MathSegment.Plain(text)) else out
}

/** RikkaHub `LatexText.kt:20-28` `assumeLatexSize` —— 行内占位符要预先知道尺寸。 */
fun assumeLatexSize(latex: String, fontSizePx: Float): Rect =
    buildLatexDrawable(latex, fontSizePx, android.graphics.Color.BLACK)?.bounds
        ?: Rect(0, 0, 0, 0)

/** RikkaHub `LatexText.kt:80-97` `getLatexDrawable`。失败返回 null（上层回退原文）。 */
internal fun buildLatexDrawable(latex: String, fontSizePx: Float, color: Int): JLatexMathDrawable? =
    runCatching {
        JLatexMathDrawable.builder(processLatex(latex))
            .textSize(fontSizePx)
            .color(color)
            .padding(0)
            .align(JLatexMathDrawable.ALIGN_LEFT)
            .build()
    }.getOrNull()

/**
 * RikkaHub `LatexText.kt:104-115` `splitLatex` —— 过长的行内公式按顶层运算符切成
 * 多段，段间插零宽空格提供换行点，避免整条公式被挤出屏幕。失败返回空列表。
 */
internal fun splitLatex(latex: String, maxWidthPx: Float, fontSizePx: Float, color: Int): List<JLatexMathDrawable> =
    runCatching {
        JLatexMathSplitter.split(processLatex(latex), maxWidthPx, fontSizePx, color)
    }.getOrElse { emptyList() }

private val INLINE_DOLLAR = Regex("""\$(.*?)\$""", RegexOption.DOT_MATCHES_ALL)
private val DISPLAY_DOLLAR_STRIP = Regex("""\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)
private val INLINE_PAREN = Regex("""\\\((.*?)\\\)""", RegexOption.DOT_MATCHES_ALL)
private val DISPLAY_BRACKET_STRIP = Regex("""\\\[(.*?)\\\]""", RegexOption.DOT_MATCHES_ALL)

/** RikkaHub `LatexText.kt:140-157` `processLatex` —— 渲染前剥掉四种定界符。 */
internal fun processLatex(latex: String): String {
    val trimmed = latex.trim()
    return when {
        DISPLAY_DOLLAR_STRIP.matches(trimmed) ->
            DISPLAY_DOLLAR_STRIP.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        INLINE_DOLLAR.matches(trimmed) ->
            INLINE_DOLLAR.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        DISPLAY_BRACKET_STRIP.matches(trimmed) ->
            DISPLAY_BRACKET_STRIP.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        INLINE_PAREN.matches(trimmed) ->
            INLINE_PAREN.find(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed

        else -> trimmed
    }
}

/** RikkaHub `LatexText.kt:30-78` `LatexText`：解析失败回退纯文本。 */
@Composable
fun LatexText(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
    color: Color = Color.Unspecified,
) {
    val base = LocalTextStyle.current
    val contentColor = LocalContentColor.current
    val style: TextStyle = base.copy(
        fontSize = fontSize.takeOrElse { base.fontSize },
        color = if (color != Color.Unspecified) color else contentColor,
    )
    val density = LocalDensity.current
    val drawable = remember(latex, style.fontSize, style.color, density) {
        with(density) {
            runCatching {
                buildLatexDrawable(latex, style.fontSize.toPx(), style.color.toArgb())
            }.getOrNull()
        }
    }
    if (drawable != null) {
        with(density) {
            Canvas(
                modifier = modifier.size(
                    width = drawable.bounds.width().toDp(),
                    height = drawable.bounds.height().toDp(),
                ),
            ) {
                drawable.draw(drawContext.canvas.nativeCanvas)
            }
        }
    } else {
        // 解析失败（非法 LaTeX）：显示原文，别把内容吞掉。
        Text(text = latex, style = style, modifier = modifier)
    }
}

/** RikkaHub `MathBlock.kt:20-30` `MathInline`。 */
@Composable
fun MathInline(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    LatexText(
        latex = latex,
        color = LocalContentColor.current,
        fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
        modifier = modifier,
    )
}

/** RikkaHub `MathBlock.kt:32-53` `MathBlock` —— 居中 + 横向滚动。 */
@Composable
fun MathBlock(
    latex: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = TextUnit.Unspecified,
) {
    Box(modifier = modifier.padding(8.dp)) {
        LatexText(
            latex = latex,
            color = LocalContentColor.current,
            fontSize = fontSize.takeOrElse { LocalTextStyle.current.fontSize },
            modifier = Modifier
                .align(Alignment.Center)
                .horizontalScroll(rememberScrollState()),
        )
    }
}

/** RikkaHub `LatexText.kt:117-133` `LatexDrawable` —— 画拆分后的单段公式。 */
@Composable
fun LatexDrawable(drawable: JLatexMathDrawable, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    with(density) {
        Canvas(
            modifier = modifier.size(
                width = drawable.bounds.width().toDp(),
                height = drawable.bounds.height().toDp(),
            ),
        ) {
            drawable.draw(drawContext.canvas.nativeCanvas)
        }
    }
}
