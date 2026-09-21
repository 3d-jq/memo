package com.psyche.memo.provider

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * memory_trace.dart — what a background memory run did, step by step, with the
 * exact prompts and raw responses so a wrong memory can be explained.
 *
 * Traces hold full prompts and responses (a single organize run can carry ~5
 * prompts built from a 12 KB conversation window plus their responses), so the
 * recorder is an in-memory ring buffer of [MemoryTraceRecorder.MAX_TRACES] and
 * nothing is ever persisted.
 */
enum class MemoryTraceTrigger {
    /** Automatic organize after the assistant's turn threshold was reached. */
    AUTO_TURNS,

    /** The user pressed "organize now". */
    MANUAL,

    /** A model tool call (`memory_*` / `chat_search`). */
    TOOL_CALL,

    /** Background conversation-summary generation. */
    CONVERSATION_SUMMARY;

    val wire: String
        get() = when (this) {
            AUTO_TURNS -> "autoTurns"
            MANUAL -> "manual"
            TOOL_CALL -> "toolCall"
            CONVERSATION_SUMMARY -> "conversationSummary"
        }
}

/** Whether the run wrote into assistant-scoped or global memory. */
enum class MemoryTraceScope { ASSISTANT, GLOBAL }

/** `memoryTraceScopeOf` — the assistant's write policy as a scope label. */
fun memoryTraceScopeOf(writeScope: String?): MemoryTraceScope = when (writeScope) {
    "alwaysAssistant", "toolDefaultAssistant" -> MemoryTraceScope.ASSISTANT
    else -> MemoryTraceScope.GLOBAL
}

/** One stage of background memory work. */
enum class MemoryTraceStepKind {
    GATEKEEPER,
    EXTRACT,
    SMART_ADD,
    PROFILE_DISTILLER,
    CONVERSATION_SUMMARY,
    CHAT_SEARCH,
    MEMORY_TOOL;

    val wire: String
        get() = when (this) {
            GATEKEEPER -> "gatekeeper"
            EXTRACT -> "extract"
            SMART_ADD -> "smartAdd"
            PROFILE_DISTILLER -> "profileDistiller"
            CONVERSATION_SUMMARY -> "conversationSummary"
            CHAT_SEARCH -> "chatSearch"
            MEMORY_TOOL -> "memoryTool"
        }
}

enum class MemoryTraceStepStatus { RUNNING, SKIPPED, SUCCESS, FAILED }

/** A concrete change the run applied to stored state. */
enum class MemoryTraceMutationKind {
    MEMORY_CREATED,
    MEMORY_MERGED,
    MEMORY_EDITED,
    MEMORY_ARCHIVED,
    MEMORY_LINKED,
    PROFILE_FIELD_WRITTEN,
    PROFILE_FIELD_CLEARED,
    CONVERSATION_SUMMARY_WRITTEN;
}

data class MemoryTraceMutation(
    val kind: MemoryTraceMutationKind,
    /** Memory entry id / profile field key / conversation id. */
    val targetId: String? = null,
    /** Extra qualifier such as the memory type or the scope. */
    val label: String? = null,
    val before: String? = null,
    val after: String? = null,
)

/** One recorded pipeline stage, with everything needed to debug it. */
class MemoryTraceStep(
    val kind: MemoryTraceStepKind,
    /** Free-form qualifier, e.g. the tool name for [MemoryTraceStepKind.MEMORY_TOOL]. */
    val label: String? = null,
    val startedAt: Long = System.currentTimeMillis(),
) {
    var endedAt: Long? = null
        private set
    var status: MemoryTraceStepStatus = MemoryTraceStepStatus.RUNNING
        private set

    /** Exact prompt(s) sent to the model; multiple calls are appended in order. */
    val prompt: String get() = promptBuffer.toString()

    /** Raw model response(s), in the same order as [prompt]. */
    val rawResponse: String get() = responseBuffer.toString()

    /** Human-readable parsed result (JSON when structured). */
    var parsedResult: String? = null

    var error: String? = null
        private set

    val mutations = mutableListOf<MemoryTraceMutation>()

    private val promptBuffer = StringBuilder()
    private val responseBuffer = StringBuilder()
    private var promptParts = 0
    private var responseParts = 0

    val durationMs: Long? get() = endedAt?.let { it - startedAt }

    fun appendPrompt(text: String) {
        promptParts++
        if (promptParts > 1) promptBuffer.append("\n\n─── #").append(promptParts).append(" ───\n\n")
        promptBuffer.append(text)
    }

    fun appendResponse(text: String) {
        responseParts++
        if (responseParts > 1) responseBuffer.append("\n\n─── #").append(responseParts).append(" ───\n\n")
        responseBuffer.append(text)
    }

    fun addMutation(mutation: MemoryTraceMutation) {
        mutations.add(mutation)
    }

    fun finish(status: MemoryTraceStepStatus, error: String? = null) {
        this.status = status
        if (error != null) this.error = error
        endedAt = System.currentTimeMillis()
    }
}

/** Everything that happened for one background memory trigger. */
class MemoryTrace(
    val id: String,
    val trigger: MemoryTraceTrigger,
    val scope: MemoryTraceScope,
    val startedAt: Long = System.currentTimeMillis(),
    val conversationId: String? = null,
    val conversationTitle: String? = null,
    /** Null for runs that are not bound to an assistant. */
    val assistantId: String? = null,
    val assistantName: String? = null,
) {
    /** Observable: a coalesced no-op updates it after publication. */
    var endedAt: Long? by mutableStateOf(null)
        internal set

    /** Watermark (message order) the run started from. */
    var watermark: Int? = null
    var windowStartOrder: Int? = null
    var windowEndOrder: Int? = null
    var windowSize: Int = 0

    val steps = mutableListOf<MemoryTraceStep>()

    var advanced: Boolean = false
        internal set
    var forcedAdvance: Boolean = false
        internal set

    /** Pipeline error / skip reason code, e.g. `below_threshold`. */
    var error: String? = null
        internal set

    /** How many identical consecutive no-op triggers this entry stands for. */
    var repeatCount: Int by mutableStateOf(1)
        internal set

    val durationMs: Long? get() = endedAt?.let { it - startedAt }

    val hasError: Boolean get() = !error.isNullOrEmpty()

    /** A trigger that never reached the model (gating / threshold checks). */
    val isNoOp: Boolean get() = steps.isEmpty()

    val mutationCount: Int get() = steps.sumOf { it.mutations.size }
}

/**
 * Live handle used by the pipeline to fill in a trace.
 *
 * Every method swallows its own failures: observability must never break the
 * pipeline.
 */
class MemoryTraceHandle internal constructor(
    private val recorder: MemoryTraceRecorder,
    val trace: MemoryTrace,
) {
    private var committed = false

    fun beginStep(kind: MemoryTraceStepKind, label: String? = null): MemoryTraceStep? = runCatching {
        val step = MemoryTraceStep(kind = kind, label = label)
        trace.steps.add(step)
        step
    }.getOrNull()

    fun setWindow(watermark: Int? = null, startOrder: Int? = null, endOrder: Int? = null, size: Int? = null) {
        runCatching {
            if (watermark != null) trace.watermark = watermark
            if (startOrder != null) trace.windowStartOrder = startOrder
            if (endOrder != null) trace.windowEndOrder = endOrder
            if (size != null) trace.windowSize = size
        }
    }

    /** Finalize the outcome and publish the trace. */
    fun commit(advanced: Boolean = false, forcedAdvance: Boolean = false, error: String? = null) {
        if (committed) return
        committed = true
        runCatching {
            trace.advanced = advanced
            trace.forcedAdvance = forcedAdvance
            trace.error = error
            trace.endedAt = System.currentTimeMillis()
            for (step in trace.steps) {
                if (step.status == MemoryTraceStepStatus.RUNNING) {
                    step.finish(MemoryTraceStepStatus.FAILED, error)
                }
            }
            recorder.publish(trace)
        }
    }
}

/**
 * In-memory ring buffer of recent background memory traces, observable from
 * Compose. Newest first; nothing is persisted.
 */
object MemoryTraceRecorder {

    /** Roughly a working session's worth of runs, bounded at a couple of MB. */
    const val MAX_TRACES = 24

    private var enabledState: Boolean by mutableStateOf(true)

    /**
     * Mirrors the user preference; switching it off drops the buffer.
     *
     * A property (not a `setEnabled` method) because the method would collide
     * with this property's own JVM setter.
     */
    var enabled: Boolean
        get() = enabledState
        set(value) {
            if (enabledState == value) return
            enabledState = value
            if (!value) buffer.clear()
        }

    private val buffer = mutableStateListOf<MemoryTrace>()
    private var sequence = 0

    /** Newest first. */
    val traces: List<MemoryTrace> get() = buffer.asReversed()

    val length: Int get() = buffer.size

    fun clear() {
        if (buffer.isEmpty()) return
        buffer.clear()
    }

    /** Start a trace, or return null when recording is off. */
    fun begin(
        trigger: MemoryTraceTrigger,
        scope: MemoryTraceScope,
        conversationId: String? = null,
        conversationTitle: String? = null,
        assistantId: String? = null,
        assistantName: String? = null,
    ): MemoryTraceHandle? {
        if (!enabled) return null
        sequence++
        val trace = MemoryTrace(
            id = "trace_${System.currentTimeMillis()}_$sequence",
            trigger = trigger,
            scope = scope,
            conversationId = conversationId,
            conversationTitle = conversationTitle?.takeIf { it.isNotBlank() },
            assistantId = assistantId,
            assistantName = assistantName,
        )
        return MemoryTraceHandle(this, trace)
    }

    /** Stores a finished trace (called by [MemoryTraceHandle.commit]). */
    fun publish(trace: MemoryTrace) {
        if (!enabled) return
        // Coalesce repeated no-op triggers (e.g. "below_threshold" after every
        // turn) so they cannot push real runs out of the buffer.
        val newest = buffer.lastOrNull()
        if (newest != null &&
            trace.isNoOp &&
            newest.isNoOp &&
            newest.trigger == trace.trigger &&
            newest.conversationId == trace.conversationId &&
            newest.error == trace.error
        ) {
            buffer[buffer.lastIndex] = newest.also {
                it.repeatCount++
                it.endedAt = trace.endedAt
            }
            return
        }
        buffer.add(trace)
        while (buffer.size > MAX_TRACES) buffer.removeAt(0)
    }
}
