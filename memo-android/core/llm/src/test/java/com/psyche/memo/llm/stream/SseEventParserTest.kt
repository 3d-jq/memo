package com.psyche.memo.llm.stream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SseEventParserTest {

    @Test
    fun singleDataEvent() {
        val parser = SseEventParser()
        val events = parser.add("data: {\"a\":1}\n\n")
        assertEquals(1, events.size)
        assertEquals("{\"a\":1}", events[0].data)
        assertNull(events[0].id)
    }

    @Test
    fun dataWithColonInValue() {
        val parser = SseEventParser()
        val events = parser.add("data: hello: world\n\n")
        assertEquals("hello: world", events[0].data)
    }

    @Test
    fun multilineDataJoinedWithNewline() {
        val parser = SseEventParser()
        val events = parser.add("data: line1\ndata: line2\n\n")
        assertEquals("line1\nline2", events[0].data)
    }

    @Test
    fun crlfHandled() {
        val parser = SseEventParser()
        val events = parser.add("data: x\r\n\r\n")
        assertEquals("x", events[0].data)
    }

    @Test
    fun crOnlyHandled() {
        val parser = SseEventParser()
        // CRLF data line followed by CRLF blank line => one event.
        val events = parser.add("data: x\r\n\r\n")
        assertEquals("x", events[0].data)
    }

    @Test
    fun trailingCrIsDeferredUntilNextChunk() {
        val parser = SseEventParser()
        val events = parser.add("data: x\r")
        assertTrue(events.isEmpty()) // CR is deferred
        val events2 = parser.add("\n\n")
        assertEquals("x", events2[0].data)
    }

    @Test
    fun idEventRetryFields() {
        val parser = SseEventParser()
        val events = parser.add("id: 42\nevent: ping\ndata: hello\n\n")
        assertEquals("42", events[0].id)
        assertEquals("ping", events[0].event)
        assertEquals("hello", events[0].data)
    }

    @Test
    fun emptyIdFieldIsEmptyString() {
        val parser = SseEventParser()
        val events = parser.add("id:\ndata: x\n\n")
        assertEquals("", events[0].id)
    }

    @Test
    fun commentLinesIgnored() {
        val parser = SseEventParser()
        val events = parser.add(": ping\n\n")
        assertTrue(events.isEmpty())
    }

    @Test
    fun bomStrippedAtStart() {
        val parser = SseEventParser()
        val events = parser.add("\uFEFFdata: x\n\n")
        assertEquals("x", events[0].data)
    }

    @Test
    fun doneEvent() {
        val parser = SseEventParser()
        val events = parser.add("data: [DONE]\n\n")
        assertEquals("[DONE]", events[0].data)
    }

    @Test
    fun finalFrameWithoutTrailingNewlineOnClose() {
        val parser = SseEventParser()
        assertTrue(parser.add("data: last").isEmpty())
        val events = parser.close()
        assertEquals("last", events[0].data)
    }

    @Test
    fun closeFlushesPending() {
        val parser = SseEventParser()
        parser.add("data: keep\n")
        val events = parser.close()
        assertEquals("keep", events[0].data)
    }

    @Test
    fun chunkSplitMidEvent() {
        val parser = SseEventParser()
        val events = parser.add("data: par")
        assertTrue(events.isEmpty())
        val events2 = parser.add("tial\n\n")
        assertEquals(1, events2.size)
        assertEquals("partial", events2[0].data)
    }

    @Test
    fun adjacentJsonRecoveryEnabled() {
        val parser = SseEventParser(recoverAdjacentJsonDataRecords = true)
        val events = parser.add("data: {\"a\":1}\ndata: {\"b\":2}\n\n")
        // Both are standalone JSON objects; recovery mode splits them.
        assertEquals(2, events.size)
        assertEquals("{\"a\":1}", events[0].data)
        assertEquals("{\"b\":2}", events[1].data)
    }

    @Test
    fun adjacentJsonRecoveryDisabledKeepsJoined() {
        val parser = SseEventParser(recoverAdjacentJsonDataRecords = false)
        val events = parser.add("data: {\"a\":1}\ndata: {\"b\":2}\n\n")
        assertEquals(1, events.size)
        assertEquals("{\"a\":1}\n{\"b\":2}", events[0].data)
    }

    @Test
    fun recoveryDoesNotSplitMultilineJsonText() {
        val parser = SseEventParser(recoverAdjacentJsonDataRecords = true)
        // First data line is not standalone JSON => no split.
        val events = parser.add("data: begin\ndata: text\n\n")
        assertEquals(1, events.size)
        assertEquals("begin\ntext", events[0].data)
    }

    @Test
    fun doneEventWithRecoveryEmitsImmediately() {
        val parser = SseEventParser(recoverAdjacentJsonDataRecords = true)
        val events = parser.add("data: [DONE]\n")
        assertEquals(1, events.size)
        assertEquals("[DONE]", events[0].data)
    }

    @Test
    fun recoveryCallbackReportsOnce() {
        var lastCount = -1
        var calls = 0
        val parser = SseEventParser(recoverAdjacentJsonDataRecords = true) { count ->
            calls++
            lastCount = count
        }
        // First data line is held; second standalone JSON line proves the held
        // payload was complete, so it is released alone (report count=1), then
        // the new line closes on the blank delimiter. Recovery fires once.
        val events = parser.add("data: {\"a\":1}\ndata: {\"b\":2}\n\n")
        assertEquals(2, events.size)
        assertEquals("{\"a\":1}", events[0].data)
        assertEquals("{\"b\":2}", events[1].data)
        assertEquals(1, calls)
        assertEquals(1, lastCount)
    }

    @Test
    fun takeEventsBeforeErrorReturnsRecovered() {
        val parser = SseEventParser(recoverAdjacentJsonDataRecords = true)
        parser.add("data: {\"a\":1}\n")
        val events = parser.takeEventsBeforeError()
        assertEquals(1, events.size)
    }
}
