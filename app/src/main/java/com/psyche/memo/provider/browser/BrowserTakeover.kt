package com.psyche.memo.provider.browser

import android.net.Uri
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback

/**
 * 接管态才存在的两笔「交给用户」的请求：JS 弹窗与文件选择器（spec §12.4）。
 *
 * 放一个文件里是因为它们共用同一条纪律：**无头时代替用户决定（拒绝 + 记账），有界面时把决定
 * 交回给人**，且两者都必须在遮罩销毁的那一刻被解套 —— 弹窗不 confirm 会让那一页的 JS 永远卡住，
 * 文件回调不 `onReceiveValue(null)` 会让那个 `<input type=file>` 永远按不动。
 */

enum class BrowserJsKind { ALERT, CONFIRM, PROMPT }

/**
 * 一个正等着用户回答的 JS 弹窗。
 *
 * 持有平台 `JsResult` 本体：**只能在主线程**回答（Compose 的点击回调就是主线程），
 * 且**必须恰好回答一次** —— 遮罩销毁时 [BrowserSession.dropPendingJsDialog] 兜底 cancel，
 * 用户点按钮走 [answer]，两条路径谁先跑到另一条就哑掉（`answered` 旗标）。
 * 重复 confirm/cancel 在平台侧是「未定义行为」，某些内核会直接抛。
 */
class BrowserJsDialog internal constructor(
    val kind: BrowserJsKind,
    val message: String,
    val defaultValue: String?,
    private val result: JsResult,
) {
    private var answered = false

    internal fun answer(accept: Boolean, text: String?) {
        if (answered) return
        answered = true
        if (!accept) result.cancel()
        else if (result is JsPromptResult) result.confirm(text.orEmpty())
        else result.confirm()
    }
}

/**
 * 一个正等着用户挑文件的请求。`mimeTypes` 已经由 [filePickerMimeTypes] 归一成
 * `ACTION_OPEN_DOCUMENT` 能吃的形态；界面拿到结果后调 [submit]，取消调 [cancel]。
 */
class BrowserFileRequest internal constructor(
    private val callback: ValueCallback<Array<Uri>>,
    val mimeTypes: Array<String>,
    val hint: String?,
) {
    private var settled = false

    internal fun submit(uris: List<Uri>) {
        if (settled) return
        settled = true
        callback.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
    }

    internal fun cancel() {
        if (settled) return
        settled = true
        callback.onReceiveValue(null)
    }
}

/**
 * `<input accept>` 的取值是**网页自己写的任意串**，常见三种形态混着用：MIME
 * （`image/png`）、通配（`image/*`）、**裸扩展名**（`.pdf`、`.xls`）。直接丢给
 * `ACTION_OPEN_DOCUMENT` 的 `EXTRA_MIME_TYPES` 会把选择器过滤成「什么都不匹配」，
 * 用户看到的是空列表 —— 那会被读成「上传坏了」，其实是这道映射。
 *
 * 认不出的形态**一律丢弃**，最后拿不到任何可用类型就回 `*/*`：宁可让用户从全部文件里挑，
 * 也不给他一个空选择器。扩展名表只收网页真会写的那几个，别去抄 MIME 全集。
 */
fun filePickerMimeTypes(acceptTypes: List<String>): Array<String> {
    val out = LinkedHashSet<String>()
    acceptTypes.forEach { raw ->
        val token = raw.trim().lowercase()
        when {
            token.isEmpty() -> Unit
            token.contains("/") -> out.add(token)
            token.startsWith(".") -> EXTENSION_MIMES[token]?.let { out.add(it) }
            else -> Unit
        }
    }
    return if (out.isEmpty()) arrayOf("*/*") else out.toTypedArray()
}

private val SCHEME_PREFIX = Regex("^[a-z][a-z0-9+.-]*:", RegexOption.IGNORE_CASE)

private val EXTENSION_MIMES = mapOf(
    ".png" to "image/png",
    ".jpg" to "image/jpeg",
    ".jpeg" to "image/jpeg",
    ".gif" to "image/gif",
    ".webp" to "image/webp",
    ".heic" to "image/heic",
    ".svg" to "image/svg+xml",
    ".pdf" to "application/pdf",
    ".txt" to "text/plain",
    ".csv" to "text/csv",
    ".json" to "application/json",
    ".zip" to "application/zip",
    ".doc" to "application/msword",
    ".docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".xls" to "application/vnd.ms-excel",
    ".xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".ppt" to "application/vnd.ms-powerpoint",
    ".pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ".mp3" to "audio/mpeg",
    ".mp4" to "video/mp4",
)

/**
 * 地址栏输入 → 要不要交给 WebView 的那一条式子（纯函数，所以能单测）。
 *
 * 规则照主流浏览器：**没写协议就补 `https://`**（用户输入 `example.com` 是想访问这个站，
 * 不是想报个错），补完仍要过 [isAllowedUrl] —— 补协议不等于放行：`http://` 原样留着让
 * [isAllowedUrl] 拒（那是用户明确写的明文地址，替他升级成 https 会静默打开另一个站点），
 * 而 `javascript:` / `file:` 这类自带协议的也在同一道闸外。
 *
 * @return null = 空输入，什么都不该做。
 */
fun addressInputToUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    // containsMatchIn + `^` 锚：只判「开头是不是有协议」。用 matches() 是全串匹配，
    // `http://example.com` 会判成「没协议」再补一发 https://，把用户明确写的明文地址改写成
    // 另一个站点（这条是 BrowserTakeoverTest 抓出来的）。
    val hasScheme = SCHEME_PREFIX.containsMatchIn(trimmed)
    return if (hasScheme) trimmed else "https://$trimmed"
}
