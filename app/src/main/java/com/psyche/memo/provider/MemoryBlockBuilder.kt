package com.psyche.memo.provider

import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.MemoryEntry
import com.psyche.memo.ui.MemoryPromptLang
import com.psyche.memo.ui.MemoryPrompts
import com.psyche.memo.ui.MemoryScope
import com.psyche.memo.ui.MemorySettingsKeys
import com.psyche.memo.ui.MemorySettingsState
import com.psyche.memo.ui.MemoryStatus
import com.psyche.memo.ui.MemoryType
import com.psyche.memo.ui.ProfileField
import com.psyche.memo.ui.UserProfileRepository
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Port of memory_block_builder.dart — pure serialization for the
 * `<user_profile>` / `<user_memory>` injection blocks, plus the convenience
 * snapshot builder the chat request uses.
 */
object MemoryBlockBuilder {

    private val TYPE_ORDER = listOf(
        MemoryType.identity, MemoryType.workflow, MemoryType.voice, MemoryType.instruction,
    )

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /** §7.4 SHA-256 of profileBlock + memoryBlock, hex, first 16 chars. */
    fun hashBlocks(profileBlock: String, memoryBlock: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((profileBlock + memoryBlock).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(16)
    }

    fun buildFullSnapshotPrefix(
        profileBlock: String,
        memoryBlock: String,
        lang: MemoryPromptLang,
    ): String = MemoryPrompts.introFullFor(lang) + "\n" + profileBlock + memoryBlock + "\n"

    fun buildMemoryBlock(
        visible: List<MemoryEntry>,
        totalByType: Map<MemoryType, Int>,
        lang: MemoryPromptLang,
        maxItems: Int,
    ): String {
        val out = StringBuilder()
        for (type in TYPE_ORDER) {
            val list = visible.filter { it.type == type }
            if (list.isEmpty()) {
                out.append("<user_memory type=\"").append(type.wire).append("\"/>\n")
                continue
            }
            val total = totalByType[type] ?: list.size
            val summary = total > maxItems
            var selected: List<MemoryEntry> = if (!summary) {
                list.toList()
            } else {
                list.sortedWith(compareByDescending<MemoryEntry> { it.updatedAt }.thenBy { it.id })
                    .let { if (it.size > maxItems) it.subList(0, maxItems) else it }
            }
            selected = selected.sortedWith(
                compareBy<MemoryEntry> { scopeRank(it.scope) }
                    .thenBy { it.createdAt }
                    .thenBy { it.id },
            )
            if (summary) {
                out.append("<user_memory type=\"").append(type.wire)
                    .append("\" mode=\"summary\" total=\"").append(total)
                    .append("\" shown=\"").append(selected.size).append("\">\n")
            } else {
                out.append("<user_memory type=\"").append(type.wire).append("\">\n")
            }
            for (e in selected) {
                val marker = if (e.scope == MemoryScope.assistant) "(assistant) " else ""
                out.append("- [").append(fmtDate(e.updatedAt)).append("] ")
                    .append(marker).append(flatten(escape(e.content))).append('\n')
            }
            if (summary) out.append(MemoryPrompts.moreHintFor(lang)).append('\n')
            out.append("</user_memory>\n")
        }
        return out.toString()
    }

    fun buildProfileBlock(fields: List<ProfileField>, lang: MemoryPromptLang): String {
        val nonEmpty = fields.filter { it.value.trim().isNotEmpty() }
        if (nonEmpty.isEmpty()) return "<user_profile/>\n"
        val byKey = nonEmpty.associateBy { it.key }
        val out = StringBuilder("<user_profile>\n")
        for (key in UserProfileRepository.knownKeys) {
            val field = byKey[key] ?: continue
            out.append('<').append(key).append('>')
                .append(escape(field.value))
                .append("</").append(key).append(">\n")
        }
        byKey.keys.filter { it.startsWith("custom.") }.sorted().forEach { key ->
            val name = key.substring("custom.".length)
            out.append("<custom name=\"").append(escapeAttr(name)).append("\">")
                .append(escape(byKey[key]!!.value))
                .append("</custom>\n")
        }
        out.append("</user_profile>\n")
        return out.toString()
    }

    /**
     * Builds the snapshot prefix for the current assistant, or "" when there is
     * nothing to inject. Mirrors message_builder_service._buildMemoryPrefix
     * (profile + memory blocks, maxItems from memory_injection_max_items_v1).
     */
    fun buildPrefix(container: AppContainerImpl, assistant: Assistant): String {
        if (!assistant.enableMemory) return ""
        val lang = MemorySettingsState(container).resolvedPromptLang()
        val maxItems = MemorySettingsState(container).injectionMaxItems

        val fields = UserProfileRepository.fields(container)
        val provider = container.memoryProviderV2
        provider.ensureLoaded()
        val visible = provider.visibleFor(assistant.id)
            .filter { it.status == MemoryStatus.active }
        val totalByType = visible.groupingBy { it.type }.eachCount()
        val hasProfile = fields.any { it.value.trim().isNotEmpty() }
        if (!hasProfile && visible.isEmpty()) return ""

        val profileBlock = buildProfileBlock(fields, lang)
        val memoryBlock = buildMemoryBlock(visible, totalByType, lang, maxItems)
        return buildFullSnapshotPrefix(profileBlock, memoryBlock, lang)
    }

    fun fmtDate(epochMicros: Long): String =
        Instant.ofEpochSecond(epochMicros / 1_000_000, (epochMicros % 1_000_000) * 1_000)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(dateFormatter)

    /** Escape `&`, `<`, `>` only. */
    fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    /** Escape for XML attribute values (`"` in addition). */
    fun escapeAttr(text: String): String = escape(text).replace("\"", "&quot;")

    /** Collapse whitespace runs to a single space, then trim. */
    fun flatten(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun scopeRank(scope: MemoryScope): Int = if (scope == MemoryScope.global) 0 else 1

    /** Test hook: the memory-injection max items key. */
    const val MAX_ITEMS_KEY = MemorySettingsKeys.INJECTION_MAX_ITEMS
}
