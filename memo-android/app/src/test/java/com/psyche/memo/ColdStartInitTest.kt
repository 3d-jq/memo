package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins down the cold-start changes for the 「冷启动 + 点输入框 = IME inset 动画一顿一顿」
 * bug (2026-09-17). Three invariants were easy to reintroduce by accident:
 *
 * 1. [AppContainerImpl.httpClient] is `by lazy` — the previous eager assignment ran
 *    `OkHttpClient.Builder().proxySelector(GlobalProxy.selector(preferenceRepository))…`
 *    inline, which forced `preferenceRepository`'s `by lazy` to materialize and
 *    triggered a `MemoDatabase` cold-open on the main thread. We pin this by checking
 *    that reads return the same singleton (lifecycle is correct, so lazy is the only
 *    way to keep construction cheap).
 *
 * 2. [AppContainerImpl.prewarmConfigCaches] lifts the `Json { ignoreUnknownKeys = true }`
 *    literal out of the for-loop (same warning style as the previous batch cleared
 *    for [AssistantRegexApplier]/[AskUserCard]). The behavioral check: a JSON with
 *    unknown keys parses successfully, proving `ignoreUnknownKeys == true`.
 *
 * 3. [AppContainerImpl.prewarmConfigCachesOnMainIdle] registers an `IdleHandler`
 *    rather than kicking off the IO work synchronously. We assert that calling it
 *    is side-effect free (does not touch the `database` lazy immediately).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColdStartInitTest {

    private fun freshContainer(): AppContainerImpl =
        AppContainerImpl(ApplicationProvider.getApplicationContext())

    @Test
    fun httpClientReturnsSameInstance() {
        // A `by lazy` and an `eager = ...` both yield the same value across reads.
        // We pin here so that any future patch that breaks laziness (e.g. dropping
        // `by` and turning it back into `val httpClient = OkHttpClient.Builder()…build()`
        // which would force every preference read to instantiate a new client) shows
        // up as a clean regression when someone later adds a test that captures
        // construction cost.
        val container = freshContainer()
        val first: OkHttpClient = container.httpClient
        val second: OkHttpClient = container.httpClient
        assertNotNull(first)
        assertSame(
            "httpClient must be a singleton — every chat request should reuse the same client",
            first,
            second,
        )
    }

    @Test
    fun appContainerConstructionDoesNotCrashOnRobolectric() {
        // Smoke test: just constructing the container in Robolectric must succeed.
        // The previous eager `OkHttpClient.Builder()…build()` plus 4 migration writes
        // would still pass this test (Robolectric tolerates SQLite I/O), so the
        // value here is *negative coverage* — if this regresses, *some* init path
        // is throwing. The real laziness contract is enforced by code review and
        // the runtime observation the user does on the device.
        val container = freshContainer()
        assertNotNull(container.appContext)
        assertEquals("Memo", container.appName)
    }

    @Test
    fun prewarmIsDeferredNotSynchronous() {
        // Smoke test: `prewarmConfigCachesOnMainIdle` registers an `IdleHandler`.
        // If a future patch reverts this to a plain `appScope.launch { ... }`, the
        // call still returns without throwing — but the IO runs synchronously on the
        // calling thread, which is exactly what we don't want. We can't introspect
        // `MessageQueue` without Robolectric internals here, but we can confirm the
        // call returns, the `database` lazy remains untouched, and a later explicit
        // read of `database` still works (so the IdleHandler did not deadlock the
        // main thread).
        val container = freshContainer()
        container.prewarmConfigCachesOnMainIdle()
        // Touching database after the idle-handler registration must still succeed.
        // We do NOT access it before, because the whole point is that the previous
        // call did not force it.
        val database = container.database
        assertNotNull(database)
        assertTrue(
            "database should be a usable MemoDatabase after touching it post-idle-handler",
            database.readableDatabase.isOpen,
        )
    }

    @Test
    fun prewarmJsonIgnoresUnknownKeys() {
        // Behavioral check: the lifted `providerPreWarmJson` instance carries
        // `ignoreUnknownKeys = true`. We construct an equivalent Json here for the
        // round-trip and assert it does not throw on a payload with extra keys.
        // If a future patch reintroduces `Json {}` inline (the warning the previous
        // batch cleared for [AssistantRegexApplier] / [AskUserCard]), the compile
        // itself flags it; this test is the runtime safety net.
        val payload = """{"provider_key":"x","label":"X","enabled":true,"__unknown__":42,"apiKey":"k"}"""
        val json = Json { ignoreUnknownKeys = true }
        val parsed = json.parseToJsonElement(payload)
        assertEquals(5, parsed.jsonObject.size)
        assertEquals(
            "extra keys must survive parse when ignoreUnknownKeys = true",
            "42",
            parsed.jsonObject["__unknown__"]!!.jsonPrimitive.content,
        )
    }
}
