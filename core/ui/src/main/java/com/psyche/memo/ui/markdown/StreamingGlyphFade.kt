package com.psyche.memo.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import kotlinx.coroutines.delay

/**
 * 流式正文的**逐字渐显**（照 Agora `ui/chat/message/StreamingGlyphFade.kt`）。
 *
 * 干什么：刚吐出来的那几个字偏淡，约 0.5 秒内变实（[STREAM_TAIL_ALPHA_PER_SECOND] = 2f）。
 * 实现只做一件事 —— 在**已经渲染好的** [AnnotatedString] 尾部若干码点上叠一层前景色 alpha。
 * 不动字号、不动行高、不缩放、不改布局高度。
 *
 * **为什么不复用卡片那套入场**（`ui/chat/GenerationLifecycleMotion`，alpha + scale 420ms）：
 * 在 Agora 里这是**两个正交机制** —— 卡片/图片用 `graphicsLayer` 一次性入场，正文用逐字淡入。
 * 我们先前把前者也安到了正文块上，观感变成「一坨一坨往外冒」，缩放还让字在动画里大小变化
 * （用户 2026-09-24「渐显没弄到位」）。
 *
 * **性能契约**：[rememberStreamFadeClock] 只在流式期间、只在尾部那一个段落 leaf 里跑。
 * 挪到消息行等于每 40ms 重组合整条消息，正撞 `ChatRowRecompositionTest` 守的那条线。
 */

/** 每秒推进的不透明度（2f ⇒ 约 0.5 秒从全淡到全实）。 */
const val STREAM_TAIL_ALPHA_PER_SECOND = 2f

/** 淡入时钟的步长（毫秒）—— 与 Agora 同值。 */
const val STREAM_TAIL_FADE_TICK_MS = 40L

/** 淡入的下限：刚出生的字不是完全透明，否则像漏了一截（Agora 亦留底）。 */
const val STREAM_TAIL_MIN_ALPHA = 0.05f

/**
 * 「每个码点第一次出现的时刻」。
 *
 * 只认**前缀增长**：流式追加时新字符拿当前时刻，老字符的出生时刻不变（一旦变实就永远变实）。
 * 文本不是旧文本的前缀（可视改写、重新生成）时当整段是新内容 —— 宁可重播一次淡入，
 * 也不要把没有出生记录的字当成全实（那样会看到半截突然变亮）。
 */
class StreamTailFadeTracker {

    private var previous: String = ""
    private var births: LongArray = LongArray(0)

    /** 返回与 [text] 的码点数等长的出生表（单位 ms，见 `SystemClock.uptimeMillis`）。 */
    fun birthTimes(text: String, nowMs: Long): LongArray {
        val codePoints = text.codePointCount(0, text.length)
        if (codePoints == 0) {
            previous = text
            births = LongArray(0)
            return births
        }
        val carried = if (previous.isNotEmpty() && text.startsWith(previous)) {
            minOf(births.size, codePoints)
        } else {
            0
        }
        val next = LongArray(codePoints)
        births.copyInto(next, destinationOffset = 0, startIndex = 0, endIndex = carried)
        for (index in carried until codePoints) next[index] = nowMs
        previous = text
        births = next
        return next
    }
}

/** 某个码点此刻的不透明度：出生即最低，线性爬到 1；没有出生记录的一律算全实。 */
fun streamTailAlpha(
    birthMs: Long,
    nowMs: Long,
    alphaPerSecond: Float = STREAM_TAIL_ALPHA_PER_SECOND,
): Float = if (birthMs <= 0L) {
    1f
} else {
    (((nowMs - birthMs) * alphaPerSecond) / 1000f).coerceIn(STREAM_TAIL_MIN_ALPHA, 1f)
}

/**
 * 给已渲染文本的尾部叠前景 alpha；全实时原样返回（不产生新对象、不多一次重组的差别）。
 *
 * Compose 的 span 只能整体替换颜色，所以这里按 Agora 的做法用调用方传进来的**正文前景色**
 * 乘 alpha：淡入窗口里内联代码/链接会短暂显成正文色（窗口只有几十个字，不值得为它冒
 * 逐 span 改写颜色的险）。
 */
fun AnnotatedString.withStreamTailFade(
    birthMs: LongArray,
    nowMs: Long,
    color: Color,
    alphaPerSecond: Float = STREAM_TAIL_ALPHA_PER_SECOND,
): AnnotatedString {
    if (text.isEmpty() || birthMs.isEmpty()) return this
    // 从后往前找到还在淡的最早那个码点；全实就直接返回。
    var tailStart = minOf(birthMs.size, text.codePointCount(0, text.length)) - 1
    while (tailStart >= 0 && streamTailAlpha(birthMs[tailStart], nowMs, alphaPerSecond) >= 1f) tailStart--
    if (tailStart < 0) return this
    val tail = tailSpans(tailStart, birthMs, nowMs, color, alphaPerSecond)
    if (tail.isEmpty()) return this
    // Builder.append 把原有的 span/段落样式/标注整体搬过来，再往尾部叠淡入 span ——
    // 自己重建 AnnotatedString 会漏掉不认识的字段（citation 胶囊、行内公式占位符都在里面）。
    return AnnotatedString.Builder().apply {
        append(this@withStreamTailFade)
        tail.forEach { addStyle(it.item, it.start, it.end) }
    }.toAnnotatedString()
}

/** 把相邻且 alpha 相近的码点合成一个 span（量化到 0.05 一档，span 数少一个量级）。 */
private fun AnnotatedString.tailSpans(
    tailStart: Int,
    birthMs: LongArray,
    nowMs: Long,
    color: Color,
    alphaPerSecond: Float,
): List<AnnotatedString.Range<SpanStyle>> {
    val out = ArrayList<AnnotatedString.Range<SpanStyle>>()
    var runStartCp = tailStart
    var runAlpha = streamTailAlpha(birthMs[tailStart], nowMs, alphaPerSecond)
    var index = tailStart + 1
    while (index <= birthMs.size) {
        val alpha = if (index < birthMs.size) {
            streamTailAlpha(birthMs[index], nowMs, alphaPerSecond)
        } else {
            2f // 收尾：把最后一段刷进去
        }
        if (index == birthMs.size || kotlin.math.abs(alpha - runAlpha) > 0.05f) {
            out += AnnotatedString.Range(
                SpanStyle(color = color.copy(alpha = runAlpha)),
                charIndex(runStartCp),
                charIndex(index.coerceAtMost(birthMs.size)),
            )
            runStartCp = index
            runAlpha = alpha
        }
        index++
    }
    return out.filter { it.end > it.start && it.item.color.alpha < 1f }
}

/**
 * 第 [codePoint] 个码点对应的 char 下标（越界给末尾）。
 *
 * 不用 `String.indexByCodePoints`：它按**码点单位**索引，与这里要的「第几个码点」差一个
 * 基准，实测会把 span 起点落到代理对上（emoji 半个亮半个暗）。这里自己走一遍更直白。
 */
private fun AnnotatedString.charIndex(codePoint: Int): Int {
    if (codePoint <= 0) return 0
    var seen = 0
    var index = 0
    while (index < text.length && seen < codePoint) {
        index += Character.charCount(text.codePointAt(index))
        seen++
    }
    return index
}

/**
 * 流式尾部的淡入作用域。
 *
 * 由消息行提供、只包住**正在生成的那条消息的最后一个正文块**（Agora 用
 * `LocalStreamingGlyphFadeSpec` 的等价物）。走 CompositionLocal 而不是给
 * `MarkdownText / MarkdownBody / MarkdownNode` 各加一个参数：这三个函数有四处递归调用点，
 * 加参数会把「谁在淡入」这件事撒满整个渲染器。
 */
class StreamTailFadeScope(val tracker: StreamTailFadeTracker)

val LocalStreamTailFade = compositionLocalOf<StreamTailFadeScope?> { null }

/**
 * 淡入时钟：只在 [active]（正在流式、且这一格是活的尾部）时按 40ms 推进。
 * 不激活时不刷新，文本停在最后一次读到的时刻 —— 出生表随文本一起消失，不留半透明。
 */
@Composable
fun rememberStreamFadeClock(active: Boolean): Long {
    var now by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            now = android.os.SystemClock.uptimeMillis()
            delay(STREAM_TAIL_FADE_TICK_MS)
        }
    }
    return now
}
