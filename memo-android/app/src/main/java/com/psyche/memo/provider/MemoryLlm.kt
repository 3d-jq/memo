package com.psyche.memo.provider

import com.psyche.memo.AppContainerImpl
import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.ui.MemorySettingsState

/**
 * The memory model slot (`memory_model_v1` + `memory_model_thinking_enabled_v1`)
 * as a plain text generator — the Kotlin twin of
 * `tool_handler_service.dart:577-591`, which builds a `ChatApiService.generateText`
 * closure for the memory judge / extractor.
 *
 * Returns null when no memory model is configured; callers then take their
 * degraded path (exact-duplicate check only).
 */
object MemoryLlm {

    suspend fun generateText(container: AppContainerImpl, prompt: String): String? {
        val settings = MemorySettingsState(container)
        val providerId = settings.memoryModelProvider ?: return null
        val modelId = settings.memoryModelId ?: return null

        val request = LlmRequest(
            providerId = providerId,
            modelId = modelId,
            messages = listOf(LlmMessage(role = "user", content = prompt)),
            apiKey = container.apiKeyFor(providerId) ?: "",
            baseUrl = container.baseUrlFor(providerId),
            chatPath = container.providerConfig(providerId)?.chatPath,
            useResponseApi = container.usesResponseApi(providerId),
            thinkingBudget = if (settings.thinkingEnabled) -1 else 0,
        )
        return container.clientFor(providerId).complete(request).parts.joinToString("")
    }

    /**
     * Same call with the provider / model / thinking budget already resolved —
     * the pipeline needs the assistant's own budget override, which
     * [generateText] cannot see.
     */
    suspend fun generateTextWith(
        container: AppContainerImpl,
        providerId: String,
        modelId: String,
        prompt: String,
        thinkingBudget: Int,
    ): String {
        val request = LlmRequest(
            providerId = providerId,
            modelId = modelId,
            messages = listOf(LlmMessage(role = "user", content = prompt)),
            apiKey = container.apiKeyFor(providerId) ?: "",
            baseUrl = container.baseUrlFor(providerId),
            chatPath = container.providerConfig(providerId)?.chatPath,
            useResponseApi = container.usesResponseApi(providerId),
            thinkingBudget = thinkingBudget,
        )
        return container.clientFor(providerId).complete(request).parts.joinToString("")
    }

    /** The call to hand to [MemorySmartAdd], or null when the slot is unset. */
    fun callerOrNull(container: AppContainerImpl): (suspend (String) -> String)? {
        val settings = MemorySettingsState(container)
        if (!settings.modelSet) return null
        return { prompt -> generateText(container, prompt) ?: "" }
    }
}
