package com.psyche.memo.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Cover for the S3 backup client's pure surface: SigV4 signing, key/prefix
 * layout, manifest codec, listing parse and the manifest/bucket merge.
 *
 * The signing test is the **AWS official test vector** ("Example: GET Object",
 * Signature Calculations for the Authorization Header), which is the only way
 * to prove the canonical request is byte-correct without hitting a bucket.
 */
class S3ClientTest {

    // ── SigV4 ─────────────────────────────────────────────────────────────────

    private fun vectorConfig() = S3Config(
        endpoint = "https://s3.amazonaws.com",
        region = "us-east-1",
        bucket = "examplebucket",
        accessKeyId = "AKIAIOSFODNN7EXAMPLE",
        secretAccessKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        prefix = "",
        pathStyle = false,
    )

    /**
     * AWS docs, "Signature Calculations for the Authorization Header":
     * `GET /test.txt`, Range: bytes=0-9, 20130524T000000Z.
     */
    @Test
    fun `matches the AWS official GET object test vector`() {
        val signed = AwsSignatureV4.sign(
            config = vectorConfig(),
            method = "GET",
            canonicalPath = "/test.txt",
            extraHeaders = mapOf("range" to "bytes=0-9"),
            payloadHash = AwsSignatureV4.EMPTY_PAYLOAD_SHA256,
            at = ZonedDateTime.of(2013, 5, 24, 0, 0, 0, 0, ZoneOffset.UTC),
        )

        assertEquals(
            "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
                "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41",
            signed.headers["authorization"],
        )
        assertEquals(
            "https://examplebucket.s3.amazonaws.com/test.txt",
            signed.url,
        )
        assertEquals("examplebucket.s3.amazonaws.com", signed.headers["host"])
        assertEquals("20130524T000000Z", signed.headers["x-amz-date"])
        assertEquals(AwsSignatureV4.EMPTY_PAYLOAD_SHA256, signed.headers["x-amz-content-sha256"])
    }

    @Test
    fun `time is normalised to UTC before stamping`() {
        val tokyo = ZonedDateTime.of(2013, 5, 24, 9, 0, 0, 0, ZoneOffset.ofHours(9))
        val sign = { at: ZonedDateTime ->
            AwsSignatureV4.sign(
                config = vectorConfig(),
                method = "GET",
                canonicalPath = "/test.txt",
                payloadHash = AwsSignatureV4.EMPTY_PAYLOAD_SHA256,
                at = at,
            )
        }
        // 09:00+09:00 is 00:00Z — same instant, so same stamp and signature.
        assertEquals("20130524T000000Z", sign(tokyo).headers["x-amz-date"])
        assertEquals(
            sign(ZonedDateTime.of(2013, 5, 24, 0, 0, 0, 0, ZoneOffset.UTC)).headers["authorization"],
            sign(tokyo).headers["authorization"],
        )
        // A different instant must sign differently.
        assertFalse(
            sign(tokyo).headers["authorization"] ==
                sign(tokyo.plusSeconds(1)).headers["authorization"],
        )
    }

    @Test
    fun `session token and user agent are signed when configured`() {
        val signed = AwsSignatureV4.sign(
            config = vectorConfig().copy(sessionToken = "tok", userAgent = "memo/1.0"),
            method = "GET",
            canonicalPath = "/a.txt",
            payloadHash = AwsSignatureV4.EMPTY_PAYLOAD_SHA256,
            at = ZonedDateTime.of(2013, 5, 24, 0, 0, 0, 0, ZoneOffset.UTC),
        )
        assertEquals("tok", signed.headers["x-amz-security-token"])
        assertEquals("memo/1.0", signed.headers["user-agent"])
        assertTrue(
            signed.headers.getValue("authorization")
                .contains("SignedHeaders=host;user-agent;x-amz-content-sha256;x-amz-date;x-amz-security-token"),
        )
    }

    @Test
    fun `content length participates in the signature`() {
        val without = AwsSignatureV4.sign(
            config = vectorConfig(),
            method = "PUT",
            canonicalPath = "/a.zip",
            payloadHash = AwsSignatureV4.UNSIGNED_PAYLOAD,
            at = ZonedDateTime.of(2013, 5, 24, 0, 0, 0, 0, ZoneOffset.UTC),
        )
        val with = AwsSignatureV4.sign(
            config = vectorConfig(),
            method = "PUT",
            canonicalPath = "/a.zip",
            payloadHash = AwsSignatureV4.UNSIGNED_PAYLOAD,
            contentLength = 42,
            at = ZonedDateTime.of(2013, 5, 24, 0, 0, 0, 0, ZoneOffset.UTC),
        )
        assertEquals("42", with.headers["content-length"])
        assertFalse(with.headers.getValue("authorization") == without.headers.getValue("authorization"))
        assertTrue(with.headers.getValue("authorization").contains("content-length"))
    }

    @Test
    fun `uri encoding follows RFC3986 rather than dart's encodeComponent`() {
        // `!*'()` are literal under Dart's Uri.encodeComponent but must be
        // percent-encoded for SigV4.
        assertEquals("a%21b%2Ac%27d%28e%29f", AwsSignatureV4.awsEncode("a!b*c'd(e)f"))
        assertEquals("a%20b", AwsSignatureV4.awsEncode("a b"))
        assertEquals("a~b-c_d.e", AwsSignatureV4.awsEncode("a~b-c_d.e"))
        assertEquals("%E4%B8%AD%E6%96%87", AwsSignatureV4.awsEncode("中文"))
        assertEquals("%2F", AwsSignatureV4.awsEncode("/"))
    }

    @Test
    fun `canonical query sorts by encoded key then value`() {
        val canonical = AwsSignatureV4.canonicalQueryString(
            linkedMapOf(
                "prefix" to "memo_backups/",
                "list-type" to "2",
                "max-keys" to "1000",
                "continuation-token" to "a b",
            ),
        )
        assertEquals(
            "continuation-token=a%20b&list-type=2&max-keys=1000&prefix=memo_backups%2F",
            canonical,
        )
    }

    @Test
    fun `empty payload hash constant is the sha256 of the empty string`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            AwsSignatureV4.sha256Hex(""),
        )
        assertEquals(AwsSignatureV4.EMPTY_PAYLOAD_SHA256, AwsSignatureV4.sha256Hex(""))
    }

    // ── key / path layout ─────────────────────────────────────────────────────

    private fun pathStyleClient(
        endpoint: String = "https://s3.example.com",
        bucket: String = "mybucket",
        prefix: String = "memo_backups",
    ) = S3Client(
        httpClient = okhttp3.OkHttpClient(),
        config = S3Config(endpoint = endpoint, bucket = bucket, prefix = prefix, pathStyle = true),
    )

    private fun virtualHostClient(
        endpoint: String = "https://s3.example.com",
        bucket: String = "mybucket",
    ) = S3Client(
        httpClient = okhttp3.OkHttpClient(),
        config = S3Config(endpoint = endpoint, bucket = bucket, pathStyle = false),
    )

    @Test
    fun `prefix is trimmed and gains a trailing slash`() {
        val client = pathStyleClient()
        assertEquals("memo_backups/", client.normalizePrefix("memo_backups"))
        assertEquals("memo_backups/", client.normalizePrefix("/memo_backups/"))
        assertEquals("a/b/", client.normalizePrefix("  a/b  "))
        assertEquals("", client.normalizePrefix("   "))
        assertEquals("", client.normalizePrefix("///"))
    }

    @Test
    fun `manifest key is the prefix plus the manifest object name`() {
        assertEquals("memo_backups/.memo_backups_manifest.json", pathStyleClient().manifestKey())
        assertEquals(
            ".memo_backups_manifest.json",
            pathStyleClient(prefix = "").manifestKey(),
        )
        // Branding guard: neither the default prefix nor the manifest object
        // name may carry the upstream brand.
        assertFalse(S3Client.MANIFEST_OBJECT_NAME.contains("kelivo"))
        assertFalse(S3Config.DEFAULT_PREFIX.contains("kelivo"))
    }

    @Test
    fun `path style repeats the bucket in the canonical path`() {
        val client = pathStyleClient()
        assertEquals("/mybucket", client.bucketPath())
        assertEquals("/mybucket/memo_backups/a.zip", client.objectPath("memo_backups/a.zip"))
    }

    @Test
    fun `virtual host style omits the bucket from the canonical path`() {
        val client = virtualHostClient()
        assertEquals("/", client.bucketPath())
        assertEquals("/memo_backups/a.zip", client.objectPath("memo_backups/a.zip"))
    }

    @Test
    fun `an endpoint that already carries the bucket does not repeat it`() {
        val client = pathStyleClient(endpoint = "https://s3.example.com/mybucket")
        assertEquals("/mybucket", client.bucketPath())
        assertEquals("/mybucket/a.zip", client.objectPath("a.zip"))
    }

    @Test
    fun `object key segments are encoded individually`() {
        val client = pathStyleClient()
        assertEquals("/mybucket/a%20b/c%2Bd.zip", client.objectPath("a b/c+d.zip"))
    }

    @Test
    fun `display name is the last key segment`() {
        assertEquals("a.zip", s3DisplayNameFromKey("memo_backups/a.zip"))
        assertEquals("a.zip", s3DisplayNameFromKey("a.zip"))
        assertEquals("a.zip", s3DisplayNameFromKey("memo_backups/a.zip/"))
    }

    @Test
    fun `host header omits a default port and keeps a custom one`() {
        assertEquals(
            "s3.example.com",
            S3Config(endpoint = "https://s3.example.com:443", bucket = "b").hostHeader(),
        )
        assertEquals(
            "s3.example.com:9000",
            S3Config(endpoint = "https://s3.example.com:9000", bucket = "b").hostHeader(),
        )
        assertEquals(
            "mybucket.s3.example.com",
            S3Config(endpoint = "https://s3.example.com", bucket = "mybucket", pathStyle = false).hostHeader(),
        )
    }

    @Test
    fun `a scheme-less endpoint gets https`() {
        val config = S3Config(endpoint = "s3.example.com", bucket = "b")
        assertEquals("s3.example.com", config.hostHeader())
        assertTrue(config.isHttps())
    }

    // ── config ────────────────────────────────────────────────────────────────

    @Test
    fun `config json round-trips through its own shape`() {
        val config = S3Config(
            endpoint = "https://s3.example.com",
            region = "auto",
            bucket = "b",
            accessKeyId = "ak",
            secretAccessKey = "sk",
            sessionToken = "tok",
            prefix = "custom",
            pathStyle = false,
            userAgent = "memo/1.0",
            includeChats = false,
            includeFiles = true,
        )
        assertEquals(config, S3Config.fromJson(config.toJson()))
    }

    @Test
    fun `config json carries the dart field names verbatim`() {
        val json = S3Config().toJson()
        listOf(
            "endpoint", "region", "bucket", "accessKeyId", "secretAccessKey",
            "sessionToken", "prefix", "pathStyle", "userAgent", "includeChats", "includeFiles",
        ).forEach { key ->
            assertNotNull("missing field $key", json[key])
        }
    }

    @Test
    fun `blank region and prefix fall back to the defaults`() {
        val parsed = S3Config.fromJsonString("""{"region":"  ","prefix":"  "}""")
        assertEquals("us-east-1", parsed.region)
        assertEquals(S3Config.DEFAULT_PREFIX, parsed.prefix)
    }

    @Test
    fun `garbage config json yields defaults rather than throwing`() {
        assertEquals(S3Config(), S3Config.fromJsonString("not json"))
        assertEquals(S3Config(), S3Config.fromJsonString(null))
        assertEquals(S3Config(), S3Config.fromJsonString(""))
    }

    @Test
    fun `validation names each missing field`() {
        fun message(config: S3Config): String =
            runCatching { config.validate() }.exceptionOrNull()?.message.orEmpty()

        assertTrue(message(S3Config()).contains("endpoint"))
        assertTrue(
            message(S3Config(endpoint = "https://x", region = "", bucket = "b", accessKeyId = "a", secretAccessKey = "s"))
                .contains("region"),
        )
        assertTrue(
            message(S3Config(endpoint = "https://x", bucket = "", accessKeyId = "a", secretAccessKey = "s"))
                .contains("bucket"),
        )
        assertTrue(
            message(S3Config(endpoint = "https://x", bucket = "b", accessKeyId = "", secretAccessKey = "s"))
                .contains("accessKeyId"),
        )
        assertTrue(
            message(S3Config(endpoint = "https://x", bucket = "b", accessKeyId = "a"))
                .contains("secretAccessKey"),
        )
        // Fully valid: no throw.
        S3Config(endpoint = "https://x", bucket = "b", accessKeyId = "a", secretAccessKey = "s").validate()
    }

    // ── manifest codec ────────────────────────────────────────────────────────

    private fun item(
        key: String,
        size: Long = 10,
        modified: String? = "2026-09-12T10:00:00Z",
    ) = S3FileItem(
        key = key,
        displayName = s3DisplayNameFromKey(key),
        size = size,
        lastModified = parseS3DateTime(modified),
    )

    @Test
    fun `manifest round-trips`() {
        val items = listOf(
            item("memo_backups/a.zip", size = 100, modified = "2026-09-12T10:00:00Z"),
            item("memo_backups/b.zip", size = 200, modified = "2026-09-11T10:00:00Z"),
        )
        assertEquals(items, decodeManifest(encodeManifest(items)))
    }

    @Test
    fun `manifest is newest first`() {
        val decoded = decodeManifest(
            encodeManifest(
                listOf(
                    item("a.zip", modified = "2026-09-01T00:00:00Z"),
                    item("b.zip", modified = "2026-09-09T00:00:00Z"),
                ),
            ),
        )
        assertEquals(listOf("b.zip", "a.zip"), decoded.map { it.key })
    }

    @Test
    fun `manifest keeps only zip entries`() {
        val decoded = decodeManifest(
            encodeManifest(listOf(item("a.zip"), item("notes.txt"), item("b.ZIP"))),
        )
        assertEquals(listOf("a.zip", "b.ZIP"), decoded.map { it.key }.sorted())
    }

    @Test
    fun `manifest entries without a key are dropped`() {
        val decoded = decodeManifest(
            """{"version":1,"items":[{"key":"a.zip","size":1},{"key":"  ","size":1},{"size":9}]}""",
        )
        assertEquals(listOf("a.zip"), decoded.map { it.key })
    }

    @Test
    fun `manifest fills a missing display name from the key`() {
        val decoded = decodeManifest("""{"version":1,"items":[{"key":"x/y/a.zip","size":1}]}""")
        assertEquals("a.zip", decoded.single().displayName)
    }

    @Test
    fun `manifest tolerates size as a number or a string`() {
        val decoded = decodeManifest(
            """{"version":1,"items":[{"key":"a.zip","size":"1234"},{"key":"b.zip","size":99.0}]}""",
        )
        assertEquals(mapOf("a.zip" to 1234L, "b.zip" to 99L), decoded.associate { it.key to it.size })
    }

    @Test
    fun `manifest tolerates a missing lastModified`() {
        val decoded = decodeManifest("""{"version":1,"items":[{"key":"a.zip","size":1}]}""")
        assertNull(decoded.single().lastModified)
    }

    @Test
    fun `malformed manifests report the same errors as dart`() {
        assertEquals(
            "S3 manifest read failed: invalid manifest format",
            runCatching { decodeManifest("nope") }.exceptionOrNull()?.message,
        )
        assertEquals(
            "S3 manifest read failed: invalid manifest items",
            runCatching { decodeManifest("""{"version":1}""") }.exceptionOrNull()?.message,
        )
    }

    // ── merge ─────────────────────────────────────────────────────────────────

    @Test
    fun `merge prefers the newer timestamp`() {
        val old = item("a.zip", modified = "2026-09-01T00:00:00Z")
        val new = item("a.zip", modified = "2026-09-09T00:00:00Z")
        val merged = pathStyleClient().mergeBackupItems(listOf(old), listOf(new), bucketIsAuthoritative = true)
        assertEquals(new.lastModified, merged.single().lastModified)
    }

    @Test
    fun `merge respects bucketIsAuthoritative when the manifest is stale`() {
        val manifestOnly = item("only-in-manifest.zip")
        val bucket = item("only-in-bucket.zip")

        val authoritative = pathStyleClient().mergeBackupItems(
            listOf(manifestOnly), listOf(bucket), bucketIsAuthoritative = true,
        )
        assertEquals(listOf("only-in-bucket.zip"), authoritative.map { it.key })

        val fallback = pathStyleClient().mergeBackupItems(
            listOf(manifestOnly), listOf(bucket), bucketIsAuthoritative = false,
        )
        assertEquals(
            setOf("only-in-manifest.zip", "only-in-bucket.zip"),
            fallback.map { it.key }.toSet(),
        )
    }

    @Test
    fun `merge fills a missing timestamp or a zero size`() {
        val noTime = item("a.zip", size = 0, modified = null)
        val withTime = item("a.zip", size = 10, modified = "2026-09-09T00:00:00Z")
        val merged = pathStyleClient().mergeBackupItems(listOf(noTime), listOf(withTime), true)
        assertEquals(10L, merged.single().size)
        assertNotNull(merged.single().lastModified)
    }

    @Test
    fun `merge output is newest first`() {
        val merged = pathStyleClient().mergeBackupItems(
            emptyList(),
            listOf(
                item("a.zip", modified = "2026-09-01T00:00:00Z"),
                item("b.zip", modified = "2026-09-09T00:00:00Z"),
                item("c.zip", modified = "2026-09-05T00:00:00Z"),
            ),
            true,
        )
        assertEquals(listOf("b.zip", "c.zip", "a.zip"), merged.map { it.key })
    }

    @Test
    fun `sameBackupItems compares every field and the instant`() {
        val client = pathStyleClient()
        val a = item("a.zip")
        assertTrue(client.sameBackupItems(listOf(a), listOf(a.copy())))
        assertFalse(client.sameBackupItems(listOf(a), listOf(a.copy(size = 11))))
        assertFalse(client.sameBackupItems(listOf(a), listOf(a.copy(displayName = "x.zip"))))
        assertFalse(client.sameBackupItems(listOf(a), listOf(a.copy(key = "b.zip"))))
        assertFalse(client.sameBackupItems(listOf(a), emptyList()))
        // Same instant expressed in a different zone is the same moment.
        val offset = a.copy(lastModified = a.lastModified?.withZoneSameInstant(ZoneOffset.ofHours(8)))
        assertTrue("instants must compare, not zones", client.sameBackupItems(listOf(a), listOf(offset)))
    }
}
