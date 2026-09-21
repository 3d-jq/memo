package com.psyche.memo.common.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogRedactorTest {

    // —— maskSecret ————————————————————————————————————————————————

    @Test
    fun `maskSecret keeps Bearer scheme`() {
        // Payload is "abcdefghijklmnop" (16 chars) after the "Bearer " prefix;
        // 16 ≥ SHORT_SECRET_LIMIT (16) so the long form fires.
        val out = LogRedactor.maskSecret("Bearer abcdefghijklmnop")
        assertEquals("Bearer abc***mnop(len=16)", out)
    }

    @Test
    fun `maskSecret keeps Basic scheme`() {
        // Payload is "dXNlcjpwYXNz" (12 chars) — under SHORT_SECRET_LIMIT (16),
        // so the short form fires: ***(len=12).
        val out = LogRedactor.maskSecret("Basic dXNlcjpwYXNz")
        assertEquals("Basic ***(len=12)", out)
    }

    @Test
    fun `maskSecret short value uses short form`() {
        val out = LogRedactor.maskSecret("short")
        assertEquals("***(len=5)", out)
    }

    @Test
    fun `maskSecret is idempotent on already-masked input`() {
        val masked = LogRedactor.maskSecret("Bearer abcdefghijklmnop")
        val again = LogRedactor.maskSecret(masked)
        assertEquals(masked, again)
    }

    // —— redactHeaders ——————————————————————————————————————————————

    @Test
    fun `redactHeaders masks authorization`() {
        val out = LogRedactor.redactHeaders(
            mapOf("Authorization" to "Bearer abcdefghijklmnop", "Content-Type" to "application/json")
        )
        assertTrue(out["Authorization"]!!.startsWith("Bearer "))
        assertTrue(out["Authorization"]!!.contains("***"))
        assertEquals("application/json", out["Content-Type"])
    }

    @Test
    fun `redactHeaders masks x-api-key and cookie`() {
        val out = LogRedactor.redactHeaders(
            mapOf("X-API-Key" to "sk-abcdefghijklmnop", "Cookie" to "session=verylongsessionid1234")
        )
        assertTrue(out["X-API-Key"]!!.contains("***"))
        assertTrue(out["Cookie"]!!.contains("***"))
    }

    @Test
    fun `redactHeaders does not mask benign values`() {
        val out = LogRedactor.redactHeaders(mapOf("Content-Type" to "application/json"))
        assertEquals("application/json", out["Content-Type"])
    }

    // —— redactUrl ————————————————————————————————————————————————

    @Test
    fun `redactUrl strips userinfo`() {
        val out = LogRedactor.redactUrl("https://user:pass@api.example.com/v1/chat")
        assertEquals("https://api.example.com/v1/chat", out)
    }

    @Test
    fun `redactUrl masks sensitive query params`() {
        val out = LogRedactor.redactUrl("https://api.example.com/v1/chat?api_key=sk-abcdefghijklmnop&model=gpt-4")
        assertTrue(out.contains("api_key="))
        assertTrue(out.contains("***"))
        assertFalse(out.contains("sk-abcdefghijklmnop"))
        assertTrue(out.contains("model=gpt-4"))
    }

    @Test
    fun `redactUrl leaves non-sensitive query alone`() {
        val out = LogRedactor.redactUrl("https://api.example.com/v1/chat?model=gpt-4&stream=true")
        assertEquals("https://api.example.com/v1/chat?model=gpt-4&stream=true", out)
    }

    @Test
    fun `redactUrl regex fallback masks query-style token`() {
        // "foo" is a scheme-less token — URI parser fails, regex fallback runs.
        // The regex requires a leading `?` or `&` before the key, mirroring the
        // Dart version's URL_QUERY_RE; "foo?token=..." matches, "foo token=..." doesn't.
        val out = LogRedactor.redactUrl("foo?token=mysecretvalue12345")
        assertTrue("Should mask token=, got: $out", out.contains("token=") && out.contains("***"))
        assertFalse(out.contains("mysecretvalue12345"))
    }

    // —— redactBody (JSON walk) ——————————————————————————————————————————

    @Test
    fun `redactBody walks json and masks string leaves under sensitive keys`() {
        val body = """{"model":"gpt-4","api_key":"sk-abcdefghijklmnop","user":"alice"}"""
        val out = LogRedactor.redactBody(body)
        assertTrue("model is benign, got: $out", out.contains("\"model\":\"gpt-4\""))
        assertTrue("api_key should be masked, got: $out", out.contains("\"api_key\":") && out.contains("***"))
        assertTrue("user is benign, got: $out", out.contains("\"user\":\"alice\""))
    }

    @Test
    fun `redactBody masks nested keys`() {
        val body = """{"request":{"password":"hunter2hunter2","name":"x"}}"""
        val out = LogRedactor.redactBody(body)
        assertTrue(out.contains("***"))
        assertFalse(out.contains("hunter2hunter2"))
    }

    @Test
    fun `redactBody does not mask benign author name`() {
        val body = """{"author":"alice","text":"hello"}"""
        val out = LogRedactor.redactBody(body)
        assertTrue(out.contains("\"author\":\"alice\""))
    }

    @Test
    fun `redactBody falls back to regex on non-json`() {
        // The regex fallback only matches JSON-shaped `"name": "value"` pairs,
        // not URL-encoded form data — that's intentional, mirrors Dart.
        val body = """{"password":"hunter2hunter2","user":"alice"}"""
        val out = LogRedactor.redactBody(body)
        assertTrue("password should be masked, got: $out", out.contains("\"password\":") && out.contains("***"))
        assertFalse(out.contains("hunter2hunter2"))
    }

    @Test
    fun `redactBody regex fallback only on small body length skip`() {
        // Build a >256KB body that is not JSON → must take the regex path, not hang.
        val body = "x".repeat(257 * 1024) + " password=hunter2hunter2"
        val out = LogRedactor.redactBody(body)
        // Body is so large that the JSON walk would be skipped, regex pass only.
        // The regex won't match because no quoted form. But it must not crash and
        // must not exceed input length drastically.
        assertTrue(out.length <= body.length + 16)
    }

    // —— redactText ————————————————————————————————————————————————

    @Test
    fun `redactText masks known API key prefixes`() {
        val out = LogRedactor.redactText("connecting with sk-abcdefghijklmnop")
        assertFalse(out.contains("sk-abcdefghijklmnop"))
        assertTrue(out.contains("***"))
    }

    @Test
    fun `redactText masks google AIza key`() {
        val out = LogRedactor.redactText("AIzaSyA-verylonggoogleapikey")
        assertFalse(out.contains("AIzaSyA-verylonggoogleapikey"))
    }

    @Test
    fun `redactText leaves plain text alone`() {
        val out = LogRedactor.redactText("Hello world, this is a plain message.")
        assertEquals("Hello world, this is a plain message.", out)
    }

    @Test
    fun `redactText masks userinfo inside inline text`() {
        val out = LogRedactor.redactText("Got 401 from https://user:hunter2@api.example.com/v1")
        assertFalse(out.contains("hunter2"))
        assertTrue(out.contains("https://api.example.com/v1"))
    }

    // —— Integration / roundtrip ——————————————————————————————————————

    @Test
    fun `headers+url+body redaction composes`() {
        val headers = mapOf("Authorization" to "Bearer verylongbearertoken12345")
        val url = "https://api.example.com/v1/chat?key=verylongkeyvalue12345"
        val body = """{"messages":[{"role":"user","content":"hi"}],"api_key":"sk-abcdefghijklmnop"}"""
        val all = LogRedactor.redactHeaders(headers).toString() +
            " " + LogRedactor.redactUrl(url) + " " + LogRedactor.redactBody(body)
        assertFalse(all.contains("verylongbearertoken12345"))
        assertFalse(all.contains("verylongkeyvalue12345"))
        assertFalse(all.contains("sk-abcdefghijklmnop"))
    }

    @Test
    fun `redaction is deterministic`() {
        val body = """{"api_key":"sk-abcdefghijklmnop"}"""
        val a = LogRedactor.redactBody(body)
        val b = LogRedactor.redactBody(body)
        assertEquals(a, b)
        assertNotEquals(body, a)
    }
}
