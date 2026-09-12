package com.psyche.memo.provider

import com.psyche.memo.ui.MemoryEntry
import com.psyche.memo.ui.MemoryPrompts
import com.psyche.memo.ui.MemoryPromptLang
import com.psyche.memo.ui.MemoryProviderV2
import com.psyche.memo.ui.MemoryScope
import com.psyche.memo.ui.MemorySource
import com.psyche.memo.ui.MemoryType
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * memory_smart_add.dart §12.6 — Smart Add: candidate retrieval, LLM judge and
 * NEW / MERGE / CONFLICT / SKIP application.
 *
 * The Kotlin port keeps the Dart structure: pure static helpers (prompt
 * building, JSON extraction, parsing, decision normalisation) plus the
 * repository-backed flows. The repository is an interface so the decision
 * logic can be driven by a fake in tests; [MemoryProviderV2] supplies the real
 * one through [MemoryProviderSmartAddRepository].
 */
enum class SmartAddAction { NEW, MERGE, CONFLICT, SKIP }

/** Parsed / resolved decision for one candidate item. */
data class SmartAddDecision(
    val action: SmartAddAction,
    val targetId: String? = null,
    val mergedContent: String? = null,
    val relatedIds: List<String> = emptyList(),
    val degraded: Boolean = false,
)

/** Result of applying one Smart Add decision. */
data class SmartAddResult(
    val action: SmartAddAction,
    val id: String? = null,
    val content: String? = null,
    val reason: String? = null,
) {
    /** `SmartAddResult.toToolJson()`. */
    fun toToolJson(): JsonObject = buildJsonObject {
        when (action) {
            SmartAddAction.SKIP -> {
                put("action", "SKIP")
                reason?.let { put("reason", it) }
                id?.let { put("id", it) }
            }
            SmartAddAction.NEW -> {
                put("action", "NEW")
                put("id", id)
                put("content", content)
            }
            SmartAddAction.MERGE -> {
                put("action", "MERGE")
                put("id", id)
                put("content", content)
            }
            SmartAddAction.CONFLICT -> {
                put("action", "CONFLICT")
                put("id", id)
                put("content", content)
                reason?.let { put("archivedId", it) }
            }
        }
    }
}

/** Input item for Smart Add (from Extract or `memory_update`). */
data class SmartAddItem(
    val type: MemoryType,
    val content: String,
    val scope: MemoryScope,
    val assistantId: String? = null,
)

/** Result of a multi-item run: whether any identity entry changed (for the Distiller). */
data class SmartAddBatchResult(val results: List<SmartAddResult>, val identityChanged: Boolean)

/**
 * The storage operations Smart Add needs. Implemented over [MemoryProviderV2]
 * for the app and by a fake in tests.
 */
interface SmartAddRepository {
    /** Active + visible + [type], ranked by token hits (all tokens OR'd), then recency. */
    fun searchAny(assistantId: String?, tokens: List<String>, type: MemoryType, limit: Int): List<MemoryEntry>

    /** Active + visible + [type], newest first. */
    fun visibleByType(assistantId: String?, type: MemoryType): List<MemoryEntry>

    fun findExact(assistantId: String?, type: MemoryType, contentNormalized: String): MemoryEntry?

    fun byIds(ids: List<String>): List<MemoryEntry>

    fun create(
        scope: MemoryScope,
        assistantId: String?,
        type: MemoryType,
        content: String,
        source: MemorySource,
    ): MemoryEntry

    fun updateContent(id: String, content: String): MemoryEntry?

    fun archive(id: String): Boolean

    /** Idempotent both-ways `relatedIds` link. */
    fun linkBidirectional(a: String, b: String)
}

/** [SmartAddRepository] over the in-memory [MemoryProviderV2] store. */
class MemoryProviderSmartAddRepository(private val provider: MemoryProviderV2) : SmartAddRepository {

    override fun searchAny(
        assistantId: String?,
        tokens: List<String>,
        type: MemoryType,
        limit: Int,
    ): List<MemoryEntry> {
        if (tokens.isEmpty() || limit <= 0) return emptyList()
        // `_searchMemoriesMatchAny`: hits >= 1, ordered by hits desc, updatedAt
        // desc, id asc. LIKE escaping only exists to protect the SQL and is a
        // no-op for a plain substring match, so the raw tokens are used.
        return provider.visibleFor(assistantId)
            .filter { it.type == type }
            .map { entry ->
                val hits = tokens.count { MemoryEntry.normalizeContent(entry.content).contains(it) }
                entry to hits
            }
            .filter { it.second >= 1 }
            .sortedWith(
                compareByDescending<Pair<MemoryEntry, Int>> { it.second }
                    .thenByDescending { it.first.updatedAt }
                    .thenBy { it.first.id },
            )
            .take(limit)
            .map { it.first }
    }

    override fun visibleByType(assistantId: String?, type: MemoryType): List<MemoryEntry> =
        provider.visibleFor(assistantId).filter { it.type == type }

    override fun findExact(assistantId: String?, type: MemoryType, contentNormalized: String): MemoryEntry? =
        provider.visibleFor(assistantId).firstOrNull {
            it.type == type && MemoryEntry.normalizeContent(it.content) == contentNormalized
        }

    override fun byIds(ids: List<String>): List<MemoryEntry> {
        if (ids.isEmpty()) return emptyList()
        val wanted = ids.toSet()
        return provider.entries.filter { it.id in wanted }
    }

    override fun create(
        scope: MemoryScope,
        assistantId: String?,
        type: MemoryType,
        content: String,
        source: MemorySource,
    ): MemoryEntry = provider.create(scope, assistantId, type, content, source)

    override fun updateContent(id: String, content: String): MemoryEntry? = provider.updateContent(id, content)

    override fun archive(id: String): Boolean = provider.archive(id)

    override fun linkBidirectional(a: String, b: String) = provider.linkBidirectional(a, b)
}

/**
 * Smart Add judge. Stateless apart from the repository; every entry point takes
 * the LLM call as a parameter so the caller decides which model (if any) runs.
 */
class MemorySmartAdd(private val repository: SmartAddRepository) {

    // ---- prompt building ----

    fun buildPerItemPrompt(
        lang: MemoryPromptLang,
        type: MemoryType,
        newInfo: String,
        entriesText: String,
        overrideZh: String? = null,
        overrideEn: String? = null,
    ): String = resolvePerItemTemplate(lang, overrideZh, overrideEn)
        .replace("{{type}}", type.wire)
        .replace("{{newInfo}}", newInfo)
        .replace("{{entriesText}}", entriesText)

    fun buildBatchPrompt(
        lang: MemoryPromptLang,
        itemsText: String,
        entriesText: String,
        overrideZh: String? = null,
        overrideEn: String? = null,
    ): String = resolveBatchTemplate(lang, overrideZh, overrideEn)
        .replace("{{itemsText}}", itemsText)
        .replace("{{entriesText}}", entriesText)

    /** Candidate retrieval: token hits, padded with recent same-type entries. */
    fun candidatesFor(assistantId: String?, type: MemoryType, newInfo: String): List<MemoryEntry> {
        val tokens = MemoryTokenizer.tokenize(newInfo)
        val base = if (tokens.isEmpty()) {
            mutableListOf()
        } else {
            repository.searchAny(assistantId, tokens, type, CANDIDATE_LIMIT).toMutableList()
        }
        if (base.size >= CANDIDATE_LIMIT) return base

        val recent = repository.visibleByType(assistantId, type)
            .sortedWith(compareByDescending<MemoryEntry> { it.updatedAt }.thenBy { it.id })
        val seen = base.map { it.id }.toMutableSet()
        for (entry in recent) {
            if (base.size >= CANDIDATE_LIMIT) break
            if (!seen.add(entry.id)) continue
            base.add(entry)
        }
        return base
    }

    /**
     * Exact duplicate → SKIP; else NEW. This is the degraded path used when no
     * memory model is configured or the judge answered with unusable JSON.
     */
    fun degradeDecision(
        visibilityAssistantId: String?,
        type: MemoryType,
        content: String,
    ): SmartAddDecision {
        val exact = repository.findExact(visibilityAssistantId, type, MemoryEntry.normalizeContent(content))
        return if (exact != null) {
            SmartAddDecision(SmartAddAction.SKIP, targetId = exact.id, degraded = true)
        } else {
            SmartAddDecision(SmartAddAction.NEW, degraded = true)
        }
    }

    fun applyDecision(
        item: SmartAddItem,
        decision: SmartAddDecision,
        candidateIds: Set<String>,
        source: MemorySource,
        mergeableIds: Set<String>? = null,
        traceStep: MemoryTraceStep? = null,
    ): SmartAddResult {
        val normalized = normalizeDecision(decision, candidateIds, mergeableIds)
        val owner = if (item.scope == MemoryScope.assistant) item.assistantId else null
        val typeLabel = item.type.wire
        return when (normalized.action) {
            SmartAddAction.SKIP -> SmartAddResult(
                action = SmartAddAction.SKIP,
                id = normalized.targetId,
                reason = if (normalized.degraded) "duplicate" else null,
            )

            SmartAddAction.NEW -> {
                val created = repository.create(item.scope, owner, item.type, item.content, source)
                for (relatedId in normalized.relatedIds) repository.linkBidirectional(created.id, relatedId)
                traceStep?.addMutation(
                    MemoryTraceMutation(
                        kind = MemoryTraceMutationKind.MEMORY_CREATED,
                        targetId = created.id,
                        label = "$typeLabel · ${item.scope.wire}",
                        after = created.content,
                    ),
                )
                for (relatedId in normalized.relatedIds) {
                    traceStep?.addMutation(
                        MemoryTraceMutation(
                            kind = MemoryTraceMutationKind.MEMORY_LINKED,
                            targetId = created.id,
                            label = relatedId,
                        ),
                    )
                }
                SmartAddResult(SmartAddAction.NEW, id = created.id, content = created.content)
            }

            SmartAddAction.MERGE -> {
                val before = if (traceStep == null) null else contentBefore(normalized.targetId)
                val merged = normalized.mergedContent!!.trim()
                val updated = repository.updateContent(normalized.targetId!!, merged)
                traceStep?.addMutation(
                    MemoryTraceMutation(
                        kind = MemoryTraceMutationKind.MEMORY_MERGED,
                        targetId = updated?.id ?: normalized.targetId,
                        label = typeLabel,
                        before = before,
                        after = updated?.content ?: merged,
                    ),
                )
                SmartAddResult(
                    action = SmartAddAction.MERGE,
                    id = updated?.id ?: normalized.targetId,
                    content = updated?.content ?: merged,
                )
            }

            SmartAddAction.CONFLICT -> {
                val oldId = normalized.targetId!!
                val before = if (traceStep == null) null else contentBefore(oldId)
                repository.archive(oldId)
                val created = repository.create(item.scope, owner, item.type, item.content, source)
                repository.linkBidirectional(created.id, oldId)
                for (relatedId in normalized.relatedIds) {
                    if (relatedId == oldId) continue
                    repository.linkBidirectional(created.id, relatedId)
                }
                traceStep?.addMutation(
                    MemoryTraceMutation(
                        kind = MemoryTraceMutationKind.MEMORY_ARCHIVED,
                        targetId = oldId,
                        label = typeLabel,
                        before = before,
                    ),
                )
                traceStep?.addMutation(
                    MemoryTraceMutation(
                        kind = MemoryTraceMutationKind.MEMORY_CREATED,
                        targetId = created.id,
                        label = "$typeLabel · ${item.scope.wire}",
                        after = created.content,
                    ),
                )
                SmartAddResult(
                    action = SmartAddAction.CONFLICT,
                    id = created.id,
                    content = created.content,
                    reason = oldId,
                )
            }
        }
    }

    /** An entry's current content, for the trace's before/after values only. */
    private fun contentBefore(id: String?): String? {
        if (id == null) return null
        return runCatching { repository.byIds(listOf(id)).firstOrNull()?.content }.getOrNull()
    }

    /** One item (`memory_update` / perItem mode). */
    suspend fun addOne(
        item: SmartAddItem,
        visibilityAssistantId: String,
        source: MemorySource,
        lang: MemoryPromptLang,
        llmCall: (suspend (String) -> String)? = null,
        overrideZh: String? = null,
        overrideEn: String? = null,
        traceStep: MemoryTraceStep? = null,
    ): SmartAddResult {
        // Fast path: exact duplicate.
        val exact = repository.findExact(
            visibilityAssistantId,
            item.type,
            MemoryEntry.normalizeContent(item.content),
        )
        if (exact != null) {
            return SmartAddResult(SmartAddAction.SKIP, id = exact.id, reason = "duplicate")
        }

        val candidates = candidatesFor(visibilityAssistantId, item.type, item.content)
        val candidateIds = candidates.map { it.id }.toSet()
        val mergeableIds = candidates
            .filter { it.scope == item.scope && it.assistantId == item.assistantId }
            .map { it.id }
            .toSet()

        val decision = if (llmCall == null) {
            degradeDecision(visibilityAssistantId, item.type, item.content)
        } else {
            val prompt = buildPerItemPrompt(
                lang = lang,
                type = item.type,
                newInfo = item.content,
                entriesText = formatEntriesPerItem(candidates),
                overrideZh = overrideZh,
                overrideEn = overrideEn,
            )
            traceStep?.appendPrompt(prompt)
            val raw = runCatching { llmCall(prompt) }.getOrNull()
            if (raw == null) {
                traceStep?.appendResponse("<request failed>")
            } else {
                traceStep?.appendResponse(raw)
            }
            val parsed = raw?.let { parsePerItem(it) }
            parsed ?: degradeDecision(visibilityAssistantId, item.type, item.content)
        }

        val result = applyDecision(
            item = item,
            decision = decision,
            candidateIds = candidateIds,
            mergeableIds = mergeableIds,
            source = source,
            traceStep = traceStep,
        )
        traceStep?.parsedResult = result.toToolJson().toString()
        return result
    }

    /** Several items (batched or perItem mode). */
    suspend fun addMany(
        items: List<SmartAddItem>,
        visibilityAssistantId: String,
        source: MemorySource,
        lang: MemoryPromptLang,
        mode: String,
        llmCall: (suspend (String) -> String)? = null,
        perItemOverrideZh: String? = null,
        perItemOverrideEn: String? = null,
        batchOverrideZh: String? = null,
        batchOverrideEn: String? = null,
        traceStep: MemoryTraceStep? = null,
    ): SmartAddBatchResult {
        if (items.isEmpty()) return SmartAddBatchResult(emptyList(), false)

        if (mode != SMART_ADD_MODE_BATCHED || llmCall == null) {
            val results = mutableListOf<SmartAddResult>()
            var identityChanged = false
            for (item in items) {
                val result = addOne(
                    item = item,
                    visibilityAssistantId = visibilityAssistantId,
                    source = source,
                    lang = lang,
                    llmCall = llmCall,
                    overrideZh = perItemOverrideZh,
                    overrideEn = perItemOverrideEn,
                    traceStep = traceStep,
                )
                results.add(result)
                if (item.type == MemoryType.identity && result.action != SmartAddAction.SKIP) {
                    identityChanged = true
                }
            }
            return SmartAddBatchResult(results, identityChanged)
        }

        // Batched path: collect candidates first, then ask once for all items
        // that still need a decision (exact duplicates are already resolved).
        val perItemCandidates = mutableListOf<List<MemoryEntry>>()
        val union = LinkedHashMap<String, MemoryEntry>()
        val decisions = arrayOfNulls<SmartAddDecision>(items.size)

        items.forEachIndexed { index, item ->
            val exact = repository.findExact(
                visibilityAssistantId,
                item.type,
                MemoryEntry.normalizeContent(item.content),
            )
            if (exact != null) {
                perItemCandidates.add(emptyList())
                decisions[index] = SmartAddDecision(SmartAddAction.SKIP, targetId = exact.id)
                return@forEachIndexed
            }
            val candidates = candidatesFor(visibilityAssistantId, item.type, item.content)
            perItemCandidates.add(candidates)
            for (entry in candidates) union[entry.id] = entry
        }

        val pending = items.indices.filter { decisions[it] == null }
        if (pending.isNotEmpty()) {
            val prompt = buildBatchPrompt(
                lang = lang,
                itemsText = formatItemsText(pending.map { items[it] }),
                entriesText = formatEntriesBatched(union.values.toList()),
                overrideZh = batchOverrideZh,
                overrideEn = batchOverrideEn,
            )
            traceStep?.appendPrompt(prompt)
            val raw = runCatching { llmCall(prompt) }.getOrNull()
            traceStep?.appendResponse(raw ?: "<request failed>")
            val parsed = raw?.let { parseBatch(it, pending.size) }
            pending.forEachIndexed { slot, index ->
                decisions[index] = parsed?.getOrNull(slot)
                    ?: degradeDecision(visibilityAssistantId, items[index].type, items[index].content)
            }
        }

        val results = mutableListOf<SmartAddResult>()
        var identityChanged = false
        items.forEachIndexed { index, item ->
            val candidates = perItemCandidates[index]
            val candidateIds = candidates.map { it.id }.toSet()
            val mergeableIds = candidates
                .filter { it.scope == item.scope && it.assistantId == item.assistantId }
                .map { it.id }
                .toSet()
            val decision = decisions[index]
                ?: degradeDecision(visibilityAssistantId, item.type, item.content)
            val result = applyDecision(
                item = item,
                decision = decision,
                candidateIds = candidateIds,
                mergeableIds = mergeableIds,
                source = source,
                traceStep = traceStep,
            )
            results.add(result)
            if (item.type == MemoryType.identity && result.action != SmartAddAction.SKIP) {
                identityChanged = true
            }
        }
        return SmartAddBatchResult(results, identityChanged)
    }

    companion object {
        /** `MemorySmartAdd.candidateLimit`. */
        const val CANDIDATE_LIMIT = 5

        /** Assistant memorySmartAddMode wire value for the single-request path. */
        const val SMART_ADD_MODE_BATCHED = "batched"

        /** An empty override falls back to the built-in prompt (settings_provider getters). */
        fun resolvePerItemTemplate(lang: MemoryPromptLang, overrideZh: String?, overrideEn: String?): String {
            val override = if (lang == MemoryPromptLang.zh) overrideZh?.trim() else overrideEn?.trim()
            if (!override.isNullOrEmpty()) return override
            return MemoryPrompts.smartAddFor(lang)
        }

        fun resolveBatchTemplate(lang: MemoryPromptLang, overrideZh: String?, overrideEn: String?): String {
            val override = if (lang == MemoryPromptLang.zh) overrideZh?.trim() else overrideEn?.trim()
            if (!override.isNullOrEmpty()) return override
            return MemoryPrompts.smartAddBatchFor(lang)
        }

        fun formatEntriesPerItem(entries: List<MemoryEntry>): String =
            entries.joinToString("\n") { "${it.id} ${it.content}" }

        fun formatEntriesBatched(entries: List<MemoryEntry>): String =
            entries.joinToString("\n") { "${it.id} (${it.type.wire}) ${it.content}" }

        fun formatItemsText(items: List<SmartAddItem>): String =
            items.mapIndexed { index, item ->
                "[${index + 1}] ${item.type.wire} ${item.content}"
            }.joinToString("\n")

        /**
         * Pulls a JSON value out of a model answer: an optional ```json fence,
         * then the outermost object/array. Null when nothing parses.
         */
        fun extractJson(response: String): JsonElement? {
            var text = response.trim()
            FENCE.find(text)?.let { text = it.groupValues[1].trim() }
            val startObj = text.indexOf('{')
            val startArr = text.indexOf('[')
            if (startObj < 0 && startArr < 0) return null
            val start = when {
                startObj < 0 -> startArr
                startArr < 0 -> startObj
                else -> minOf(startObj, startArr)
            }
            val end = maxOf(text.lastIndexOf('}'), text.lastIndexOf(']'))
            if (end <= start) return null
            return runCatching { Json.parseToJsonElement(text.substring(start, end + 1)) }.getOrNull()
        }

        /** Per-item JSON answer; null means the caller should degrade. */
        fun parsePerItem(response: String): SmartAddDecision? {
            val decoded = extractJson(response) as? JsonObject ?: return null
            val action = parseAction(decoded) ?: return null
            return SmartAddDecision(
                action = action,
                targetId = targetIdOf(decoded),
                mergedContent = (decoded["mergedContent"] as? JsonPrimitive)?.contentOrNull,
                relatedIds = relatedIdsOf(decoded),
            )
        }

        /** Batched JSON answer; a missing index stays null so it can degrade. */
        fun parseBatch(response: String, count: Int): List<SmartAddDecision?>? {
            val decoded = extractJson(response) as? JsonObject ?: return null
            val results = decoded["results"] as? JsonArray ?: return null

            val byIndex = HashMap<Int, SmartAddDecision>()
            for (element in results) {
                val item = element as? JsonObject ?: continue
                val index = (item["index"] as? JsonPrimitive)?.let { primitive ->
                    primitive.intOrNull ?: primitive.content.toIntOrNull()
                } ?: continue
                if (index < 1 || index > count) continue
                val action = parseAction(item) ?: continue
                byIndex[index] = SmartAddDecision(
                    action = action,
                    targetId = targetIdOf(item),
                    mergedContent = (item["mergedContent"] as? JsonPrimitive)?.contentOrNull,
                    relatedIds = relatedIdsOf(item),
                )
            }
            return (1..count).map { byIndex[it] }
        }

        /**
         * Validates a decision against the candidates. MERGE / CONFLICT targets
         * must be mergeable (same scope and owner), so an assistant-scoped item
         * cannot rewrite a global entry; `relatedIds` only have to be candidates.
         */
        fun normalizeDecision(
            decision: SmartAddDecision,
            candidateIds: Set<String>,
            mergeableIds: Set<String>? = null,
        ): SmartAddDecision {
            var action = decision.action
            var targetId = decision.targetId
            var merged = decision.mergedContent
            val related = decision.relatedIds.filter { it in candidateIds }
            val mergeTargets = mergeableIds ?: candidateIds

            if (action == SmartAddAction.MERGE || action == SmartAddAction.CONFLICT) {
                if (targetId == null || targetId !in mergeTargets) {
                    action = SmartAddAction.NEW
                    targetId = null
                    merged = null
                }
            }
            if (action == SmartAddAction.MERGE && merged?.trim().isNullOrEmpty()) {
                return SmartAddDecision(SmartAddAction.SKIP, degraded = true)
            }
            return SmartAddDecision(
                action = action,
                targetId = targetId,
                mergedContent = merged,
                relatedIds = related,
                degraded = decision.degraded,
            )
        }

        private fun parseAction(obj: JsonObject): SmartAddAction? =
            when ((obj["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.uppercase()) {
                "NEW" -> SmartAddAction.NEW
                "MERGE" -> SmartAddAction.MERGE
                "CONFLICT" -> SmartAddAction.CONFLICT
                "SKIP" -> SmartAddAction.SKIP
                else -> null
            }

        private fun targetIdOf(obj: JsonObject): String? {
            val raw = (obj["targetId"] as? JsonPrimitive)?.contentOrNull
            return if (raw.isNullOrEmpty() || raw == "null") null else raw
        }

        private fun relatedIdsOf(obj: JsonObject): List<String> =
            (obj["relatedIds"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { id -> id.isNotEmpty() } }
                ?: emptyList()

        private val FENCE = Regex("```(?:json)?\\s*([\\s\\S]*?)```", RegexOption.IGNORE_CASE)
    }
}

/**
 * memory_tokenizer.dart — Smart Add candidate tokenizer (§12.6) and LIKE
 * escaping (§5.9). English/number words of length ≥ 2 minus stopwords, CJK
 * bigrams minus stopword grams, at most 8 tokens in encounter order.
 */
object MemoryTokenizer {

    private val CJK_STOPWORDS = setOf(
        "用户", "的", "了", "是", "在", "和", "与", "会", "要", "对", "这", "那", "他", "她", "它",
    )

    private val ENGLISH_STOPWORDS = setOf(
        "the", "a", "an", "of", "to", "and", "or", "in", "on", "for", "with",
        "user", "users", "prefer", "prefers",
    )

    private val LATIN_WORD = Regex("[a-z0-9]{2,}")

    /** The Dart `[\u3040-\u30ff\u3400-\u9fff\uf900-\ufaff]` class — BMP ranges only. */
    private fun isCjk(ch: Char): Boolean {
        val code = ch.code
        return code in 0x3040..0x30ff || code in 0x3400..0x9fff || code in 0xf900..0xfaff
    }

    fun tokenize(text: String): List<String> {
        val lower = text.lowercase()
        val tokens = LinkedHashSet<String>()

        var index = 0
        while (index < lower.length && tokens.size < MAX_TOKENS) {
            if (isCjk(lower[index])) {
                val start = index
                while (index < lower.length && isCjk(lower[index])) index++
                addCjkBigrams(lower.substring(start, index), tokens)
            } else {
                val start = index
                while (index < lower.length && !isCjk(lower[index])) index++
                addLatinWords(lower.substring(start, index), tokens)
            }
        }
        return tokens.toList()
    }

    /** Escape `%`, `_` and `\` for SQL `LIKE ... ESCAPE '\'` (§5.9). */
    fun escapeLike(token: String): String =
        token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun addCjkBigrams(run: String, tokens: MutableSet<String>) {
        if (run.length < 2) return
        for (i in 0 until run.length - 1) {
            if (tokens.size >= MAX_TOKENS) return
            val gram = run.substring(i, i + 2)
            if (CJK_STOPWORDS.any { gram.contains(it) }) continue
            tokens.add(gram)
        }
    }

    private fun addLatinWords(run: String, tokens: MutableSet<String>) {
        for (match in LATIN_WORD.findAll(run)) {
            if (tokens.size >= MAX_TOKENS) return
            val word = match.value
            if (word in ENGLISH_STOPWORDS) continue
            tokens.add(word)
        }
    }

    private const val MAX_TOKENS = 8
}
