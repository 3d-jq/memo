package com.psyche.memo.data.backup

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The multistatus (207) XML walk needs the Android XmlPullParser, so these run
 * under Robolectric; the URL/config checks stay in core:data as plain JUnit.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebDavMultistatusParseTest {

    private fun client() = WebDavClient(
        httpClient = OkHttpClient(),
        config = WebDavConfig(
            url = "https://example.com/dav",
            username = "u",
            password = "p",
            path = "memo_backups",
        ),
    )

    @Test
    fun `multistatus parse extracts entries with size and mtime`() {
        val xml = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/memo_backups/</d:href>
                <d:propstat><d:prop><d:displayname>memo_backups</d:displayname></d:prop></d:propstat>
              </d:response>
              <d:response>
                <d:href>/dav/memo_backups/memo_backup_2026-09-12T10-30-00.000.zip</d:href>
                <d:propstat><d:prop>
                  <d:displayname>memo_backup_2026-09-12T10-30-00.000.zip</d:displayname>
                  <d:getcontentlength>123456</d:getcontentlength>
                  <d:getlastmodified>Sat, 12 Sep 2026 10:30:00 GMT</d:getlastmodified>
                </d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val entries = client().parseForTest(xml)
        assertEquals(2, entries.size)
        assertEquals(123456L, entries[1].size)
        assertEquals("memo_backup_2026-09-12T10-30-00.000.zip", entries[1].displayName)
        assertTrue(entries[1].lastModified != null)
    }

    @Test
    fun `a name without a displayname falls back to the href tail`() {
        val xml = """
            <d:multistatus xmlns:d="DAV:">
              <d:response>
                <d:href>/dav/memo_backups/only_href.zip</d:href>
                <d:propstat><d:prop><d:getcontentlength>1</d:getcontentlength></d:prop></d:propstat>
              </d:response>
            </d:multistatus>
        """.trimIndent()
        val entries = client().parseForTest(xml)
        assertEquals("only_href.zip", entries.single().displayName)
        assertNull(entries.single().lastModified)
    }
}
