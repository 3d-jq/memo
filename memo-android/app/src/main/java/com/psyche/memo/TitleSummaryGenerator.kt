package com.psyche.memo

import com.psyche.memo.common.SummaryText
import com.psyche.memo.common.TitleText
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bridges the "default model" slots [title_model_v1] / [summary_model_v1] to
 * real LLM calls. Both functions are conversation-scoped (work for ANY
 * conversationId, loading messages from the DB) so they serve both the
 * auto-trigger path (called from ChatViewModel after each reply) and the
 * manual "regenerate" entry in the side drawer.
 *
 * Model resolution mirrors home_view_model.dart:
 *  - title:  titleModelProvider ?? chatModelProvider ?? selected_model_v1
 *  - summary: summaryModelProvider ?? titleModelProvider ?? chatModelProvider ?? selected_model_v1
 */
object TitleSummaryGenerator {

    /** Returns the generated title, or null when nothing was written. */
    suspend fun generateTitle(
        container: AppContainerImpl,
        conversationId: String,
        force: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        if (conversationId == Conversation.TEMPORARY_ID) return@withContext null
        val conv = container.conversationDao.get(conversationId) ?: return@withContext null
        val defaultTitle = container.appContext
            .getString(com.psyche.memo.ui.R.string.chat_service_default_conversation_title)
        if (!force && conv.title.isNotBlank() && conv.title != defaultTitle) return@withContext null
        if (!readBoolPref(container, DefaultModelPrefs.TITLE_GENERATION_ENABLED_V1)) return@withContext null

        val titleSel = readModelSelection(container, DefaultModelPrefs.TITLE_MODEL_V1)
        val chatSel = conv.chatModelProvider?.let { p -> conv.chatModelId?.let { m -> p to m } }
            ?: readModelSelection(container, DefaultModelPrefs.SELECTED_MODEL_V1)
        val providerId = titleSel?.first ?: chatSel?.first ?: return@withContext null
        val modelId = titleSel?.second ?: chatSel?.second ?: return@withContext null

        val msgs = container.messageDao.getAllForConversation(conversationId)
            .filterNot { it.isCompaction }
        val content = TitleText.buildContent(msgs.map { it.role to it.content })
        if (content.isBlank()) return@withContext null

        val template = readPrefString(container, DefaultModelPrefs.TITLE_PROMPT_V1)
            ?: DefaultModelPrefs.DEFAULT_TITLE_PROMPT
        val locale = java.util.Locale.getDefault().toLanguageTag()
        val prompt = template.replace("{content}", content).replace("{locale}", locale)
        val thinking = readBoolPref(container, DefaultModelPrefs.TITLE_GENERATION_THINKING_ENABLED_V1)

        val request = LlmRequest(
            providerId = providerId,
            modelId = modelId,
            messages = listOf(LlmMessage(role = "user", content = prompt)),
            apiKey = container.apiKeyFor(providerId) ?: "",
            baseUrl = container.baseUrlFor(providerId),
            chatPath = container.providerConfig(providerId)?.chatPath,
            useResponseApi = container.usesResponseApi(providerId),
            thinkingBudget = if (thinking) -1 else 0,
        )
        val raw = container.clientFor(providerId).complete(request).parts.joinToString("").trim()
        val title = TitleText.parseTitle(raw)
        if (title.isEmpty()) return@withContext null
        container.conversationDao.updateTitle(conversationId, title)
        title
    }

    /** Returns true when a summary was actually written. */
    suspend fun generateSummary(
        container: AppContainerImpl,
        conversationId: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (conversationId == Conversation.TEMPORARY_ID) return@withContext false
        val conv = container.conversationDao.get(conversationId) ?: return@withContext false

        // Gated by assistant flags (home_view_model.dart L1583-1593): both
        // allowPastConversationRecall AND generateConversationSummary must be on.
        val aid = conv.assistantId
        val assistant = if (aid != null) {
            container.assistantStore.get(aid)
        } else {
            container.currentAssistant()
        }
        if (assistant == null ||
            !(assistant.allowPastConversationRecall && assistant.generateConversationSummary)
        ) {
            return@withContext false
        }

        // 压缩检查点不是用户发言：不计入消息数、也不进摘要输入。
        val msgs = container.messageDao.getAllForConversation(conversationId)
            .filterNot { it.isCompaction }
        val total = msgs.size
        val lastN = conv.lastSummarizedMessageCount.coerceAtLeast(0)
        val trigger = assistant.recentChatsSummaryMessageCount.coerceAtLeast(1)
        if (total == 0 || total - lastN < trigger) return@withContext false

        val allUser = msgs.filter { it.role == "user" && it.content.trim().isNotEmpty() }
        val userAtLast = msgs.take(lastN)
            .count { it.role == "user" && it.content.trim().isNotEmpty() }
        val newUser = allUser.drop(userAtLast)
        if (newUser.isEmpty()) return@withContext false

        val content = SummaryText.buildContent(newUser.map { it.content.trim() })
        val previous = conv.summary.orEmpty()
        val template = readPrefString(container, DefaultModelPrefs.SUMMARY_PROMPT_V1)
            ?: DefaultModelPrefs.DEFAULT_SUMMARY_PROMPT
        val prompt = template
            .replace("{previous_summary}", previous)
            .replace("{user_messages}", content)

        val summarySel = readModelSelection(container, DefaultModelPrefs.SUMMARY_MODEL_V1)
        val titleSel = readModelSelection(container, DefaultModelPrefs.TITLE_MODEL_V1)
        val chatSel = conv.chatModelProvider?.let { p -> conv.chatModelId?.let { m -> p to m } }
            ?: readModelSelection(container, DefaultModelPrefs.SELECTED_MODEL_V1)
        val providerId = summarySel?.first ?: titleSel?.first ?: chatSel?.first ?: return@withContext false
        val modelId = summarySel?.second ?: titleSel?.second ?: chatSel?.second ?: return@withContext false
        val thinking = readBoolPref(container, DefaultModelPrefs.SUMMARY_GENERATION_THINKING_ENABLED_V1)

        val request = LlmRequest(
            providerId = providerId,
            modelId = modelId,
            messages = listOf(LlmMessage(role = "user", content = prompt)),
            apiKey = container.apiKeyFor(providerId) ?: "",
            baseUrl = container.baseUrlFor(providerId),
            chatPath = container.providerConfig(providerId)?.chatPath,
            useResponseApi = container.usesResponseApi(providerId),
            thinkingBudget = if (thinking) -1 else 0,
        )
        val raw = container.clientFor(providerId).complete(request).parts.joinToString("").trim()
        val summary = SummaryText.parseSummary(raw)
        if (summary.isEmpty()) return@withContext false
        container.conversationDao.updateSummary(conversationId, summary, total)
        true
    }

    // ---- pref readers (mirror ChatViewModel.readPrefString / readBoolPref) ----

    private fun readPrefString(container: AppContainerImpl, key: String): String? =
        container.preferenceRepository.readJson(key)
            ?.let { raw ->
                runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrDefault(raw)
            }
            ?.takeIf { it.isNotBlank() }

    private fun readBoolPref(container: AppContainerImpl, key: String): Boolean {
        val raw = container.preferenceRepository.readJson(key) ?: return false
        return runCatching { Json.parseToJsonElement(raw).jsonPrimitive.booleanOrNull }.getOrNull() ?: false
    }

    private fun readModelSelection(container: AppContainerImpl, key: String): Pair<String, String>? =
        DefaultModelPrefs.parseModelSelection(readPrefString(container, key))
}
