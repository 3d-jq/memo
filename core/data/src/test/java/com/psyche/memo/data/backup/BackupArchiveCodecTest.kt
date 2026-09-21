package com.psyche.memo.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Round-trip and defence tests for [BackupArchiveCodec]. The codec is the only
 * thing standing between a user's archive and their data, so the tests cover
 * the happy path, the tampered-archive paths and the shape of what lands on
 * disk (entry names, forward slashes, manifest placement).
 */
class BackupArchiveCodecTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(file: File, content: String): File {
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file
    }

    private fun assetDirs(vararg pairs: Pair<String, File>): Map<String, File> =
        pairs.toMap()

    private fun manifestFor(includeChats: Boolean, includeFiles: Boolean): (Map<String, EntryMetadata>) -> String =
        { entries ->
            BackupManifestCodec.encode(
                entries = entries,
                database = if (includeChats) DatabaseInfo(
                    entry = BackupManifestCodec.ENTRY_DATABASE,
                    schemaVersion = 3,
                    minimumReadableSchemaVersion = 1,
                    conversationCount = 0,
                    messageCount = 0,
                ) else null,
                includeChats = includeChats,
                includeFiles = includeFiles,
                appVersion = "1.2.5+2073",
                createdAtUtc = "2026-09-10T06:00:00.000Z",
                businessEntityRowIds = emptyMap(),
            )
        }

    @Test
    fun `packs and extracts a full archive round-trip`() {
        val settings = write(File(tmp.root, "staging/settings.json"), """{"a":1}""")
        val database = write(File(tmp.root, "staging/kelivo.db"), "SQLite-format-3\u0000binary")
        val upload = File(tmp.root, "files/upload").apply { mkdirs() }
        write(File(upload, "note.txt"), "hello")
        write(File(upload, "nested/deep.txt"), "deep")

        val archive = File(tmp.root, "out.zip")
        val result = BackupArchiveCodec.pack(
            archive = archive,
            settingsFile = settings,
            databaseFile = database,
            assetDirs = assetDirs("upload" to upload),
            includeFiles = true,
            manifestFor = manifestFor(includeChats = true, includeFiles = true),
        )

        // The returned entry table is what callers verify against, so it
        // includes the manifest itself...
        assertEquals(
            setOf("settings.json", "database/kelivo.db", "upload/note.txt", "upload/nested/deep.txt", "manifest.json"),
            result.entries.keys,
        )

        // ...while the manifest embedded in the archive describes everything
        // except itself, matching `_buildBackupManifestJson` (the Dart writer
        // serialises the manifest before adding its own entry).
        val manifest = BackupArchiveCodec.readManifest(archive)
        assertEquals(
            setOf("settings.json", "database/kelivo.db", "upload/note.txt", "upload/nested/deep.txt"),
            manifest.entries.keys,
        )
        assertEquals(3, manifest.database!!.schemaVersion)

        // Verification must accept an archive this codec just produced.
        BackupArchiveCodec.verifyPacked(archive, result.entries)

        val outDir = File(tmp.root, "restored")
        val extracted = BackupArchiveCodec.extract(archive, outDir, manifest)
        assertEquals(4, extracted.size) // manifest.json is read, not extracted
        assertEquals("""{"a":1}""", File(outDir, "settings.json").readText())
        assertEquals("SQLite-format-3\u0000binary", File(outDir, "database/kelivo.db").readText())
        assertEquals("hello", File(outDir, "upload/note.txt").readText())
        assertEquals("deep", File(outDir, "upload/nested/deep.txt").readText())
    }

    @Test
    fun `settings-only archive omits the database entry`() {
        val settings = write(File(tmp.root, "staging/settings.json"), """{"b":2}""")
        val archive = File(tmp.root, "settings-only.zip")
        val result = BackupArchiveCodec.pack(
            archive = archive,
            settingsFile = settings,
            databaseFile = null,
            assetDirs = emptyMap(),
            includeFiles = false,
            manifestFor = manifestFor(includeChats = false, includeFiles = false),
        )
        assertEquals(setOf("settings.json", "manifest.json"), result.entries.keys)
        val manifest = BackupArchiveCodec.readManifest(archive)
        assertEquals(BackupManifestCodec.PAYLOAD_SETTINGS_ONLY, manifest.payloadKind)
        assertEquals(null, manifest.database)
    }

    @Test
    fun `manifest is the last entry so it can describe every other entry`() {
        val settings = write(File(tmp.root, "staging/settings.json"), "{}")
        val archive = File(tmp.root, "order.zip")
        val order = mutableListOf<String>()
        BackupArchiveCodec.pack(
            archive = archive,
            settingsFile = settings,
            databaseFile = null,
            assetDirs = emptyMap(),
            includeFiles = false,
            manifestFor = manifestFor(includeChats = false, includeFiles = false),
            onEntryWritten = { name, _ -> order += name },
        )
        assertEquals(listOf("settings.json", "manifest.json"), order)
    }

    @Test
    fun `records the true byte size and digest of every entry`() {
        val payload = "x".repeat(5000)
        val settings = write(File(tmp.root, "staging/settings.json"), payload)
        val archive = File(tmp.root, "digest.zip")
        val result = BackupArchiveCodec.pack(
            archive = archive,
            settingsFile = settings,
            databaseFile = null,
            assetDirs = emptyMap(),
            includeFiles = false,
            manifestFor = manifestFor(includeChats = false, includeFiles = false),
        )
        val entry = result.entries.getValue("settings.json")
        assertEquals(5000L, entry.bytes)
        assertEquals(BackupArchiveCodec.sha256Of(settings), entry.sha256)
    }

    @Test
    fun `rejects a missing settings source before writing anything`() {
        val archive = File(tmp.root, "nope.zip")
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.pack(
                archive = archive,
                settingsFile = File(tmp.root, "absent.json"),
                databaseFile = null,
                assetDirs = emptyMap(),
                includeFiles = false,
                manifestFor = manifestFor(includeChats = false, includeFiles = false),
            )
        }
    }

    @Test
    fun `rejects a missing database snapshot`() {
        val settings = write(File(tmp.root, "staging/settings.json"), "{}")
        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.pack(
                archive = File(tmp.root, "nope2.zip"),
                settingsFile = settings,
                databaseFile = File(tmp.root, "absent.db"),
                assetDirs = emptyMap(),
                includeFiles = false,
                manifestFor = manifestFor(includeChats = true, includeFiles = false),
            )
        }
    }

    @Test
    fun `detects a tampered payload`() {
        val settings = write(File(tmp.root, "staging/settings.json"), """{"ok":true}""")
        val archive = File(tmp.root, "tampered.zip")
        val result = BackupArchiveCodec.pack(
            archive = archive,
            settingsFile = settings,
            databaseFile = null,
            assetDirs = emptyMap(),
            includeFiles = false,
            manifestFor = manifestFor(includeChats = false, includeFiles = false),
        )

        // Rewrite the archive with different settings bytes but the original
        // manifest, simulating corruption in transit.
        val manifestJson = BackupArchiveCodec.readEntry(archive, "manifest.json")!!.toString(Charsets.UTF_8)
        val forged = File(tmp.root, "forged.zip")
        val badSettings = write(File(tmp.root, "forged/settings.json"), """{"ok":false}""")
        java.util.zip.ZipOutputStream(forged.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("settings.json"))
            zip.write(badSettings.readBytes())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zip.write(manifestJson.toByteArray())
            zip.closeEntry()
        }

        assertThrows(IllegalStateException::class.java) {
            BackupArchiveCodec.verifyPacked(forged, result.entries)
        }
        assertThrows(IllegalStateException::class.java) {
            BackupArchiveCodec.extract(forged, File(tmp.root, "forged-out"), BackupArchiveCodec.readManifest(forged))
        }
    }

    @Test
    fun `extraction refuses an entry the manifest never declared`() {
        val settings = write(File(tmp.root, "staging/settings.json"), "{}")
        val archive = File(tmp.root, "smuggled.zip")
        val result = BackupArchiveCodec.pack(
            archive = archive,
            settingsFile = settings,
            databaseFile = null,
            assetDirs = emptyMap(),
            includeFiles = false,
            manifestFor = manifestFor(includeChats = false, includeFiles = false),
        )
        val manifest = BackupArchiveCodec.readManifest(archive)
        assertEquals(2, result.entries.size)

        // Same manifest, but an extra undeclared entry rides along.
        val smuggler = File(tmp.root, "smuggler.zip")
        java.util.zip.ZipOutputStream(smuggler.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("settings.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("upload/evil.sh"))
            zip.write("rm -rf /".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zip.write(BackupArchiveCodec.readEntry(archive, "manifest.json")!!)
            zip.closeEntry()
        }

        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.extract(smuggler, File(tmp.root, "smuggler-out"), manifest)
        }
    }

    @Test
    fun `extraction refuses a traversal entry name`() {
        val settings = write(File(tmp.root, "staging/settings.json"), "{}")
        val archive = File(tmp.root, "traverse.zip")
        BackupArchiveCodec.pack(
            archive = archive,
            settingsFile = settings,
            databaseFile = null,
            assetDirs = emptyMap(),
            includeFiles = false,
            manifestFor = manifestFor(includeChats = false, includeFiles = false),
        )
        val manifestBytes = BackupArchiveCodec.readEntry(archive, "manifest.json")!!

        val evil = File(tmp.root, "evil.zip")
        java.util.zip.ZipOutputStream(evil.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("../../etc/passwd"))
            zip.write("pwned".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zip.write(manifestBytes)
            zip.closeEntry()
        }

        assertThrows(IllegalArgumentException::class.java) {
            BackupArchiveCodec.extract(evil, File(tmp.root, "evil-out"), BackupManifestCodec.decode(manifestBytes.toString(Charsets.UTF_8)))
        }
        assertTrue(!File(tmp.root.parentFile, "etc/passwd").exists())
    }

    @Test
    fun `readManifest fails clearly when the manifest is absent`() {
        val archive = File(tmp.root, "no-manifest.zip")
        java.util.zip.ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("settings.json"))
            zip.write("{}".toByteArray())
            zip.closeEntry()
        }
        val error = assertThrows(IllegalStateException::class.java) {
            BackupArchiveCodec.readManifest(archive)
        }
        assertTrue(error.message!!.contains("manifest.json"))
    }

    @Test
    fun `canonicalName normalises separators and leading slashes`() {
        assertEquals("upload/a.png", BackupArchiveCodec.canonicalName("upload\\a.png"))
        assertEquals("upload/a.png", BackupArchiveCodec.canonicalName("/upload/a.png"))
        assertEquals("upload/a.png", BackupArchiveCodec.canonicalName("//upload/a.png"))
    }

    @Test
    fun `asset directories are packed in the documented root order`() {
        val settings = write(File(tmp.root, "staging/settings.json"), "{}")
        val dirs = linkedMapOf<String, File>()
        for (root in listOf("fonts", "images", "upload", "avatars", "videos")) {
            val d = File(tmp.root, "files/$root").apply { mkdirs() }
            write(File(d, "$root.bin"), root)
            dirs[root] = d
        }
        val archive = File(tmp.root, "order-roots.zip")
        val order = mutableListOf<String>()
        BackupArchiveCodec.pack(
            archive = archive,
            settingsFile = settings,
            databaseFile = null,
            assetDirs = dirs,
            includeFiles = true,
            manifestFor = manifestFor(includeChats = false, includeFiles = true),
            onEntryWritten = { name, _ -> order += name },
        )
        // ASSET_ROOTS order wins over the caller's map order, per the Dart writer.
        assertEquals(
            listOf(
                "settings.json",
                "upload/upload.bin",
                "avatars/avatars.bin",
                "images/images.bin",
                "fonts/fonts.bin",
                // 生成视频（自研功能）的产出目录也要进归档。
                "videos/videos.bin",
                "manifest.json",
            ),
            order,
        )
    }

    @Test
    fun `identical files under two asset roots get distinct prefixed names`() {
        // Two roots may legitimately point at the same source directory (e.g. a
        // caller wiring both `upload` and `images` to one folder); the root
        // prefix keeps the entry names distinct, so this must NOT collide.
        val settings = write(File(tmp.root, "staging/settings.json"), "{}")
        val shared = File(tmp.root, "shared").apply { mkdirs() }
        write(File(shared, "same.bin"), "x")
        val archive = File(tmp.root, "shared-roots.zip")
        val order = mutableListOf<String>()
        BackupArchiveCodec.pack(
            archive = archive,
            settingsFile = settings,
            databaseFile = null,
            assetDirs = assetDirs("upload" to shared, "images" to shared),
            includeFiles = true,
            manifestFor = manifestFor(includeChats = false, includeFiles = true),
            onEntryWritten = { name, _ -> order += name },
        )
        assertEquals(
            listOf("settings.json", "upload/same.bin", "images/same.bin", "manifest.json"),
            order,
        )
    }

    @Test
    fun `a duplicate entry name is refused by the collision ledger`() {
        // The writer's collision guard is `ZipEntryNames.CollisionLedger`; a
        // duplicate would silently overwrite the earlier entry and leave the
        // manifest describing data the archive no longer holds.
        val ledger = ZipEntryNames.CollisionLedger()
        ledger.claim("settings.json")
        ledger.claim("database/kelivo.db")
        assertEquals(2, ledger.count)
        assertThrows(IllegalStateException::class.java) { ledger.claim("settings.json") }
    }

    @Test
    fun `the collision ledger folds case like the Dart writer`() {
        val ledger = ZipEntryNames.CollisionLedger()
        ledger.claim("Upload/Photo.PNG")
        // Windows and macOS filesystems fold case, so these are the same entry.
        assertThrows(IllegalStateException::class.java) { ledger.claim("upload/photo.png") }
    }

    @Test
    fun `packing the same file under two roots yields distinct entries`() {
        val settings = write(File(tmp.root, "staging/settings.json"), "{}")
        val shared = File(tmp.root, "shared").apply { mkdirs() }
        write(File(shared, "same.bin"), "x")
        val archive = File(tmp.root, "shared-roots.zip")
        val order = mutableListOf<String>()
        BackupArchiveCodec.pack(
            archive = archive,
            settingsFile = settings,
            databaseFile = null,
            assetDirs = assetDirs("upload" to shared, "images" to shared),
            includeFiles = true,
            manifestFor = manifestFor(includeChats = false, includeFiles = true),
            onEntryWritten = { name, _ -> order += name },
        )
        // The root prefix keeps them apart, so this must not collide.
        assertEquals(
            listOf("settings.json", "upload/same.bin", "images/same.bin", "manifest.json"),
            order,
        )
    }
}
