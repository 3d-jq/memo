package com.psyche.memo.update

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 上游 `update_provider.dart` 的 `UpdateInfo`。本项目没有自己的更新服务器，数据源换成
 * GitHub Releases，所以字段按 `releases/latest` 的 JSON 形状取；上游的 `build` 与
 * `mandatory` 在 GitHub 上没有对应概念，去掉（见 PORTING §5.37）。
 */
data class UpdateInfo(
    val version: String,
    val releasedAt: String?,
    val notes: String,
    val downloadUrl: String,
    /**
     * 有 `.apk` 资产时是那个资产的文件名（应用内下载要用它给文件命名、也就是通知栏标题）；
     * 只有 release 页面时是 null —— 那种情况下载不动，调用方直接开浏览器。
     */
    val downloadName: String? = null,
)

object UpdateFeed {

    /** 照上游 `_isRemoteNewer`：只比前三段数字，忽略 build 号。 */
    fun isRemoteNewer(remoteVersion: String, currentVersion: String): Boolean {
        val remote = segments(remoteVersion)
        val current = segments(currentVersion)
        for (index in 0 until 3) {
            if (remote[index] != current[index]) return remote[index] > current[index]
        }
        return false
    }

    private fun segments(version: String): IntArray {
        val parts = version.split('.')
        return IntArray(3) { index ->
            if (index >= parts.size) 0
            else parts[index].trim()
                .dropWhile { !it.isDigit() }
                .takeWhile { it.isDigit() }
                .toIntOrNull() ?: 0
        }
    }

    /**
     * 解析一条 release。tag 前缀的 `v` 去掉；有 `.apk` 资产就直链它，否则退回
     * release 页面（我们自己不发应用商店，浏览器下载是唯一路径）。
     */
    fun parseRelease(json: String): UpdateInfo? {
        val obj = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull() ?: return null
        val tag = obj.string("tag_name").trim()
        if (tag.isEmpty()) return null
        val assets = obj["assets"] as? JsonArray
        val apk = assets?.firstNotNullOfOrNull { element ->
            val asset = element as? JsonObject ?: return@firstNotNullOfOrNull null
            val url = asset.string("browser_download_url")
            val name = asset.string("name").ifEmpty { url.substringAfterLast('/') }
            if (url.isNotEmpty() && name.endsWith(".apk", ignoreCase = true)) url to name else null
        }
        val page = obj.string("html_url")
        return UpdateInfo(
            version = tag.removePrefix("v"),
            releasedAt = obj["published_at"]?.let { (it as? JsonPrimitive)?.contentOrNull },
            notes = obj.string("body"),
            downloadUrl = apk?.first?.takeIf { it.isNotEmpty() } ?: page,
            downloadName = apk?.second,
        )
    }

    private fun JsonObject.string(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
}
