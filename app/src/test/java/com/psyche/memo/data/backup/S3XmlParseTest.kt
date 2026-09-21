package com.psyche.memo.data.backup

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The `ListBucketResult` walk and the S3 error document both need the Android
 * XmlPullParser, so these run under Robolectric; the signing, key layout and
 * manifest checks stay in `core:data` as plain JUnit (S3ClientTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class S3XmlParseTest {

    private fun client() = S3Client(
        httpClient = OkHttpClient(),
        config = S3Config(
            endpoint = "https://s3.example.com",
            region = "us-east-1",
            bucket = "mybucket",
            accessKeyId = "ak",
            secretAccessKey = "sk",
            prefix = "memo_backups",
            pathStyle = true,
        ),
    )

    // ── listing ───────────────────────────────────────────────────────────────

    @Test
    fun `listing parse keeps zip objects only and reads size and mtime`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
              <Name>mybucket</Name>
              <Prefix>memo_backups/</Prefix>
              <KeyCount>2</KeyCount>
              <MaxKeys>1000</MaxKeys>
              <IsTruncated>false</IsTruncated>
              <Contents>
                <Key>memo_backups/memo_backup_2026-09-12T10-00-00.000.zip</Key>
                <LastModified>2026-09-12T10:00:00.000Z</LastModified>
                <ETag>"abc"</ETag>
                <Size>4096</Size>
                <StorageClass>STANDARD</StorageClass>
              </Contents>
              <Contents>
                <Key>memo_backups/notes.txt</Key>
                <LastModified>2026-09-11T10:00:00.000Z</LastModified>
                <Size>12</Size>
              </Contents>
            </ListBucketResult>
        """.trimIndent()

        val page = client().parseListBucket(xml)
        assertFalse(page.isTruncated)
        assertEquals("", page.nextContinuationToken)
        val entry = page.objects.single()
        assertEquals("memo_backups/memo_backup_2026-09-12T10-00-00.000.zip", entry.key)
        assertEquals("memo_backup_2026-09-12T10-00-00.000.zip", entry.displayName)
        assertEquals(4096L, entry.size)
        assertEquals(
            ZonedDateTime.of(2026, 9, 12, 10, 0, 0, 0, ZoneOffset.UTC).toInstant(),
            entry.lastModified?.toInstant(),
        )
    }

    @Test
    fun `listing parse surfaces the continuation token`() {
        val xml = """
            <ListBucketResult>
              <IsTruncated>true</IsTruncated>
              <NextContinuationToken>tok-2</NextContinuationToken>
              <Contents><Key>a.zip</Key><Size>1</Size></Contents>
            </ListBucketResult>
        """.trimIndent()
        val page = client().parseListBucket(xml)
        assertTrue(page.isTruncated)
        assertEquals("tok-2", page.nextContinuationToken)
        assertEquals(1, page.objects.size)
    }

    @Test
    fun `listing parse handles an empty result`() {
        val page = client().parseListBucket(
            "<ListBucketResult><Name>b</Name><KeyCount>0</KeyCount></ListBucketResult>",
        )
        assertTrue(page.objects.isEmpty())
        assertFalse(page.isTruncated)
    }

    @Test
    fun `listing parse does not carry a key across Contents blocks`() {
        // A provider that omits <Key> in the last block must not inherit the
        // previous block's key.
        val xml = """
            <ListBucketResult>
              <Contents><Key>a.zip</Key><Size>1</Size></Contents>
              <Contents><Size>2</Size></Contents>
            </ListBucketResult>
        """.trimIndent()
        assertEquals(listOf("a.zip"), client().parseListBucket(xml).objects.map { it.key })
    }

    // ── error documents (exercised through ApiResponse) ───────────────────────

    @Test
    fun `error summary mirrors the dart composition`() {
        val line = S3Client.ApiResponse(
            code = 403,
            body = "<Error><Code>AccessDenied</Code><Message>Denied</Message></Error>",
            regionHint = "eu-west-1",
        ).errorSummary()
        assertEquals("AccessDenied - Denied - Bucket region: eu-west-1", line)

        val hintOnly = S3Client.ApiResponse(code = 400, body = "", regionHint = "us-east-2").errorSummary()
        // The hint is part of the joined list, so it stands alone when there is
        // no error document to prefix it.
        assertEquals("Bucket region: us-east-2", hintOnly)

        assertEquals("HTTP 500", S3Client.ApiResponse(500, "", "").errorSummary())
    }

    @Test
    fun `a missing object is a 404 or a NoSuchKey code`() {
        assertTrue(S3Client.ApiResponse(404, "", "").isMissingObject)
        assertTrue(
            S3Client.ApiResponse(403, "<Error><Code>NoSuchKey</Code></Error>", "").isMissingObject,
        )
        assertFalse(S3Client.ApiResponse(200, "", "").isMissingObject)
        assertFalse(
            S3Client.ApiResponse(403, "<Error><Code>AccessDenied</Code></Error>", "").isMissingObject,
        )
    }

    @Test
    fun `a non xml error body does not break the summary`() {
        val summary = S3Client.ApiResponse(502, "<html>Bad gateway</html>", "").errorSummary()
        assertEquals("HTTP 502", summary)
    }
}
