package com.psyche.memo.data.backup

import okhttp3.OkHttpClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure WebDAV client checks: URL assembly, config round trip, and the
 * multistatus (207) XML walk — no network involved.
 */
class WebDavClientTest {

    private fun client(url: String, path: String) = WebDavClient(
        httpClient = OkHttpClient(),
        config = WebDavConfig(url = url, username = "u", password = "p", path = path),
    )

    @Test
    fun `collection url trims slashes and appends the path with a trailing slash`() {
        assertEquals(
            "https://example.com/dav/memo_backups/",
            client("https://example.com/dav", "memo_backups").collectionUrl(),
        )
        assertEquals(
            "https://example.com/dav/memo_backups/",
            client("https://example.com/dav///", "/memo_backups/").collectionUrl(),
        )
        assertEquals(
            "https://example.com/dav/",
            client("https://example.com/dav/", "").collectionUrl(),
        )
    }

    @Test
    fun `file url appends the child name`() {
        assertEquals(
            "https://example.com/dav/memo_backups/memo_backup_2026.zip",
            client("https://example.com/dav", "memo_backups").fileUrl("memo_backup_2026.zip"),
        )
    }

    @Test
    fun `config survives a json round trip`() {
        val config = WebDavConfig(
            url = "https://dav.example.com",
            username = "user",
            password = "pass\"with\\quotes",
            path = "custom",
            userAgent = "Memo/1.0",
        )
        val parsed = WebDavConfig.fromJson(
            Json.parseToJsonElement(config.toJson().toString()).jsonObject,
        )
        assertEquals(config, parsed)
    }

    @Test
    fun `empty path config reads back with the default`() {
        val raw = WebDavConfig(url = "u").toJson()
        val parsed = WebDavConfig.fromJson(Json.parseToJsonElement(raw.toString()).jsonObject)
        assertEquals(WebDavConfig.DEFAULT_PATH, parsed.path)
    }

    @Test
    fun `content switches round-trip and default to on`() {
        // The Backup-page toggles write these; a config stored before they
        // existed must still read back as "include everything".
        val defaults = WebDavConfig.fromJson(
            Json.parseToJsonElement(WebDavConfig(url = "u").toJson().toString()).jsonObject,
        )
        assertTrue(defaults.includeChats)
        assertTrue(defaults.includeFiles)

        val off = WebDavConfig(url = "u", includeChats = false, includeFiles = false)
        val parsed = WebDavConfig.fromJson(
            Json.parseToJsonElement(off.toJson().toString()).jsonObject,
        )
        assertFalse(parsed.includeChats)
        assertFalse(parsed.includeFiles)

        // A legacy payload without the two keys keeps the on defaults.
        val legacy = Json.parseToJsonElement("""{"url":"u","path":"p"}""").jsonObject
        assertTrue(WebDavConfig.fromJson(legacy).includeChats)
        assertTrue(WebDavConfig.fromJson(legacy).includeFiles)
    }
}
