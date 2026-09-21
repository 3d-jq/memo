package com.psyche.memo.data.backup

import com.psyche.memo.data.db.SchemaMigrations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The `manifest.json` contract of a Memo backup archive.
 *
 * Ported verbatim from `lib/core/services/backup/data_sync.dart`
 * (`_buildBackupManifestJson` L1948-1986, constants L248-280). Every key name,
 * the `format` marker and the version numbers are part of the on-disk format:
 * a Flutter build writes these archives and an Android build must read them,
 * and vice versa. Do not rename or reorder without also bumping
 * [FORMAT_VERSION] / [MINIMUM_READABLE_FORMAT_VERSION].
 *
 * Hand-written JSON is used instead of `@Serializable` data classes because the
 * original writes the map through `jsonEncode` with a fixed insertion order and
 * omits the `database` block entirely for settings-only payloads; keeping the
 * writer explicit makes that alignment reviewable line by line.
 */
object BackupManifestCodec {

    const val FORMAT = "kelivo-backup"
    const val FORMAT_VERSION = 2

    /**
     * The oldest archive format that can still read a backup this build writes.
     *
     * Raise in lockstep with [FORMAT_VERSION] whenever an archive change is NOT
     * purely additive — anything an older build would misread rather than
     * merely fail to recognise.
     */
    const val MINIMUM_READABLE_FORMAT_VERSION = 2

    const val KEY_MINIMUM_READABLE_FORMAT = "minimumReadableFormatVersion"
    const val KEY_SCHEMA_MINIMUM_READABLE = "minimumReadableSchemaVersion"

    const val ENTRY_MANIFEST = "manifest.json"
    const val ENTRY_SETTINGS = "settings.json"
    const val ENTRY_DATABASE = "database/kelivo.db"

    const val PAYLOAD_SQLITE = "sqlite"
    const val PAYLOAD_SETTINGS_ONLY = "settings-only"

    // Restore-side bounds, mirrored from data_sync.dart L273-279.
    const val MAX_MANIFEST_BYTES = 16L * 1024 * 1024
    const val MAX_SETTINGS_BYTES = 1024L * 1024 * 1024
    const val MAX_RESTORE_ENTRY_BYTES = 8L * 1024 * 1024 * 1024
    const val MAX_RESTORE_TOTAL_BYTES = 16L * 1024 * 1024 * 1024
    const val MAX_RESTORE_ENTRIES = 100_000

    /** Writes the manifest exactly as `_buildBackupManifestJson` does. */
    fun encode(
        entries: Map<String, EntryMetadata>,
        database: DatabaseInfo?,
        includeChats: Boolean,
        includeFiles: Boolean,
        appVersion: String,
        createdAtUtc: String,
        businessEntityRowIds: Map<String, List<String>>,
    ): String = buildJsonObject {
        put("format", FORMAT)
        put("formatVersion", FORMAT_VERSION)
        put(KEY_MINIMUM_READABLE_FORMAT, MINIMUM_READABLE_FORMAT_VERSION)
        put("payloadKind", if (includeChats) PAYLOAD_SQLITE else PAYLOAD_SETTINGS_ONLY)
        put("createdAtUtc", createdAtUtc)
        put("appVersion", appVersion)
        put("includeChats", includeChats)
        put("includeFiles", includeFiles)
        put("secretsIncluded", true)
        put(
            "businessEntityRowIds",
            JsonObject(businessEntityRowIds.mapValues { (_, ids) ->
                JsonArray(ids.map { JsonPrimitive(it) })
            }),
        )
        if (database != null) {
            put("database", buildJsonObject {
                put("entry", database.entry)
                put("schemaVersion", database.schemaVersion)
                put(KEY_SCHEMA_MINIMUM_READABLE, database.minimumReadableSchemaVersion)
                put("conversationCount", database.conversationCount)
                put("messageCount", database.messageCount)
            })
        }
        put("entries", buildJsonObject {
            entries.forEach { (name, meta) ->
                put(name, buildJsonObject {
                    put("bytes", meta.bytes)
                    put("sha256", meta.sha256)
                })
            }
        })
    }.toString()

    fun decode(json: String): BackupManifest {
        val root = JSON.parseToJsonElement(json).jsonObject
        val format = root["format"]?.jsonPrimitive?.content ?: ""
        val formatVersion = root["formatVersion"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val minReadable = root[KEY_MINIMUM_READABLE_FORMAT]
            ?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val payloadKind = root["payloadKind"]?.jsonPrimitive?.content ?: PAYLOAD_SETTINGS_ONLY
        val entriesJson = root["entries"]?.jsonObject ?: JsonObject(emptyMap())
        val entries = entriesJson.mapValues { (_, value) ->
            val obj = value.jsonObject
            EntryMetadata(
                bytes = obj["bytes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                sha256 = obj["sha256"]?.jsonPrimitive?.content ?: "",
            )
        }
        val database = root["database"]?.let { element ->
            val obj = element.jsonObject
            DatabaseInfo(
                entry = obj["entry"]?.jsonPrimitive?.content ?: ENTRY_DATABASE,
                schemaVersion = obj["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                // Absent or malformed = no declaration (the forwardUndeclared
                // axis), not zero.
                minimumReadableSchemaVersion = obj[KEY_SCHEMA_MINIMUM_READABLE]
                    ?.jsonPrimitive?.content?.toIntOrNull(),
                conversationCount = obj["conversationCount"]
                    ?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                messageCount = obj["messageCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        }
        val entityRowIds = root["businessEntityRowIds"]?.jsonObject?.mapValues { (_, element) ->
            // Decoded leniently: a malformed row-id projection must not make
            // the whole archive unreadable, it only disables id-stable merge.
            (element as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
        } ?: emptyMap()
        return BackupManifest(
            format = format,
            formatVersion = formatVersion,
            minimumReadableFormatVersion = minReadable,
            payloadKind = payloadKind,
            createdAtUtc = root["createdAtUtc"]?.jsonPrimitive?.content ?: "",
            appVersion = root["appVersion"]?.jsonPrimitive?.content ?: "",
            includeChats = root["includeChats"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            includeFiles = root["includeFiles"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            secretsIncluded = root["secretsIncluded"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
            businessEntityRowIds = entityRowIds,
            database = database,
            entries = entries,
        )
    }

    /**
     * `_acceptsArchiveFormat` (with the `format` marker check upstream runs
     * alongside it): whether this build may read an archive of the manifest's
     * format version. An exact match, plus one relaxation: a NEWER archive
     * that vouches for us through `minimumReadableFormatVersion` (a
     * declaration in 1..[FORMAT_VERSION]). An undeclared newer archive is
     * refused — unlike the database axis there are no undeclared newer
     * archives in the wild to serve, since every build that can write a newer
     * format also writes the declaration. Older archive formats were never
     * supported and still are not.
     */
    fun acceptsFormat(manifest: BackupManifest): Boolean =
        manifest.format == FORMAT && when {
            manifest.formatVersion == FORMAT_VERSION -> true
            manifest.formatVersion < FORMAT_VERSION -> false
            else -> manifest.minimumReadableFormatVersion in 1..FORMAT_VERSION
        }

    /**
     * `_declaresNewerBuild`: the manifest was written by a build newer than
     * this one, on either axis — a newer archive format, or a newer database
     * schema. Both move independently and both matter: a newer build can add
     * an ignorable directory without touching the schema, and a settings-only
     * backup has no schema at all.
     */
    fun declaresNewerBuild(manifest: BackupManifest): Boolean =
        manifest.formatVersion > FORMAT_VERSION ||
            (manifest.database?.schemaVersion ?: 0) > SchemaMigrations.CURRENT_SCHEMA_VERSION

    private val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
}

data class EntryMetadata(val bytes: Long, val sha256: String)

data class DatabaseInfo(
    val entry: String,
    val schemaVersion: Int,
    /** `minimumReadableSchemaVersion` declaration; null when absent. */
    val minimumReadableSchemaVersion: Int?,
    val conversationCount: Int,
    val messageCount: Int,
)

data class BackupManifest(
    val format: String,
    val formatVersion: Int,
    val minimumReadableFormatVersion: Int,
    val payloadKind: String,
    val createdAtUtc: String,
    val appVersion: String,
    val includeChats: Boolean,
    val includeFiles: Boolean,
    val secretsIncluded: Boolean,
    val businessEntityRowIds: Map<String, List<String>>,
    val database: DatabaseInfo?,
    val entries: Map<String, EntryMetadata>,
)
