package com.psyche.memo.ui.markdown

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import com.psyche.memo.highlight.CodeHighlighter
import com.psyche.memo.highlight.HighlightTextColorPalette
import com.psyche.memo.highlight.buildHighlightText

/**
 * 代码块语法高亮 —— 高亮引擎是照 RikkaHub 的 `:highlight` 模块 1:1 搬过来的
 * （pure Kotlin，语法移植自 highlight.js 11.11.1，带 hljs 金标夹具测试），
 * 调色板与选择方式也照 RikkaHub `ui/theme/CodeColor.kt` + `HighlightCodeBlock.kt:99`
 * （`if (darkMode) AtomOneDarkPalette else AtomOneLightPalette`）。
 *
 * 为什么之前没有：我们原来代码块是**纯等宽文本**（没有高亮）——
 * 原版 `markdown_with_highlight.dart` 有高亮，且带流式上限（`:107-108` 的
 * 300 行 / 12000 字，`:2825-2828` 使用）。现在两样都有了。
 */

/** Atom One Dark（逐字照 RikkaHub `CodeColor.kt:7-23`）。 */
internal val AtomOneDarkPalette = HighlightTextColorPalette(
    keyword = Color(0xFFc678dd),
    string = Color(0xFF98c379),
    number = Color(0xFFd19a66),
    comment = Color(0xFF5c6370),
    function = Color(0xFF61afef),
    operator = Color(0xFF56b6c2),
    punctuation = Color(0xFFabb2bf),
    className = Color(0xFFe5c07b),
    property = Color(0xFFe06c75),
    boolean = Color(0xFFd19a66),
    variable = Color(0xFFe06c75),
    tag = Color(0xFFe06c75),
    attrName = Color(0xFFd19a66),
    attrValue = Color(0xFF98c379),
    fallback = Color(0xFFabb2bf),
)

/** Atom One Light（逐字照 RikkaHub `CodeColor.kt:26-42`）。 */
internal val AtomOneLightPalette = HighlightTextColorPalette(
    keyword = Color(0xFFa626a4),
    string = Color(0xFF50a14f),
    number = Color(0xFFc18401),
    comment = Color(0xFF9ca0a4),
    function = Color(0xFF4078f2),
    operator = Color(0xFF0184bc),
    punctuation = Color(0xFF383a42),
    className = Color(0xFFc18401),
    property = Color(0xFFe45649),
    boolean = Color(0xFFc18401),
    variable = Color(0xFFe45649),
    tag = Color(0xFFe45649),
    attrName = Color(0xFFc18401),
    attrValue = Color(0xFF50a14f),
    fallback = Color(0xFF383a42),
)

/** 原版流式上限（`markdown_with_highlight.dart:107-108`）。 */
internal const val CODE_HIGHLIGHT_MAX_LINES = 300
internal const val CODE_HIGHLIGHT_MAX_CHARS = 12000

/**
 * 这段代码要不要做语法高亮 —— 逐字照原版上限：超过 300 行或 12000 字就**不高亮**
 * （高亮是 O(代码量) 的 UI 线程工作，长代码块在流式输出时最费）。
 */
internal fun shouldHighlightCode(
    code: String,
    maxLines: Int = CODE_HIGHLIGHT_MAX_LINES,
    maxChars: Int = CODE_HIGHLIGHT_MAX_CHARS,
): Boolean {
    if (code.isEmpty()) return false
    if (code.length > maxChars) return false
    var lines = 1
    for (ch in code) {
        if (ch == '\n') {
            lines++
            if (lines > maxLines) return false
        }
    }
    return true
}

/**
 * 高亮器单例：`CodeHighlighter()` 构造时要装配 ~30 个语言的语法，**不能每次重组都建**。
 */
private val sharedCodeHighlighter by lazy { CodeHighlighter() }

/**
 * 把代码渲染成带高亮的 [AnnotatedString]；超上限或语言不支持时退回纯文本。
 * 调用方负责 `remember`（key 用 code/language/palette）。
 */
@Composable
internal fun rememberHighlightedCode(
    code: String,
    language: String?,
    modifierKey: Any? = null,
    highlight: Boolean = true,
): AnnotatedString {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val palette = if (dark) AtomOneDarkPalette else AtomOneLightPalette
    return remember(code, language, palette, modifierKey, highlight) {
        if (!highlight || !shouldHighlightCode(code)) {
            AnnotatedString(code)
        } else {
            buildAnnotatedString {
                sharedCodeHighlighter.highlight(code, language.orEmpty()).forEach { token ->
                    buildHighlightText(token, palette)
                }
            }
        }
    }
}
