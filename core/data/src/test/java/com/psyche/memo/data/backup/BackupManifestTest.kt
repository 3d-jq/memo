package com.psyche.memo.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the on-disk manifest contract. These assertions are what keeps a backup
 * written by the Flutter build readable by the Android build (and vice versa) —
 * if one of them fails, an archive format change was made without a version
 * bump, which silently breaks every existing backup on users' disks.
 */
class BackupManifestTest {

    private fun sampleEntries() = mapOf(
        "settings.json" to EntryMetadata(bytes = 1234, sha256 = "aa".repeat(32)),
        "database/kelivo.db" to EntryMetadata(bytes = 999_999, sha256 = "bb".repeat(32)),
    )

    private fun sampleDatabase() = DatabaseInfo(
        entry = BackupManifestCodec.ENTRY_DATABASE,
        schemaVersion = 3,
        minimumReadableSchemaVersion = 1,
        conversationCount = 7,
        messageCount = 42,
    )

    private fun encodeSample(): String = BackupManifestCodec.encode(
        entries = sampleEntries(),
        database = sampleDatabase(),
        includeChats = true,
        includeFiles = true,
        appVersion = "1.2.5+2073",
        createdAtUtc = "2026-09-10T06:00:00.000Z",
        businessEntityRowIds = mapOf("assistants_v1" to listOf("a1", "a2")),
    )

    @Test
    fun `encode writes the exact key set the Flutter build writes`() {
        val json = encodeSample()
        // Spot-check the literal keys rather than only round-tripping: a
        // typo'd key still round-trips through this same codec.
        assertTrue(json.contains("\"format\":\"kelivo-backup\""))
        assertTrue(json.contains("\"formatVersion\":2"))
        assertTrue(json.contains("\"minimumReadableFormatVersion\":2"))
        assertTrue(json.contains("\"payloadKind\":\"sqlite\""))
        assertTrue(json.contains("\"secretsIncluded\":true"))
        assertTrue(json.contains("\"entry\":\"database/kelivo.db\""))
        assertTrue(json.contains("\"minimumReadableSchemaVersion\":1"))
        assertTrue(json.contains("\"assistants_v1\":[\"a1\",\"a2\"]"))
        assertTrue(json.contains("2026-09-10T06:00:00.000Z"))
    }

    @Test
    fun `settings-only payload omits the database block`() {
        val json = BackupManifestCodec.encode(
            entries = mapOf("settings.json" to EntryMetadata(10, "cc".repeat(32))),
            database = null,
            includeChats = false,
            includeFiles = false,
            appVersion = "1.2.5+2073",
            createdAtUtc = "2026-09-10T06:00:00.000Z",
            businessEntityRowIds = emptyMap(),
        )
        assertFalse(json.contains("\"database\""))
        assertTrue(json.contains("\"payloadKind\":\"settings-only\""))
    }

    @Test
    fun `decode round-trips every field`() {
        val manifest = BackupManifestCodec.decode(encodeSample())

        assertEquals(BackupManifestCodec.FORMAT, manifest.format)
        assertEquals(BackupManifestCodec.FORMAT_VERSION, manifest.formatVersion)
        assertEquals(
            BackupManifestCodec.MINIMUM_READABLE_FORMAT_VERSION,
            manifest.minimumReadableFormatVersion,
        )
        assertEquals(BackupManifestCodec.PAYLOAD_SQLITE, manifest.payloadKind)
        assertEquals("2026-09-10T06:00:00.000Z", manifest.createdAtUtc)
        assertEquals("1.2.5+2073", manifest.appVersion)
        assertTrue(manifest.includeChats)
        assertTrue(manifest.includeFiles)
        assertTrue(manifest.secretsIncluded)
        assertEquals(listOf("a1", "a2"), manifest.businessEntityRowIds["assistants_v1"])

        val db = manifest.database
        assertNotNull(db)
        assertEquals(3, db!!.schemaVersion)
        assertEquals(1, db.minimumReadableSchemaVersion)
        assertEquals(7, db.conversationCount)
        assertEquals(42, db.messageCount)

        assertEquals(2, manifest.entries.size)
        assertEquals(1234L, manifest.entries.getValue("settings.json").bytes)
        assertEquals("aa".repeat(32), manifest.entries.getValue("settings.json").sha256)
    }

    @Test
    fun `decode tolerates unknown keys added by a newer build`() {
        val json = encodeSample().replace(
            "\"formatVersion\":2",
            "\"formatVersion\":2,\"somethingNew\":{\"nested\":[1,2,3]}",
        )
        val manifest = BackupManifestCodec.decode(json)
        assertEquals(BackupManifestCodec.FORMAT, manifest.format)
        assertEquals(2, manifest.formatVersion)
    }

    @Test
    fun `decode survives a malformed row-id projection`() {
        val json = encodeSample().replace(
            "\"assistants_v1\":[\"a1\",\"a2\"]",
            "\"assistants_v1\":\"not-an-array\"",
        )
        val manifest = BackupManifestCodec.decode(json)
        // Only the row-id projection degrades; the archive stays readable.
        assertEquals(emptyList<String>(), manifest.businessEntityRowIds["assistants_v1"])
        assertEquals(2, manifest.entries.size)
    }

    @Test
    fun `admits archives this build can read`() {
        val manifest = BackupManifestCodec.decode(encodeSample())
        assertTrue(BackupManifestCodec.acceptsFormat(manifest))
        assertFalse(BackupManifestCodec.declaresNewerBuild(manifest))
    }

    @Test
    fun `format gate follows the upstream matrix`() {
        fun manifestWith(formatVersion: Int, declared: Int?): BackupManifest {
            val raw = encodeSample()
                .replace("\"formatVersion\":2", "\"formatVersion\":$formatVersion")
                .let { json ->
                    if (declared == null) {
                        json.replace("\"minimumReadableFormatVersion\":2,", "")
                    } else {
                        json.replace(
                            "\"minimumReadableFormatVersion\":2",
                            "\"minimumReadableFormatVersion\":$declared",
                        )
                    }
                }
            return BackupManifestCodec.decode(raw)
        }

        // Exact match reads as-is, regardless of what it declares.
        assertTrue(BackupManifestCodec.acceptsFormat(manifestWith(formatVersion = 2, declared = 2)))
        // Older archive formats were never supported and still are not.
        assertFalse(BackupManifestCodec.acceptsFormat(manifestWith(formatVersion = 1, declared = 1)))
        // A NEWER archive is admitted only while its declaration vouches for
        // this build (1..FORMAT_VERSION).
        assertTrue(BackupManifestCodec.acceptsFormat(manifestWith(formatVersion = 3, declared = 2)))
        assertTrue(BackupManifestCodec.acceptsFormat(manifestWith(formatVersion = 3, declared = 1)))
        assertFalse(BackupManifestCodec.acceptsFormat(manifestWith(formatVersion = 3, declared = 3)))
        assertFalse(BackupManifestCodec.acceptsFormat(manifestWith(formatVersion = 3, declared = null)))
        assertFalse(BackupManifestCodec.acceptsFormat(manifestWith(formatVersion = 3, declared = 0)))
    }

    @Test
    fun `rejects an archive whose format version is below its own floor`() {
        // An archive cannot vouch for a floor ABOVE the format it declares —
        // the writer contradicts itself, so it cannot be trusted.
        val json = encodeSample().replace(
            "\"formatVersion\":2",
            "\"formatVersion\":3",
        ).replace(
            "\"minimumReadableFormatVersion\":2",
            "\"minimumReadableFormatVersion\":3",
        )
        val manifest = BackupManifestCodec.decode(json)
        assertFalse(BackupManifestCodec.acceptsFormat(manifest))
    }

    @Test
    fun `flags an archive written by a newer build`() {
        // Format axis: a newer formatVersion (already vouched for by the
        // format gate).
        val newerFormat = BackupManifestCodec.decode(
            encodeSample()
                .replace("\"formatVersion\":2", "\"formatVersion\":3")
                .replace("\"minimumReadableFormatVersion\":2", "\"minimumReadableFormatVersion\":2"),
        )
        assertTrue(BackupManifestCodec.declaresNewerBuild(newerFormat))
        // Schema axis: the database block describes a schema newer than this
        // build knows, even at the same archive format.
        val newerSchema = BackupManifestCodec.decode(
            encodeSample().replace("\"schemaVersion\":3", "\"schemaVersion\":4"),
        )
        assertTrue(BackupManifestCodec.declaresNewerBuild(newerSchema))
        // A settings-only archive has no schema at all; only the format axis
        // can flag it.
        assertFalse(BackupManifestCodec.declaresNewerBuild(BackupManifestCodec.decode(encodeSample())))
    }

    @Test
    fun `rejects a foreign archive format`() {
        val json = encodeSample().replace("\"kelivo-backup\"", "\"some-other-app\"")
        val manifest = BackupManifestCodec.decode(json)
        assertFalse(BackupManifestCodec.acceptsFormat(manifest))
    }

    @Test
    fun `decode of minimal json yields safe defaults instead of throwing`() {
        val manifest = BackupManifestCodec.decode("{}")
        assertEquals("", manifest.format)
        assertEquals(0, manifest.formatVersion)
        assertNull(manifest.database)
        assertEquals(emptyMap<String, EntryMetadata>(), manifest.entries)
        assertFalse(BackupManifestCodec.acceptsFormat(manifest))
    }

    @Test
    fun `format constants match the values already on disk`() {
        // Pinned literals: changing these invalidates every existing backup.
        assertEquals("kelivo-backup", BackupManifestCodec.FORMAT)
        assertEquals(2, BackupManifestCodec.FORMAT_VERSION)
        assertEquals(2, BackupManifestCodec.MINIMUM_READABLE_FORMAT_VERSION)
        assertEquals("manifest.json", BackupManifestCodec.ENTRY_MANIFEST)
        assertEquals("settings.json", BackupManifestCodec.ENTRY_SETTINGS)
        assertEquals("database/kelivo.db", BackupManifestCodec.ENTRY_DATABASE)
    }
}
