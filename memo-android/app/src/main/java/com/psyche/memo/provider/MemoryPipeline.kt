package com.psyche.memo.provider

import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.ui.MemoryEntry
import com.psyche.memo.ui.MemoryPromptLang
import com.psyche.memo.ui.MemoryProviderV2
import com.psyche.memo.ui.MemorySettingsState
import com.psyche.memo.ui.MemorySource
import com.psyche.memo.ui.MemoryType
import com.psyche.memo.ui.UserProfileRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * memory_gatekeeper.dart — decides whether a turn is worth remembering.
 *
 * A parsed `false` completes the run and advances the watermark; a malformed
 * answer or a failed request must not (§12.8).
 */
enum class MemoryGateParseResult { WORTH_REMEMBERING, SKIP, MALFORMED }

object MemoryGatekeeper {

    private val USER_MEMORY_RE = Regex("<user_memory>\\s*(true|false)", RegexOption.IGNORE_CASE)

    fun resolveTemplate(lang: MemoryPromptLang, overrideZh: String?, overrideEn: String?): String {
        val override = if (lang == MemoryPromptLang.zh) overrideZh?.trim() else overrideEn?.trim()
        if (!override.isNullOrEmpty()) return override
        return com.psyche.memo.ui.MemoryPrompts.gateFor(lang)
    }

    fun buildPrompt(
        lang: MemoryPromptLang,
        conversation: String,
        overrideZh: String? = null,
        overrideEn: String? = null,
    ): String = resolveTemplate(lang, overrideZh, overrideEn).replace("{{conversation}}", conversation)

    /** Tolerates surrounding prose; an unmatched tag is [MemoryGateParseResult.MALFORMED]. */
    fun parse(response: String): MemoryGateParseResult = when (
        USER_MEMORY_RE.find(response)?.groupValues?.get(1)?.lowercase()
    ) {
        "true" -> MemoryGateParseResult.WORTH_REMEMBERING
        "false" -> MemoryGateParseResult.SKIP
        else -> MemoryGateParseResult.MALFORMED
    }
}

/** One candidate extracted from a conversation window (§12.5). */
data class MemoryExtractedItem(
    val type: MemoryType,
    val content: String,
    /** Raw `scope` attribute from the model; honoured only for `toolDefault*` policies. */
    val scopeAttr: String? = null,
)

/** Extract parse outcome; [ok] false means the `<extracted>` tag was missing. */
data class MemoryExtractParseResult(val ok: Boolean, val items: List<MemoryExtractedItem>) {
    companion object {
        fun ok(items: List<MemoryExtractedItem>) = MemoryExtractParseResult(true, items)
        fun malformed() = MemoryExtractParseResult(false, emptyList())
    }
}

/** memory_extractor.dart — pure Extract helpers (§12.5). */
object MemoryExtractor {

    const val MAX_ITEMS = 10

    private val EXTRACTED_OPEN_RE = Regex("<extracted\\b", RegexOption.IGNORE_CASE)
    private val ITEM_RE = Regex("<item\\b([^>]*)>([\\s\\S]*?)</item>", RegexOption.IGNORE_CASE)
    private val TYPE_ATTR_RE = Regex(
        """type\s*=\s*["'](identity|workflow|voice|instruction)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val SCOPE_ATTR_RE = Regex(
        """scope\s*=\s*["'](global|assistant)["']""",
        RegexOption.IGNORE_CASE,
    )

    fun resolveTemplate(lang: MemoryPromptLang, overrideZh: String?, overrideEn: String?): String {
        val override = if (lang == MemoryPromptLang.zh) overrideZh?.trim() else overrideEn?.trim()
        if (!override.isNullOrEmpty()) return override
        return com.psyche.memo.ui.MemoryPrompts.extractFor(lang)
    }

    fun buildPrompt(
        lang: MemoryPromptLang,
        conversation: String,
        existingMemory: String,
        writeScope: String?,
        overrideZh: String? = null,
        overrideEn: String? = null,
    ): String {
        var template = resolveTemplate(lang, overrideZh, overrideEn)

        val toolDefault = writeScope == "toolDefaultGlobal" || writeScope == "toolDefaultAssistant"
        if (toolDefault) {
            val rule = com.psyche.memo.ui.MemoryPrompts.extractToolDefaultScopeRuleFor(lang)
            val marker = if (lang == MemoryPromptLang.zh) "## 已有记忆" else "## Existing memory"
            template = if (template.contains(marker)) {
                template.replaceFirst(marker, "$rule\n\n$marker")
            } else {
                "$template\n\n$rule"
            }
        }

        return template
            .replace("{{existingMemory}}", existingMemory)
            .replace("{{conversation}}", conversation)
    }

    /**
     * Requires an `<extracted` tag, otherwise malformed. Invalid types and empty
     * bodies are dropped; capped at [MAX_ITEMS].
     */
    fun parse(response: String): MemoryExtractParseResult {
        if (!EXTRACTED_OPEN_RE.containsMatchIn(response)) return MemoryExtractParseResult.malformed()

        val items = mutableListOf<MemoryExtractedItem>()
        for (match in ITEM_RE.findAll(response)) {
            if (items.size >= MAX_ITEMS) break
            val attrs = match.groupValues[1]
            val body = match.groupValues[2].trim()
            if (body.isEmpty()) continue
            val typeName = TYPE_ATTR_RE.find(attrs)?.groupValues?.get(1)?.lowercase() ?: continue
            val type = MemoryType.entries.firstOrNull { it.wire == typeName } ?: continue
            val scopeAttr = SCOPE_ATTR_RE.find(attrs)?.groupValues?.get(1)?.lowercase()
            items.add(MemoryExtractedItem(type = type, content = body, scopeAttr = scopeAttr))
        }
        return MemoryExtractParseResult.ok(items)
    }
}

/** One distilled profile field from Distiller JSON (§12.7). */
data class MemoryDistilledField(val key: String, val value: String)

/** Distiller parse outcome; [ok] false means unparseable JSON. */
data class MemoryDistillParseResult(val ok: Boolean, val fields: List<MemoryDistilledField>) {
    companion object {
        fun ok(fields: List<MemoryDistilledField>) = MemoryDistillParseResult(true, fields)
        fun malformed() = MemoryDistillParseResult(false, emptyList())
    }
}

/**
 * memory_profile_distiller.dart — turns identity memories into the structured
 * profile fields the model sees in `<user_profile>` (§12.7). It never clears a
 * field, and a failure is not fatal: the caller still advances the watermark
 * (§12.8).
 */
class MemoryProfileDistiller(
    private val container: AppContainerImpl,
    private val provider: MemoryProviderV2,
) {

    fun buildPrompt(
        lang: MemoryPromptLang,
        profileBlock: String,
        identityEntries: String,
        overrideZh: String? = null,
        overrideEn: String? = null,
    ): String {
        val override = if (lang == MemoryPromptLang.zh) overrideZh?.trim() else overrideEn?.trim()
        val template = if (!override.isNullOrEmpty()) {
            override
        } else {
            com.psyche.memo.ui.MemoryPrompts.profileDistillFor(lang)
        }
        return template
            .replace("{{profileBlock}}", profileBlock)
            .replace("{{identityEntries}}", identityEntries)
    }

    fun parse(response: String): MemoryDistillParseResult {
        val decoded = extractJsonObject(response) ?: return MemoryDistillParseResult.malformed()
        val rawFields = decoded["fields"] as? kotlinx.serialization.json.JsonArray
            ?: return MemoryDistillParseResult.malformed()

        val fields = rawFields.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val key = (item["key"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val value = (item["value"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            if (key.isEmpty() || value.isEmpty()) return@mapNotNull null
            if (!UserProfileRepository.isValidKey(key)) return@mapNotNull null
            MemoryDistilledField(key, value)
        }
        return MemoryDistillParseResult.ok(fields)
    }

    /** Returns false on an LLM or parse failure; the caller still advances. */
    suspend fun run(
        lang: MemoryPromptLang,
        assistantId: String?,
        llmCall: suspend (String) -> String,
        overrideZh: String? = null,
        overrideEn: String? = null,
        traceStep: MemoryTraceStep? = null,
    ): Boolean {
        val identity = provider.visibleFor(assistantId).filter { it.type == MemoryType.identity }
        if (identity.isEmpty()) return true

        val profile = UserProfileRepository.fields(container)
        val profileBlock = MemoryBlockBuilder.buildProfileBlock(profile, lang)
        val prompt = buildPrompt(
            lang = lang,
            profileBlock = profileBlock,
            identityEntries = formatIdentityEntries(identity),
            overrideZh = overrideZh,
            overrideEn = overrideEn,
        )

        traceStep?.appendPrompt(prompt)
        val raw = runCatching { llmCall(prompt) }.getOrNull()
        if (raw == null) {
            traceStep?.appendResponse("<request failed>")
            return false
        }
        traceStep?.appendResponse(raw)
        val parsed = parse(raw)
        if (!parsed.ok) {
            traceStep?.parsedResult = "malformed"
            return false
        }
        traceStep?.parsedResult = parsed.fields.joinToString(", ") { "${it.key}=${it.value}" }

        for (field in parsed.fields) {
            val before = profile.firstOrNull { it.key == field.key }?.value
            runCatching {
                UserProfileRepository.put(container, field.key, field.value, source = "distilled")
            }.onSuccess {
                traceStep?.addMutation(
                    MemoryTraceMutation(
                        kind = MemoryTraceMutationKind.PROFILE_FIELD_WRITTEN,
                        targetId = field.key,
                        before = before,
                        after = field.value,
                    ),
                )
            }
        }
        return true
    }

    private companion object {
        /** `id content` per line, as the Dart `formatIdentityEntries`. */
        fun formatIdentityEntries(entries: List<MemoryEntry>): String =
            entries.joinToString("\n") { "${it.id} ${it.content}" }

        /** JSON object out of model prose / ```json fences. */
        fun extractJsonObject(response: String): JsonObject? {
            var text = response.trim()
            FENCE.find(text)?.let { text = it.groupValues[1].trim() }
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { Json.parseToJsonElement(text.substring(start, end + 1)) as? JsonObject }
                .getOrNull()
        }

        val FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    }
}

/** Result of a background organize run (§12 / §13.6). */
data class MemoryOrganizeResult(
    val advanced: Boolean,
    val gate: MemoryGateParseResult? = null,
    val extractedCount: Int = 0,
    val error: String? = null,
    val forcedAdvance: Boolean = false,
    /** Messages in the processed window; 0 when the run stopped earlier. */
    val windowSize: Int = 0,
)

data class MemoryOrganizeStatus(val lastAt: Long? = null, val lastResult: MemoryOrganizeResult? = null)

/**
 * The settings a run reads, snapshotted so [MemoryPipelineService] does not
 * depend on the settings UI and tests can build one directly.
 */
data class MemoryPipelineSettings(
    val lang: MemoryPromptLang,
    val injectionMaxItems: Int,
    val gateZh: String,
    val gateEn: String,
    val extractZh: String,
    val extractEn: String,
    val smartAddZh: String,
    val smartAddEn: String,
    val smartAddBatchZh: String,
    val smartAddBatchEn: String,
    val distillZh: String,
    val distillEn: String,
) {
    companion object {
        fun from(container: AppContainerImpl): MemoryPipelineSettings {
            val state = MemorySettingsState(container)
            return MemoryPipelineSettings(
                lang = state.resolvedPromptLang(),
                injectionMaxItems = state.injectionMaxItems,
                gateZh = state.prompt(com.psyche.memo.ui.MemoryPromptKind.GATE, true),
                gateEn = state.prompt(com.psyche.memo.ui.MemoryPromptKind.GATE, false),
                extractZh = state.prompt(com.psyche.memo.ui.MemoryPromptKind.EXTRACT, true),
                extractEn = state.prompt(com.psyche.memo.ui.MemoryPromptKind.EXTRACT, false),
                smartAddZh = state.prompt(com.psyche.memo.ui.MemoryPromptKind.SMART_ADD, true),
                smartAddEn = state.prompt(com.psyche.memo.ui.MemoryPromptKind.SMART_ADD, false),
                smartAddBatchZh = state.smartAddBatchPrompt(true),
                smartAddBatchEn = state.smartAddBatchPrompt(false),
                distillZh = state.prompt(com.psyche.memo.ui.MemoryPromptKind.DISTILL, true),
                distillEn = state.prompt(com.psyche.memo.ui.MemoryPromptKind.DISTILL, false),
            )
        }
    }
}

/**
 * memory_pipeline.dart — the background memory pipeline:
 * Gatekeeper → Extract → Smart Add → Profile Distiller.
 *
 * One run at a time, max 8 queued (the oldest is dropped when full) (§12.8).
 * Traces are not recorded: the session trace viewer needs the trace-step model
 * of `memory_trace.dart`, which this port has not moved over yet.
 */
class MemoryPipelineService(
    private val container: AppContainerImpl,
    private val scope: CoroutineScope,
) {
    private data class Job(
        val conversationId: String,
        val assistantId: String,
        val force: Boolean,
        val result: CompletableDeferred<MemoryOrganizeResult>? = null,
        val onError: ((String) -> Unit)? = null,
    )

    private val queue = ArrayDeque<Job>()
    private val drainLock = Mutex()
    private val windowFailures = HashMap<String, Int>()

    @Volatile
    private var status = MemoryOrganizeStatus()

    val lastStatus: MemoryOrganizeStatus get() = status

    /** Never awaits, never throws into the chat turn that triggered it. */
    fun scheduleIfNeeded(conversationId: String, assistantId: String, onError: ((String) -> Unit)? = null) {
        runCatching {
            enqueue(Job(conversationId = conversationId, assistantId = assistantId, force = false, onError = onError))
        }.onFailure { onError?.invoke(it.toString()) }
    }

    /** Manual "organize now" — bypasses the auto-organize toggle and N-turns. */
    suspend fun runNow(conversationId: String, assistantId: String): MemoryOrganizeResult {
        val deferred = CompletableDeferred<MemoryOrganizeResult>()
        enqueue(
            Job(
                conversationId = conversationId,
                assistantId = assistantId,
                force = true,
                result = deferred,
            ),
        )
        return deferred.await()
    }

    private fun enqueue(job: Job) {
        // A temporary conversation is thrown away when the user leaves it;
        // distilling it into long-term memory would outlive that promise.
        if (job.conversationId == Conversation.TEMPORARY_ID) {
            job.result?.complete(MemoryOrganizeResult(advanced = false, error = "temporary_conversation"))
            return
        }

        // Coalesce pending (not running) auto jobs for the same conversation.
        queue.removeAll { it.conversationId == job.conversationId && it.result == null && job.result == null }
        queue.addLast(job)
        while (queue.size > QUEUE_LIMIT) {
            val dropped = queue.removeFirst()
            dropped.onError?.invoke("queue_overflow")
            dropped.result?.complete(MemoryOrganizeResult(advanced = false, error = "queue_overflow"))
        }
        scope.launch { drain() }
    }

    private suspend fun drain() {
        drainLock.withLock {
            while (queue.isNotEmpty()) {
                val job = queue.removeFirst()
                val result = runCatching { runJob(job) }.getOrElse { error ->
                    MemoryOrganizeResult(advanced = false, error = error.toString())
                }
                status = MemoryOrganizeStatus(lastAt = System.currentTimeMillis(), lastResult = result)
                result.error?.takeIf { it !in SKIP_REASON_CODES }?.let { job.onError?.invoke(it) }
                job.result?.complete(result)
            }
        }
    }

    private suspend fun runJob(job: Job): MemoryOrganizeResult {
        val handle = beginJobTrace(job)
        val result = try {
            runJobBody(job, handle)
        } catch (error: Throwable) {
            handle?.commit(error = error.toString())
            throw error
        }
        handle?.commit(
            advanced = result.advanced,
            forcedAdvance = result.forcedAdvance,
            error = result.error,
        )
        return result
    }

    /** Opens a trace for [job]; null when recording is off or the chat is temporary. */
    private fun beginJobTrace(job: Job): MemoryTraceHandle? {
        // Temporary chats are discarded on exit; keep their traces (title, args,
        // result) out of the viewer.
        if (job.conversationId == Conversation.TEMPORARY_ID) return null
        return runCatching {
            val assistant = container.assistantStore.get(job.assistantId)
            val conversation = container.conversationDao.get(job.conversationId)
            container.memoryTraceRecorder.begin(
                trigger = if (job.force) MemoryTraceTrigger.MANUAL else MemoryTraceTrigger.AUTO_TURNS,
                scope = memoryTraceScopeOf(assistant?.memoryWriteScope),
                conversationId = job.conversationId,
                conversationTitle = conversation?.title,
                assistantId = assistant?.id ?: job.assistantId,
                assistantName = assistant?.name,
            )
        }.getOrNull()
    }

    private suspend fun runJobBody(job: Job, trace: MemoryTraceHandle?): MemoryOrganizeResult {
        val provider = container.memoryProviderV2
        provider.ensureLoaded()
        val assistant = container.assistantStore.get(job.assistantId)
            ?: return MemoryOrganizeResult(advanced = false, error = "assistant_missing")
        if (!assistant.enableMemory) return MemoryOrganizeResult(advanced = false, error = "memory_disabled")
        if (!job.force && !assistant.autoOrganizeMemory) {
            return MemoryOrganizeResult(advanced = false, error = "auto_organize_off")
        }

        val settings = MemorySettingsState(container)
        if (!settings.modelSet) return MemoryOrganizeResult(advanced = false, error = "memory_model_unset")
        val providerKey = settings.memoryModelProvider!!
        val modelId = settings.memoryModelId!!
        val config = container.providerConfig(providerKey)
        if (config != null && config.models.isNotEmpty() && modelId !in config.models) {
            return MemoryOrganizeResult(advanced = false, error = "memory_model_missing")
        }

        val conversation = container.conversationDao.get(job.conversationId)
            ?: return MemoryOrganizeResult(advanced = false, error = "conversation_missing")

        // 压缩检查点不是真实发言（摘要只给模型看）：记忆抽取不看它，否则锚定摘要会
        // 被当成一条用户消息写进记忆。
        val messages = container.messageDao.getAllForConversation(job.conversationId)
            .filterNot { it.isCompaction }
        if (messages.any { it.isStreaming }) {
            return MemoryOrganizeResult(advanced = false, error = "streaming")
        }

        val selected = collapseSelectedVersions(messages, conversation.versionSelections)
        val watermark = conversation.lastMemoryExtractedOrder
        val orderById = container.messageDao.getMessageIds(job.conversationId)
            .withIndex().associate { (index, id) -> id to index }

        val window = selected.mapNotNull { message ->
            if (message.isStreaming) return@mapNotNull null
            val order = orderById[message.id] ?: return@mapNotNull null
            if (order <= watermark) return@mapNotNull null
            message to order
        }.sortedBy { it.second }

        val pendingTurns = window.count { it.first.role == "assistant" }
        if (!job.force && pendingTurns < assistant.memoryOrganizeEveryNTurns.coerceIn(1, 20)) {
            return MemoryOrganizeResult(advanced = false, error = "below_threshold")
        }
        if (window.isEmpty()) return MemoryOrganizeResult(advanced = false, error = "empty_window")

        val capped = if (watermark == -1 && window.size > FIRST_WINDOW_CAP) {
            window.subList(window.size - FIRST_WINDOW_CAP, window.size).toList()
        } else {
            window
        }

        val thinkingBudget = if (settings.thinkingEnabled) {
            assistant.thinkingBudget ?: readThinkingBudget() ?: -1
        } else {
            0
        }
        return processWindow(
            conversationId = job.conversationId,
            assistant = assistant,
            settings = MemoryPipelineSettings.from(container),
            watermark = watermark,
            window = capped,
            llmCall = { prompt -> MemoryLlm.generateTextWith(container, providerKey, modelId, prompt, thinkingBudget) },
            trace = trace,
        )
    }

    /** Gatekeeper → Extract → Smart Add → Distiller for a prepared window. */
    suspend fun processWindow(
        conversationId: String,
        assistant: Assistant,
        settings: MemoryPipelineSettings,
        watermark: Int,
        window: List<Pair<ChatMessage, Int>>,
        llmCall: suspend (String) -> String,
        trace: MemoryTraceHandle? = null,
    ): MemoryOrganizeResult {
        if (window.isEmpty()) return MemoryOrganizeResult(advanced = false, error = "empty_window")

        val windowEnd = window.last().second
        val failureKey = "$conversationId|$watermark|$windowEnd"
        val lang = settings.lang
        val conversationText = buildConversationText(window.map { it.first }, lang)
        trace?.setWindow(
            watermark = watermark,
            startOrder = window.first().second,
            endOrder = windowEnd,
            size = window.size,
        )

        // ── Gatekeeper ───────────────────────────────────────────────────────
        val gateStep = trace?.beginStep(MemoryTraceStepKind.GATEKEEPER)
        val gatePrompt = MemoryGatekeeper.buildPrompt(
            lang = lang,
            conversation = conversationText,
            overrideZh = settings.gateZh,
            overrideEn = settings.gateEn,
        )
        gateStep?.appendPrompt(gatePrompt)
        val gateAttempt = runCatching { llmCall(gatePrompt) }
        val gateRaw = gateAttempt.getOrNull()
        if (gateRaw == null) {
            val reason = "gate_request_failed:" + (gateAttempt.exceptionOrNull()?.message ?: "unknown")
            gateStep?.finish(MemoryTraceStepStatus.FAILED, reason)
            skipRemainingSteps(trace, from = MemoryTraceStepKind.EXTRACT)
            return failWindow(
                failureKey = failureKey,
                conversationId = conversationId,
                windowEnd = windowEnd,
                windowSize = window.size,
                gate = null,
                error = reason,
            )
        }
        gateStep?.appendResponse(gateRaw)
        val gate = MemoryGatekeeper.parse(gateRaw)
        gateStep?.parsedResult = gate.name
        if (gate == MemoryGateParseResult.MALFORMED) {
            gateStep?.finish(MemoryTraceStepStatus.FAILED, "gate_parse_failed")
            skipRemainingSteps(trace, from = MemoryTraceStepKind.EXTRACT)
            return failWindow(
                failureKey = failureKey,
                conversationId = conversationId,
                windowEnd = windowEnd,
                windowSize = window.size,
                gate = gate,
                error = "gate_parse_failed",
            )
        }
        gateStep?.finish(MemoryTraceStepStatus.SUCCESS)
        if (gate == MemoryGateParseResult.SKIP) {
            skipRemainingSteps(trace, from = MemoryTraceStepKind.EXTRACT)
            advance(conversationId, windowEnd)
            windowFailures.remove(failureKey)
            return MemoryOrganizeResult(advanced = true, gate = gate, windowSize = window.size)
        }

        // ── Extract ──────────────────────────────────────────────────────────
        val provider = container.memoryProviderV2
        provider.ensureLoaded()
        val visible = provider.visibleFor(assistant.id)
        val existingMemory = MemoryBlockBuilder.buildMemoryBlock(
            visible = visible,
            totalByType = visible.groupingBy { it.type }.eachCount(),
            lang = lang,
            maxItems = settings.injectionMaxItems,
        )
        val extractStep = trace?.beginStep(MemoryTraceStepKind.EXTRACT)
        val extractPrompt = MemoryExtractor.buildPrompt(
            lang = lang,
            conversation = conversationText,
            existingMemory = existingMemory,
            writeScope = assistant.memoryWriteScope,
            overrideZh = settings.extractZh,
            overrideEn = settings.extractEn,
        )
        extractStep?.appendPrompt(extractPrompt)
        val extractAttempt = runCatching { llmCall(extractPrompt) }
        val extractRaw = extractAttempt.getOrNull()
        if (extractRaw == null) {
            val reason = "extract_request_failed:" + (extractAttempt.exceptionOrNull()?.message ?: "unknown")
            extractStep?.finish(MemoryTraceStepStatus.FAILED, reason)
            skipRemainingSteps(trace, from = MemoryTraceStepKind.SMART_ADD)
            return failWindow(
                failureKey = failureKey,
                conversationId = conversationId,
                windowEnd = windowEnd,
                windowSize = window.size,
                gate = gate,
                error = reason,
            )
        }
        extractStep?.appendResponse(extractRaw)
        val extracted = MemoryExtractor.parse(extractRaw)
        if (!extracted.ok) {
            extractStep?.finish(MemoryTraceStepStatus.FAILED, "extract_parse_failed")
            skipRemainingSteps(trace, from = MemoryTraceStepKind.SMART_ADD)
            return failWindow(
                failureKey = failureKey,
                conversationId = conversationId,
                windowEnd = windowEnd,
                windowSize = window.size,
                gate = gate,
                error = "extract_parse_failed",
            )
        }
        extractStep?.parsedResult = buildJsonObject {
            put("items", buildJsonArray {
                extracted.items.forEach { item ->
                    add(
                        buildJsonObject {
                            put("type", item.type.wire)
                            item.scopeAttr?.let { scope -> put("scope", scope) }
                            put("content", item.content)
                        },
                    )
                }
            })
        }.toString()
        extractStep?.finish(MemoryTraceStepStatus.SUCCESS)
        if (extracted.items.isEmpty()) {
            skipRemainingSteps(trace, from = MemoryTraceStepKind.SMART_ADD)
            advance(conversationId, windowEnd)
            windowFailures.remove(failureKey)
            return MemoryOrganizeResult(advanced = true, gate = gate, windowSize = window.size)
        }

        // ── Smart Add ────────────────────────────────────────────────────────
        val smartItems = extracted.items.map { item ->
            val scope = MemoryTools.resolveWriteScope(assistant.memoryWriteScope, item.scopeAttr)
            SmartAddItem(
                type = item.type,
                content = item.content,
                scope = scope,
                assistantId = if (scope == com.psyche.memo.ui.MemoryScope.assistant) assistant.id else null,
            )
        }
        val smartAdd = MemorySmartAdd(MemoryProviderSmartAddRepository(provider))
        val smartStep = trace?.beginStep(MemoryTraceStepKind.SMART_ADD)
        val smart = smartAdd.addMany(
            items = smartItems,
            visibilityAssistantId = assistant.id,
            source = MemorySource.extracted,
            lang = lang,
            mode = assistant.memorySmartAddMode,
            llmCall = llmCall,
            perItemOverrideZh = settings.smartAddZh,
            perItemOverrideEn = settings.smartAddEn,
            batchOverrideZh = settings.smartAddBatchZh,
            batchOverrideEn = settings.smartAddBatchEn,
            traceStep = smartStep,
        )
        smartStep?.finish(MemoryTraceStepStatus.SUCCESS)

        // ── Profile Distiller (identity changes only) ────────────────────────
        if (smart.identityChanged) {
            val distillStep = trace?.beginStep(MemoryTraceStepKind.PROFILE_DISTILLER)
            val ok = runCatching {
                MemoryProfileDistiller(container, provider).run(
                    lang = lang,
                    assistantId = assistant.id,
                    llmCall = llmCall,
                    overrideZh = settings.distillZh,
                    overrideEn = settings.distillEn,
                    traceStep = distillStep,
                )
            }.getOrDefault(false)
            distillStep?.finish(
                if (ok) MemoryTraceStepStatus.SUCCESS else MemoryTraceStepStatus.FAILED,
                if (ok) null else "distill_failed",
            )
        } else {
            trace?.beginStep(MemoryTraceStepKind.PROFILE_DISTILLER)
                ?.finish(MemoryTraceStepStatus.SKIPPED)
        }

        // Smart Add (including its degraded path) and Distiller failures both
        // advance the watermark (§12.8).
        advance(conversationId, windowEnd)
        windowFailures.remove(failureKey)
        return MemoryOrganizeResult(
            advanced = true,
            gate = gate,
            extractedCount = extracted.items.size,
            windowSize = window.size,
        )
    }

    /**
     * Records a window failure; after [MAX_WINDOW_FAILURES] attempts the
     * watermark advances anyway, so one poisonous window cannot block memory
     * extraction forever.
     */
    private fun failWindow(
        failureKey: String,
        conversationId: String,
        windowEnd: Int,
        windowSize: Int,
        gate: MemoryGateParseResult?,
        error: String,
    ): MemoryOrganizeResult {
        val count = (windowFailures[failureKey] ?: 0) + 1
        windowFailures[failureKey] = count
        if (count >= MAX_WINDOW_FAILURES) {
            advance(conversationId, windowEnd)
            windowFailures.remove(failureKey)
            return MemoryOrganizeResult(
                advanced = true,
                gate = gate,
                error = error,
                forcedAdvance = true,
                windowSize = windowSize,
            )
        }
        return MemoryOrganizeResult(
            advanced = false,
            gate = gate,
            error = error,
            windowSize = windowSize,
        )
    }

    private fun advance(conversationId: String, order: Int) {
        runCatching { container.conversationDao.setLastMemoryExtractedOrder(conversationId, order) }
        runCatching { container.conversationDao.get(conversationId)?.lastMemoryExtractedOrder = order }
        // The lists the UI reads come from the provider cache; refresh it so a
        // run that just wrote entries shows up without leaving the screen.
        runCatching { container.memoryProviderV2.loadAll() }
    }

    private fun readThinkingBudget(): Int? = runCatching {
        container.preferenceRepository.readJson("thinking_budget_v1")
            ?.let { Json.parseToJsonElement(it) as? JsonPrimitive }
            ?.content?.toIntOrNull()
    }.getOrNull()

    /** Records a stage the run never reached, so the viewer shows the chain. */
    private fun skipRemainingSteps(trace: MemoryTraceHandle?, from: MemoryTraceStepKind) {
        if (trace == null) return
        val start = ORGANIZE_STAGES.indexOf(from)
        if (start < 0) return
        for (index in start until ORGANIZE_STAGES.size) {
            trace.beginStep(ORGANIZE_STAGES[index])?.finish(MemoryTraceStepStatus.SKIPPED)
        }
    }

    companion object {
        private val ORGANIZE_STAGES = listOf(
            MemoryTraceStepKind.GATEKEEPER,
            MemoryTraceStepKind.EXTRACT,
            MemoryTraceStepKind.SMART_ADD,
            MemoryTraceStepKind.PROFILE_DISTILLER,
        )

        const val QUEUE_LIMIT = 8
        const val FIRST_WINDOW_CAP = 20
        const val MAX_WINDOW_FAILURES = 3

        /** Outcome codes that stop a run without counting as a task failure. */
        val SKIP_REASON_CODES = setOf(
            "temporary_conversation",
            "memory_disabled",
            "auto_organize_off",
            "streaming",
            "below_threshold",
            "empty_window",
        )

        /**
         * Collapse version chains to the selected (or newest) version, keeping
         * the first-seen group order — same rules as title generation.
         */
        fun collapseSelectedVersions(
            messages: List<ChatMessage>,
            selections: Map<String, Int>,
        ): List<ChatMessage> {
            val byGroup = LinkedHashMap<String, MutableList<ChatMessage>>()
            for (message in messages) {
                byGroup.getOrPut(message.groupId) { mutableListOf() }.add(message)
            }
            return byGroup.values.map { versions ->
                val sorted = versions.sortedBy { it.version }
                val selected = selections[sorted.first().groupId]
                if (selected != null) sorted.firstOrNull { it.version == selected } ?: sorted.last() else sorted.last()
            }
        }

        /** `buildConversationText(window)` (§12.3): text parts only, capped. */
        fun buildConversationText(window: List<ChatMessage>, lang: MemoryPromptLang): String {
            val userPrefix = if (lang == MemoryPromptLang.zh) "用户：" else "User: "
            val assistantPrefix = if (lang == MemoryPromptLang.zh) "助手：" else "Assistant: "
            val lines = mutableListOf<String>()
            for (message in window) {
                val prefix = when (message.role) {
                    "user" -> userPrefix
                    "assistant" -> assistantPrefix
                    else -> continue
                }
                var text = message.parts.filterIsInstance<TextPart>().joinToString("") { it.text }.trim()
                if (text.isEmpty()) continue
                if (text.length > 2000) text = text.substring(0, 2000) + "…"
                lines.add(prefix + text)
            }
            val out = lines.joinToString("\n\n")
            return if (out.length > 12000) "…" + out.substring(out.length - 12000) else out
        }

        /** Conversation-summary gate (§12.10 / D-27). */
        fun shouldGenerateConversationSummary(
            allowPastConversationRecall: Boolean,
            generateConversationSummary: Boolean,
        ): Boolean = allowPastConversationRecall && generateConversationSummary
    }
}
