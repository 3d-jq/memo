package com.psyche.memo.provider

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.MemoryPromptLang
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * memory_trace.dart — the session ring buffer, and the traces the pipeline and
 * the memory tools actually leave behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryTraceTest {

    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
        container.database.writableDatabase.execSQL("DELETE FROM preference_rows")
        MemoryTraceRecorder.clear()
        MemoryTraceRecorder.enabled = true
    }

    /**
     * A distinct conversation per label: identical no-op runs coalesce by
     * design (see the coalescing test), which would defeat the counting tests.
     */
    private fun beginTrace(label: String = "run"): MemoryTraceHandle =
        MemoryTraceRecorder.begin(
            trigger = MemoryTraceTrigger.AUTO_TURNS,
            scope = MemoryTraceScope.GLOBAL,
            conversationId = "c_$label",
            conversationTitle = label,
        )!!

    // ---- the recorder --------------------------------------------------------

    @Test
    fun `recording off yields no handle and drops the buffer`() {
        beginTrace().commit()
        assertEquals(1, MemoryTraceRecorder.length)

        MemoryTraceRecorder.enabled = false
        assertEquals(0, MemoryTraceRecorder.length)
        assertNull(MemoryTraceRecorder.begin(MemoryTraceTrigger.MANUAL, MemoryTraceScope.GLOBAL))
    }

    @Test
    fun `traces come back newest first`() {
        beginTrace("first").commit()
        beginTrace("second").commit()
        assertEquals(listOf("first", "second"), MemoryTraceRecorder.traces.map { it.conversationTitle }.reversed())
        assertEquals("second", MemoryTraceRecorder.traces.first().conversationTitle)
    }

    @Test
    fun `the buffer keeps at most twenty-four traces`() {
        repeat(MemoryTraceRecorder.MAX_TRACES + 4) { index -> beginTrace("t$index").commit() }
        assertEquals(MemoryTraceRecorder.MAX_TRACES, MemoryTraceRecorder.length)
        assertEquals("t${MemoryTraceRecorder.MAX_TRACES + 3}", MemoryTraceRecorder.traces.first().conversationTitle)
    }

    @Test
    fun `repeated identical no-op triggers coalesce`() {
        repeat(3) {
            val handle = MemoryTraceRecorder.begin(
                trigger = MemoryTraceTrigger.AUTO_TURNS,
                scope = MemoryTraceScope.GLOBAL,
                conversationId = "c1",
            )!!
            handle.commit(error = "below_threshold")
        }
        assertEquals(1, MemoryTraceRecorder.length)
        assertEquals(3, MemoryTraceRecorder.traces.single().repeatCount)
    }

    @Test
    fun `a run with steps is never coalesced`() {
        repeat(2) {
            val handle = beginTrace()
            handle.beginStep(MemoryTraceStepKind.GATEKEEPER)?.finish(MemoryTraceStepStatus.SUCCESS)
            handle.commit(error = "below_threshold")
        }
        assertEquals(2, MemoryTraceRecorder.length)
    }

    @Test
    fun `committing fails any step still running`() {
        val handle = beginTrace()
        handle.beginStep(MemoryTraceStepKind.EXTRACT)
        handle.commit(error = "boom")

        val step = MemoryTraceRecorder.traces.single().steps.single()
        assertEquals(MemoryTraceStepStatus.FAILED, step.status)
        assertEquals("boom", step.error)
        assertNotNull(step.endedAt)
    }

    @Test
    fun `prompt and response parts are appended in order`() {
        val handle = beginTrace()
        val step = handle.beginStep(MemoryTraceStepKind.SMART_ADD)!!
        step.appendPrompt("first")
        step.appendPrompt("second")
        step.appendResponse("a")
        step.appendResponse("b")

        assertTrue(step.prompt.startsWith("first"))
        assertTrue(step.prompt.contains("#2"))
        assertTrue(step.prompt.endsWith("second"))
        assertTrue(step.rawResponse.contains("#2"))
    }

    // ---- the pipeline --------------------------------------------------------

    private fun assistant() = Assistant(
        id = "a1",
        name = "Test",
        enableMemory = true,
        autoOrganizeMemory = true,
        memoryOrganizeEveryNTurns = 1,
        memorySmartAddMode = "batched",
        memoryWriteScope = "alwaysGlobal",
    )

    private fun settings() = MemoryPipelineSettings(
        lang = MemoryPromptLang.en,
        injectionMaxItems = 10,
        gateZh = "GATE-ZH", gateEn = "GATE-EN",
        extractZh = "EXTRACT-ZH", extractEn = "EXTRACT-EN",
        smartAddZh = "ADD-ZH", smartAddEn = "ADD-EN",
        smartAddBatchZh = "BATCH-ZH", smartAddBatchEn = "BATCH-EN",
        distillZh = "DISTILL-ZH", distillEn = "DISTILL-EN",
    )

    private fun window() = listOf(
        com.psyche.memo.data.model.ChatMessage(
            id = "m1", role = "user", parts = listOf(com.psyche.memo.data.model.TextPart("remember I like tea")),
            timestamp = 0L, conversationId = "c1", groupId = "g1", version = 0, messageOrder = 0,
        ) to 0,
        com.psyche.memo.data.model.ChatMessage(
            id = "m2", role = "assistant", parts = listOf(com.psyche.memo.data.model.TextPart("noted")),
            timestamp = 1L, conversationId = "c1", groupId = "g2", version = 0, messageOrder = 1,
        ) to 1,
    )

    private fun scriptedLlm(): suspend (String) -> String = { prompt ->
        when {
            prompt.contains("GATE-EN") -> "<user_memory>true</user_memory>"
            prompt.contains("EXTRACT-EN") -> "<extracted><item type=\"identity\">Likes tea</item></extracted>"
            prompt.contains("BATCH-EN") -> """{"results":[{"index":1,"action":"NEW"}]}"""
            prompt.contains("DISTILL-EN") -> """{"fields":[{"key":"preferred_name","value":"Dee"}]}"""
            else -> ""
        }
    }

    @Test
    fun `a full run records every stage and its changes`() = runBlocking {
        val trace = beginTrace("full run")
        container.memoryPipeline.processWindow(
            conversationId = "c1",
            assistant = assistant(),
            settings = settings(),
            watermark = -1,
            window = window(),
            llmCall = scriptedLlm(),
            trace = trace,
        )
        trace.commit(advanced = true)

        val recorded = MemoryTraceRecorder.traces.single()
        assertEquals(
            listOf(
                MemoryTraceStepKind.GATEKEEPER,
                MemoryTraceStepKind.EXTRACT,
                MemoryTraceStepKind.SMART_ADD,
                MemoryTraceStepKind.PROFILE_DISTILLER,
            ),
            recorded.steps.map { it.kind },
        )
        assertTrue(recorded.steps.all { it.status == MemoryTraceStepStatus.SUCCESS })
        // The prompt and the raw answer are kept for each model call.
        assertTrue(recorded.steps[0].prompt.contains("GATE-EN"))
        assertTrue(recorded.steps[0].rawResponse.contains("<user_memory>true"))
        assertEquals("WORTH_REMEMBERING", recorded.steps[0].parsedResult)
        // The window the run processed travels with the trace.
        assertEquals(-1, recorded.watermark)
        assertEquals(2, recorded.windowSize)

        val kinds = recorded.steps.flatMap { step -> step.mutations.map { it.kind } }
        assertTrue(kinds.contains(MemoryTraceMutationKind.MEMORY_CREATED))
        assertTrue(kinds.contains(MemoryTraceMutationKind.PROFILE_FIELD_WRITTEN))
        val created = recorded.steps.flatMap { it.mutations }
            .first { it.kind == MemoryTraceMutationKind.MEMORY_CREATED }
        assertEquals("Likes tea", created.after)
        assertTrue(created.label!!.contains("identity"))
    }

    @Test
    fun `a gate skip records the stages it never reached`() = runBlocking {
        val trace = beginTrace("skip")
        container.memoryPipeline.processWindow(
            conversationId = "c1",
            assistant = assistant(),
            settings = settings(),
            watermark = -1,
            window = window(),
            llmCall = { "<user_memory>false</user_memory>" },
            trace = trace,
        )
        trace.commit(advanced = true)

        val recorded = MemoryTraceRecorder.traces.single()
        assertEquals(MemoryTraceStepStatus.SUCCESS, recorded.steps[0].status)
        assertEquals(
            listOf(MemoryTraceStepStatus.SKIPPED, MemoryTraceStepStatus.SKIPPED, MemoryTraceStepStatus.SKIPPED),
            recorded.steps.drop(1).map { it.status },
        )
        assertEquals(0, recorded.mutationCount)
    }

    // ---- the memory tool -----------------------------------------------------

    private fun assistantModel() = Assistant(
        id = "a1",
        name = "Test",
        enableMemory = true,
        memoryWriteScope = "alwaysGlobal",
    )

    private fun memoryUpdateArgs(content: String) =
        Json.parseToJsonElement("""{"type":"identity","content":"$content"}""") as JsonObject

    @Test
    fun `a memory tool call records a one-step trace`() = runBlocking {
        MemoryTools.handle(
            container = container,
            assistant = assistantModel(),
            conversationId = "c1",
            isTemporary = false,
            name = MemoryTools.MEMORY_UPDATE,
            args = memoryUpdateArgs("prefers tea"),
        )

        val recorded = MemoryTraceRecorder.traces.single()
        assertEquals(MemoryTraceTrigger.TOOL_CALL, recorded.trigger)
        assertEquals(MemoryTraceScope.GLOBAL, recorded.scope)
        val step = recorded.steps.single()
        assertEquals(MemoryTraceStepKind.MEMORY_TOOL, step.kind)
        assertEquals(MemoryTools.MEMORY_UPDATE, step.label)
        assertEquals(MemoryTraceStepStatus.SUCCESS, step.status)
        assertTrue(step.prompt.contains("prefers tea"))
        assertTrue(step.rawResponse.contains("NEW"))
    }

    @Test
    fun `a failing memory tool call records the error`() = runBlocking {
        MemoryTools.handle(
            container = container,
            assistant = assistantModel(),
            conversationId = "c1",
            isTemporary = false,
            name = MemoryTools.MEMORY_UPDATE,
            args = Json.parseToJsonElement("""{"type":"nope","content":"x"}""") as JsonObject,
        )

        val step = MemoryTraceRecorder.traces.single().steps.single()
        assertEquals(MemoryTraceStepStatus.FAILED, step.status)
        assertNotNull(step.error)
        assertTrue(MemoryTraceRecorder.traces.single().hasError)
    }

    @Test
    fun `a temporary chat records no tool trace`() = runBlocking {
        MemoryTools.handle(
            container = container,
            assistant = assistantModel(),
            conversationId = "c1",
            isTemporary = true,
            name = MemoryTools.MEMORY_READ,
            args = Json.parseToJsonElement("{}") as JsonObject,
        )
        assertTrue(MemoryTraceRecorder.traces.isEmpty())
    }

    @Test
    fun `recording off leaves the pipeline silent`() = runBlocking {
        MemoryTraceRecorder.enabled = false
        container.memoryPipeline.processWindow(
            conversationId = "c1",
            assistant = assistant(),
            settings = settings(),
            watermark = -1,
            window = window(),
            llmCall = scriptedLlm(),
            trace = null,
        )
        assertTrue(MemoryTraceRecorder.traces.isEmpty())
        assertFalse(MemoryTraceRecorder.enabled)
    }
}
