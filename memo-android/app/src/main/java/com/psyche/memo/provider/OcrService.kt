package com.psyche.memo.provider

import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.settings.PreferenceRepository
import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * Port of features/home/services/ocr_service.dart: run the configured OCR
 * model over a message's images, cache the text by image content hash, and
 * wrap it in the `<image_file_ocr>` block the message builder prepends.
 */
object OcrService {

    const val ARTIFACT_KIND = "image_ocr_v1"
    const val DEFAULT_USER_PROMPT =
        "Please perform OCR on the attached image(s) and return only the extracted text and visual descriptions."

    const val ENABLED_KEY = "ocr_enabled_v1"
    const val MODEL_KEY = "ocr_model_v1"
    const val PROMPT_KEY = "ocr_prompt_v1"
    const val THINKING_KEY = "ocr_generation_thinking_enabled_v1"

    private const val MAX_CACHE_ENTRIES = 32

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = LinkedHashMap<String, String>()

    data class OcrSettings(
        val enabled: Boolean,
        val providerId: String?,
        val modelId: String?,
        val prompt: String,
        val thinking: Boolean,
    ) {
        val usable: Boolean get() = enabled && providerId != null && modelId != null
    }

    fun settingsOf(prefs: PreferenceRepository): OcrSettings {
        val model = stringPref(prefs, MODEL_KEY).orEmpty()
        val parts = model.split("::")
        return OcrSettings(
            enabled = intPref(prefs, ENABLED_KEY, 0) == 1,
            providerId = parts.getOrNull(0)?.takeIf { it.isNotEmpty() },
            modelId = parts.drop(1).joinToString("::").takeIf { it.isNotEmpty() },
            prompt = stringPref(prefs, PROMPT_KEY).orEmpty(),
            thinking = intPref(prefs, THINKING_KEY, 0) == 1,
        )
    }

    /** buildOcrRequestMessages: prompt-less configurations skip the system turn. */
    fun buildMessages(prompt: String): List<LlmMessage> = buildList {
        val trimmed = prompt.trim()
        if (trimmed.isNotEmpty()) add(LlmMessage(role = "system", content = trimmed))
        add(LlmMessage(role = "user", content = DEFAULT_USER_PROMPT))
    }

    /** Runs the OCR model over [imagePaths]; null on failure/empty output. */
    suspend fun runOcr(
        container: AppContainerImpl,
        settings: OcrSettings,
        imagePaths: List<String>,
    ): String? {
        if (imagePaths.isEmpty()) return null
        val providerId = settings.providerId ?: return null
        val modelId = settings.modelId ?: return null
        val parts = imagePaths.mapNotNull { path ->
            val file = File(path)
            if (!file.isFile) null else "{\"uri\":\"${path.replace("\\", "\\\\")}\"}"
        }
        if (parts.isEmpty()) return null
        return runCatching {
            val messages = buildMessages(settings.prompt).toMutableList()
            val last = messages.lastIndex
            messages[last] = messages[last].copy(parts = parts)
            val client = container.clientFor(providerId)
            val result = client.complete(
                LlmRequest(
                    providerId = providerId,
                    modelId = modelId,
                    messages = messages,
                    apiKey = container.apiKeyFor(providerId) ?: "",
                    baseUrl = container.baseUrlFor(providerId),
                    chatPath = container.providerConfig(providerId)?.chatPath,
                    useResponseApi = container.usesResponseApi(providerId),
                    thinkingBudget = if (settings.thinking) -1 else 0,
                ),
            )
            result.parts.joinToString("").trim().ifEmpty { null }
        }.getOrNull()
    }

    /** `_defaultWrapOcrBlock`. */
    fun wrapBlock(ocrText: String): String = buildString {
        append("The image_file_ocr tag contains a description of an image that the user uploaded to you, not the user's prompt.\n")
        append("<image_file_ocr>\n")
        append(ocrText.trim())
        append("\n</image_file_ocr>\n\n")
    }

    /** SHA-256 of the file bytes (upstream resolveContentHashes equivalent). */
    fun contentHash(path: String): String? = runCatching {
        val file = File(path)
        if (!file.isFile) return null
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        digest.joinToString("") { "%02x".format(it) }
    }.getOrNull()

    fun cached(hash: String): String? {
        val value = cache.remove(hash) ?: return null
        cache[hash] = value // bump to most-recent
        return value
    }

    fun cacheText(hash: String, text: String) {
        if (hash.isEmpty()) return
        cache.remove(hash)
        cache[hash] = text
        while (cache.size > MAX_CACHE_ENTRIES) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
    }

    /** Test hook. */
    internal fun clearCache() = cache.clear()

    private fun intPref(prefs: PreferenceRepository, key: String, fallback: Int): Int {
        val raw = prefs.readJson(key)?.trim()?.removeSurrounding("\"") ?: return fallback
        return raw.toIntOrNull() ?: fallback
    }

    private fun stringPref(prefs: PreferenceRepository, key: String): String? {
        val raw = prefs.readJson(key) ?: return null
        return runCatching { json.parseToJsonElement(raw).jsonPrimitive.content }.getOrDefault(raw)
            .takeIf { it.isNotEmpty() }
    }
}
