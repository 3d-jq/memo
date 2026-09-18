package com.psyche.memo.provider.chart

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import kotlin.math.abs

/**
 * 模型手写 SVG 的**消毒 + 校验**（`render_svg` 工具，自研）。
 *
 * 为什么不能直接落盘：SVG 是「图」也是「文档」—— 里面能塞脚本、外部引用、
 * `<foreignObject>`（内嵌任意 HTML）。我们只当静态图渲染（coil → AndroidSVG，
 * 不执行 JS），但仍然：
 *  1. **删掉** `<script>` / `<foreignObject>` 整棵子树；
 *  2. **删掉** `on*` 事件属性、`javascript:` 值、以及 http(s)/`//` 开头的外部 `href`
 *     （不联网、不引用外部资源）；
 *  3. 要求根节点是 `<svg>` 且带 `viewBox` 或 `width`+`height`（否则没法布局）；
 *  4. 限制体积（[MAX_BYTES]）。
 *
 * 做法是**用 XmlPullParser 重建文档**（白名单式拷贝），不是正则替换 —— 正则改标记
 * 很容易漏（属性大小写、引号、注释里的伪装）。
 *
 * 失败一律返回 [Result.Error]，message 是**给模型看的**（它会改了重试）。
 */
object SvgSanitizer {

    const val MAX_BYTES = 256 * 1024

    /** 垂直/水平极端长宽比会让卡片变成一条线，直接拒绝。 */
    private const val MIN_ASPECT = 0.15f
    private const val MAX_ASPECT = 8f

    sealed interface Result {
        data class Ok(val svg: String, val aspectRatio: Float) : Result
        data class Error(val message: String) : Result
    }

    private val DROPPED_ELEMENTS = setOf("script", "foreignobject")

    fun sanitize(raw: String): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result.Error("svg must not be empty")
        if (trimmed.length > MAX_BYTES) {
            return Result.Error(
                "svg is too large (${trimmed.length / 1024}KB); keep it under ${MAX_BYTES / 1024}KB",
            )
        }
        if (!trimmed.contains("<svg", ignoreCase = true)) {
            return Result.Error("svg must contain a root <svg> element")
        }
        val out = StringBuilder(trimmed.length)
        var rootSeen = false
        var skipDepth = 0
        // 自闭合标签（`<rect/>`）XmlPullParser 会先给 isEmptyElementTag=true 的 START_TAG、
        // **再补一个 END_TAG** —— 两边都写就会输出 `<rect/></rect>`（非法 XML，
        // AndroidSVG 直接解析失败 → 图渲染不出来）。用计数器吞掉那个补来的 END_TAG。
        var swallowedEndTags = 0
        var aspect: Float? = null
        try {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(StringReader(trimmed))
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name
                        when {
                            skipDepth > 0 -> skipDepth++
                            name.lowercase() in DROPPED_ELEMENTS -> skipDepth = 1
                            else -> {
                                val isRoot = !rootSeen
                                if (isRoot && !name.equals("svg", ignoreCase = true)) {
                                    return Result.Error(
                                        "the root element must be <svg>, found <$name>",
                                    )
                                }
                                out.append('<').append(name)
                                var hasViewBox = false
                                var width: Float? = null
                                var height: Float? = null
                                for (i in 0 until parser.attributeCount) {
                                    val attrName = parser.getAttributeName(i)
                                    val value = normalizeValue(
                                        attrName,
                                        parser.getAttributeValue(i) ?: "",
                                    )
                                    if (!isAttributeAllowed(attrName, value)) continue
                                    when (attrName.lowercase()) {
                                        "viewbox" -> hasViewBox = true
                                        "width" -> width = number(value)
                                        "height" -> height = number(value)
                                    }
                                    out.append(' ').append(attrName).append("=\"")
                                        .append(escapeAttribute(value)).append('"')
                                }
                                if (isRoot) {
                                    rootSeen = true
                                    aspect = aspectOf(parser, hasViewBox, width, height)
                                        ?: return Result.Error(
                                            "the root <svg> needs a viewBox (or width and height) " +
                                                "so it can be laid out",
                                        )
                                }
                                if (parser.isEmptyElementTag) {
                                    out.append("/>")
                                    swallowedEndTags++
                                } else {
                                    out.append('>')
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when {
                            skipDepth > 0 -> skipDepth--
                            swallowedEndTags > 0 -> swallowedEndTags--
                            else -> out.append("</").append(parser.name).append('>')
                        }
                    }
                    XmlPullParser.TEXT, XmlPullParser.CDSECT -> {
                        if (skipDepth == 0) out.append(escapeText(parser.text.orEmpty()))
                    }
                    XmlPullParser.ENTITY_REF -> {
                        if (skipDepth == 0) out.append('&').append(parser.name).append(';')
                    }
                }
                event = parser.next()
            }
        } catch (e: Exception) {
            return Result.Error("svg is not well-formed XML: ${e.message ?: e::class.simpleName}")
        }
        if (!rootSeen) return Result.Error("svg must contain a root <svg> element")
        val ratio = aspect ?: return Result.Error(
            "the root <svg> needs a viewBox (or width and height) so it can be laid out",
        )
        if (ratio < MIN_ASPECT || ratio > MAX_ASPECT) {
            return Result.Error(
                "svg aspect ratio ${"%.2f".format(ratio)} is too extreme; " +
                    "keep it between $MIN_ASPECT and $MAX_ASPECT (width / height)",
            )
        }
        return Result.Ok(svg = out.toString(), aspectRatio = ratio)
    }

    /** 根节点的宽高比：优先 `viewBox`，退到 `width`/`height`；都拿不到就 null。 */
    private fun aspectOf(
        parser: XmlPullParser,
        hasViewBox: Boolean,
        width: Float?,
        height: Float?,
    ): Float? {
        if (hasViewBox) {
            for (i in 0 until parser.attributeCount) {
                if (!parser.getAttributeName(i).equals("viewBox", ignoreCase = true)) continue
                val parts = parser.getAttributeValue(i).orEmpty()
                    .trim().split(Regex("[\\s,]+"))
                if (parts.size == 4) {
                    val w = parts[2].toFloatOrNull()
                    val h = parts[3].toFloatOrNull()
                    if (w != null && h != null && w > 0f && h > 0f) return w / h
                }
            }
        }
        val w = width ?: 0f
        val h = height ?: 0f
        return if (w > 0f && h > 0f) w / h else null
    }

    /**
     * 归一化 AndroidSVG 认不出的取值。
     *
     * `orient` 只接受 `auto` 或数字，而模型（跟着 SVG2 文档）爱写
     * `auto-start-reverse` —— 那会让解析直接抛异常、**整张图都渲染不出来**
     *（2026-09-18「文件是好的但渲染不出来」的真凶）。降级成 `auto` 至少箭头方向是对的。
     */
    private fun normalizeValue(name: String, value: String): String {
        if (!name.equals("orient", ignoreCase = true)) return value
        val trimmed = value.trim()
        if (trimmed.equals("auto", ignoreCase = true)) return "auto"
        if (trimmed.toFloatOrNull() != null) return trimmed
        return "auto"
    }

    /** 事件属性 / 脚本协议 / 外部引用一律不放行。 */
    private fun isAttributeAllowed(name: String, value: String): Boolean {
        val lower = name.lowercase()
        if (lower.startsWith("on")) return false
        val normalized = value.trim().lowercase()
        if (normalized.startsWith("javascript:") || normalized.startsWith("data:text/html")) {
            return false
        }
        if (lower == "href" || lower == "xlink:href" || lower.endsWith(":href")) {
            if (normalized.startsWith("http:") ||
                normalized.startsWith("https:") ||
                normalized.startsWith("//")
            ) {
                return false
            }
        }
        if (lower == "style" && normalized.contains("url(") && normalized.contains("http")) {
            return false
        }
        return true
    }

    /** 只认绝对尺寸：`100%`（相对父容器）读不出比例，视作「没给」。 */
    private fun number(raw: String): Float? {
        val value = raw.trim()
        if (value.endsWith("%")) return null
        return value.removeSuffix("px").toFloatOrNull()?.takeIf { it > 0f }
    }

    private fun escapeText(raw: String): String = raw
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun escapeAttribute(raw: String): String = escapeText(raw).replace("\"", "&quot;")
}

/**
 * 从落盘的 SVG 里读宽高比 —— 聊天气泡按它 `aspectRatio` 留位（自由绘制的图可能又高又窄，
 * 用固定比例会被压扁）。纯字符串解析，只读开头一小段。
 */
object SvgAspect {

    /** viewBox/width/height 都可能出现在前 400 字节里。 */
    private const val SNIPPET = 400

    fun of(head: String): Float? {
        val root = head.indexOf("<svg", ignoreCase = true)
        if (root < 0) return null
        val end = head.indexOf('>', root)
        val tag = if (end > 0) head.substring(root, end) else head.substring(root)
        val viewBox = Regex("""viewBox\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)
            ?.trim()?.split(Regex("[\\s,]+"))
        if (viewBox != null && viewBox.size == 4) {
            val w = viewBox[2].toFloatOrNull()
            val h = viewBox[3].toFloatOrNull()
            if (w != null && h != null && w > 0f && h > 0f && abs(w / h - 1f) > 0f) {
                return w / h
            }
        }
        fun attr(name: String) = Regex("""$name\s*=\s*"([\d.]+)""", RegexOption.IGNORE_CASE)
            .find(tag)?.groupValues?.get(1)?.toFloatOrNull()
        val w = attr("width")
        val h = attr("height")
        return if (w != null && h != null && w > 0f && h > 0f) w / h else null
    }

    /** 读文件开头一小段（图表/自由图卡片在组合期用 `remember` 包一次）。 */
    fun ofFile(path: String): Float? = runCatching {
        java.io.File(path).inputStream().use { stream ->
            val buffer = ByteArray(SNIPPET)
            val read = stream.read(buffer)
            if (read <= 0) null else of(String(buffer, 0, read, Charsets.UTF_8))
        }
    }.getOrNull()
}
