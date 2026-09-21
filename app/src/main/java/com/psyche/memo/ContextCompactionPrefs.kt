package com.psyche.memo

import com.psyche.memo.common.SessionCompaction
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * 上下文压缩（opencode 阈值机制）的偏好读取。
 *
 * 四个键都是本工程新增的 PREFERENCE 键（`classifyBusinessKey` 认不出来 → UNKNOWN，
 * 走 preference_rows 存储与备份；见 PORTING §5.11）：
 * - `context_compaction_auto_v1`：自动压缩开关（默认开）
 * - `context_compaction_keep_tokens_v1`：原样保留的最近 tokens（默认 8000）
 * - `context_compaction_buffer_v1`：预留缓冲 tokens（默认 20000）
 * - `context_compaction_window_v1`：模型没写上下文窗口时的全局默认值（默认 128k）
 *
 * 模型级上下文窗口写在 provider 的 `modelOverrides[modelId].contextWindow`
 * （模型编辑页 Advanced），优先于全局默认值。
 */
object ContextCompactionPrefs {
    const val AUTO_V1 = "context_compaction_auto_v1"
    const val KEEP_TOKENS_V1 = "context_compaction_keep_tokens_v1"
    const val BUFFER_V1 = "context_compaction_buffer_v1"
    const val WINDOW_V1 = "context_compaction_window_v1"

    /** 模型 override 里可能的上下文窗口字段名（与既有读取路径一致）。 */
    private val WINDOW_OVERRIDE_KEYS = listOf(
        "contextWindow", "context_window", "maxContextTokens",
        "max_context_tokens", "contextLength", "context_length",
    )

    fun read(container: AppContainerImpl, providerId: String?, modelId: String?): SessionCompaction.Settings {
        val prefs = container.preferenceRepository
        return SessionCompaction.Settings(
            auto = boolOf(prefs.readJson(AUTO_V1), default = SessionCompaction.DEFAULT_AUTO),
            buffer = positiveOf(prefs.readJson(BUFFER_V1), SessionCompaction.DEFAULT_BUFFER),
            keepTokens = nonNegativeOf(prefs.readJson(KEEP_TOKENS_V1), SessionCompaction.DEFAULT_KEEP_TOKENS),
            contextWindow = modelContextWindow(container, providerId, modelId)
                ?: positiveOf(prefs.readJson(WINDOW_V1), SessionCompaction.DEFAULT_CONTEXT_WINDOW),
        )
    }

    /** 写自动压缩 / 保留 tokens / 缓冲 tokens（三个与模型无关的设置）。 */
    fun writeSettings(container: AppContainerImpl, auto: Boolean, keepTokens: Int, buffer: Int) {
        val prefs = container.preferenceRepository
        prefs.writeJson(AUTO_V1, JsonPrimitive(auto).toString())
        prefs.writeJson(KEEP_TOKENS_V1, JsonPrimitive(keepTokens).toString())
        prefs.writeJson(BUFFER_V1, JsonPrimitive(buffer).toString())
    }

    /** 写全局默认上下文窗口（模型自己填了 `contextWindow` 时不用写）。 */
    fun writeDefaultWindow(container: AppContainerImpl, contextWindow: Int) {
        container.preferenceRepository.writeJson(WINDOW_V1, JsonPrimitive(contextWindow).toString())
    }

    /** 模型 override 的上下文窗口；未填 → null（回落到全局默认值）。 */
    fun modelContextWindow(container: AppContainerImpl, providerId: String?, modelId: String?): Int? {
        if (providerId.isNullOrEmpty() || modelId.isNullOrEmpty()) return null
        val override = container.providerConfig(providerId)?.modelOverrides?.get(modelId) as? JsonObject
            ?: return null
        for (key in WINDOW_OVERRIDE_KEYS) {
            val value = override[key]?.jsonPrimitive?.content?.toIntOrNull()
            if (value != null && value > 0) return value
        }
        return null
    }

    /** JSON 文本偏好 → 布尔（裸 true/false、"1"/"0"、带引号的 JSON 串都认）。 */
    fun boolOf(raw: String?, default: Boolean): Boolean {
        if (raw == null) return default
        return when (raw.trim()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> runCatching {
                kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonPrimitive.content
            }.getOrNull()?.let { boolOf(it, default) } ?: default
        }
    }

    /** JSON 文本偏好 → 整数。 */
    fun intOf(raw: String?, default: Int): Int =
        raw?.let { runCatching { it.trim().toInt() }.getOrNull() } ?: default

    /** ≥ 1 的整数（窗口/缓冲为 0 会让阈值失去意义）。 */
    fun positiveOf(raw: String?, default: Int): Int = intOf(raw, default).takeIf { it > 0 } ?: default

    /** ≥ 0 的整数（保留 tokens 允许 0 = 不保留原文）。 */
    fun nonNegativeOf(raw: String?, default: Int): Int = intOf(raw, default).takeIf { it >= 0 } ?: default
}
