package com.psyche.memo.ui

import com.psyche.memo.provider.SkillTools
import com.psyche.memo.provider.workspace.WorkspaceTools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * 1:1 port of the tool-schema data layer:
 *   lib/core/models/tool_schema_override.dart
 *   lib/core/services/tools/tool_schema_overrides.dart (describeParams walk)
 *   lib/core/services/tools/built_in_tool_catalog.dart
 *   lib/core/services/search/search_tool_service.dart getToolDefinition
 *   lib/core/services/memory/memory_tools.dart catalog/legacyDefinitions
 *   lib/features/home/services/local_tools_service.dart definitionFor
 * (Only the schema *descriptions* are user-editable; structure stays locked.
 * Platform availability mirrors DeviceLocalTools on Android: screen time and
 * calendar are available; location/weather/health/reminders are iOS-only.)
 */
data class ToolSchemaOverride(
    val description: String? = null,
    val paramDescriptions: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() {
            if (!description.isNullOrBlank()) return false
            return paramDescriptions.values.all { it.isBlank() }
        }

    /** tool_schema_override.dart toJson — blank values dropped. */
    fun toJson(): JsonObject {
        val obj = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        if (!description.isNullOrBlank()) obj["description"] = JsonPrimitive(description)
        val params = paramDescriptions.filterValues { it.isNotBlank() }
        if (params.isNotEmpty()) {
            obj["paramDescriptions"] = JsonObject(params.mapValues { JsonPrimitive(it.value) })
        }
        return JsonObject(obj)
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        fun fromJson(obj: JsonObject): ToolSchemaOverride {
            val desc = (obj["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val params = LinkedHashMap<String, String>()
            (obj["paramDescriptions"] as? JsonObject)?.forEach { (k, v) ->
                val s = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!s.isNullOrBlank()) params[k] = s
            }
            return ToolSchemaOverride(description = desc, paramDescriptions = params)
        }

        fun fromJsonString(text: String): ToolSchemaOverride =
            fromJson(Json.parseToJsonElement(text).jsonObject)
    }
}

/** tool_schema_overrides.dart ToolParamDescriptor. */
data class ToolParamDescriptor(
    val path: String,
    val type: String?,
    val enumValues: List<String>?,
    val defaultDescription: String?,
)

enum class BuiltInToolGroup { SEARCH, MEMORY, LOCAL, SKILL, WORKSPACE, BROWSER, GENERATION }

/** built_in_tool_catalog.dart BuiltInToolCatalogEntry (schema as JSON). */
data class BuiltInToolCatalogEntry(
    val name: String,
    val defaultDefinition: JsonObject,
    val group: BuiltInToolGroup,
) {
    val defaultDescription: String?
        get() = stringAt(defaultDefinition, "function", "description")

    companion object {
        internal fun stringAt(obj: JsonObject, vararg path: String): String? {
            // Walk intermediate levels as JsonObject; the final key addresses a
            // JsonPrimitive on the current node (e.g. def["function"]["description"]).
            var node: JsonObject = obj
            for ((index, key) in path.withIndex()) {
                if (index == path.size - 1) {
                    val value = node[key] as? JsonPrimitive ?: return null
                    return if (value.isString) value.content else null
                }
                node = node[key] as? JsonObject ?: return null
            }
            return null
        }
    }
}

object BuiltInToolCatalog {

    /** Local tool availability — Android side of DeviceLocalTools (L57-82). */
    fun isAvailableOnThisPlatform(name: String): Boolean = when (name) {
        LocalToolNames.SCREEN_TIME, LocalToolNames.CALENDAR_QUERY, LocalToolNames.CALENDAR_CREATE,
        // 上游 locationSupported 是 iOS-only；安卓侧的执行器是本工程加的（见 LocationTool）。
        LocalToolNames.CURRENT_LOCATION -> true
        LocalToolNames.WEATHER,
        LocalToolNames.HEALTH_SUMMARY, LocalToolNames.REMINDERS_QUERY,
        LocalToolNames.REMINDERS_CREATE, LocalToolNames.REMINDERS_COMPLETE,
        -> false // iOS-only (iosDeviceToolsSupported)
        else -> true
    }

    /**
     * 递给模型的本地工具名 —— **只有安卓侧真有执行器的那批**（`TIME_INFO` 与 `ASK_USER`
     * 在 ToolHandler 里各自特殊处理，没有通用执行器）。
     *
     * 为什么从 [com.psyche.memo.provider.LocalToolExecutors.EXECUTABLE] 推导而不是另抄一份
     * 名单：`ChatViewModel.offeredTools()` 以前自己写了一个 `setOf(...)`，和
     * [isAvailableOnThisPlatform]、`EXECUTABLE` 是三份手维护的清单 —— 加定位工具时我只开了
     * 后两道，第一道没改，工具就被静默滤掉、模型答「我没有这个工具」（`LocalToolOfferingTest`
     * 现在钉住三者的一致性）。
     */
    fun offeredLocalToolNames(): Set<String> =
        com.psyche.memo.provider.LocalToolExecutors.EXECUTABLE + setOf(
            LocalToolNames.TIME_INFO,
            LocalToolNames.ASK_USER,
        )

    /** built_in_tool_catalog.dart entries(lang) — the legacy-memory variant is not ported. */
    fun entries(lang: MemoryPromptLang): List<BuiltInToolCatalogEntry> {
        val out = mutableListOf<BuiltInToolCatalogEntry>()
        out.add(
            BuiltInToolCatalogEntry(
                name = "search_web",
                defaultDefinition = searchWebDefinition(),
                group = BuiltInToolGroup.SEARCH,
            ),
        )
        for (def in catalogMemoryDefinitions(lang)) {
            val name = BuiltInToolCatalogEntry.stringAt(def, "function", "name") ?: continue
            out.add(BuiltInToolCatalogEntry(name, def, BuiltInToolGroup.MEMORY))
        }
        for (name in LocalToolNames.all) {
            if (!isAvailableOnThisPlatform(name)) continue
            out.add(BuiltInToolCatalogEntry(name, localDefinition(name), BuiltInToolGroup.LOCAL))
        }
        // Memo 自己加的两类工具（上游没有）：Agent Skills 与沙箱工作区。
        // 用户 2026-09-16：「设置里面的工具描述是不是没有跟新还有工作区工具呀」——
        // 之前这个目录只列了搜索/记忆/本地工具，工作区和技能的工具在「工具描述」页
        // 根本看不到，describeParams 也拿不到它们的参数。
        for (spec in SkillTools.catalogDefinitions()) {
            out.add(BuiltInToolCatalogEntry(spec.name, definitionOf(spec), BuiltInToolGroup.SKILL))
        }
        for (spec in WorkspaceTools.catalogDefinitions()) {
            out.add(BuiltInToolCatalogEntry(spec.name, definitionOf(spec), BuiltInToolGroup.WORKSPACE))
        }
        // Agent 浏览器（app 级，14 颗）：用户 2026-09-26「在工具描述里面加一下吧」——
        // 对话里的卡片有中文标题与图标，这一页却说不出模型手上到底有哪几颗、叫什么名。
        // 名单取自 `BrowserTools.catalogDefinitions()`（= 真正递给模型的那份），不在这里抄第二份。
        // ⚠️ 它们**不进** `LocalToolNames.all`：那是「助手勾了才执行」的双闸，浏览器是设备能力
        //（`ToolRulesTest.the app level browser tools stay out of the assistant gated local tool list`）。
        for (spec in com.psyche.memo.provider.browser.BrowserTools.catalogDefinitions()) {
            out.add(BuiltInToolCatalogEntry(spec.name, definitionOf(spec), BuiltInToolGroup.BROWSER))
        }
        // 生成工具（自研功能）：图片 / 视频各一条，描述可在「工具描述」页改。
        for (spec in com.psyche.memo.provider.generation.GenerationTools.catalogDefinitions()) {
            out.add(BuiltInToolCatalogEntry(spec.name, definitionOf(spec), BuiltInToolGroup.GENERATION))
        }
        return out
    }

    /** 运行时工具（[LlmToolSpec]）→ 目录里的 `{"type":"function","function":{…}}` JSON。 */
    fun definitionOf(spec: com.psyche.memo.llm.client.LlmToolSpec): JsonObject {
        val parameters = runCatching { Json.parseToJsonElement(spec.inputSchemaJson) as? JsonObject }
            .getOrNull()
            ?: JsonObject(emptyMap())
        return kotlinx.serialization.json.buildJsonObject {
            put("type", JsonPrimitive("function"))
            put(
                "function",
                kotlinx.serialization.json.buildJsonObject {
                    put("name", JsonPrimitive(spec.name))
                    put("description", JsonPrimitive(spec.description))
                    put("parameters", parameters)
                },
            )
        }
    }

    /** 目录里全部内置工具名（工具描述覆盖只认这些名字，MCP 工具不动）。 */
    fun allBuiltInNames(lang: MemoryPromptLang): Set<String> =
        entries(lang).map { it.name }.toSet()

    /** ToolSchemaOverrides.describeParams — walk the default schema. */
    fun describeParams(def: JsonObject): List<ToolParamDescriptor> {
        val parameters = (def["function"] as? JsonObject)?.get("parameters") as? JsonObject
            ?: return emptyList()
        val properties = parameters["properties"] as? JsonObject ?: return emptyList()
        val out = mutableListOf<ToolParamDescriptor>()
        walkProperties(properties, "", out)
        return out
    }

    private fun walkProperties(properties: JsonObject, prefix: String, out: MutableList<ToolParamDescriptor>) {
        for ((key, value) in properties) {
            val schema = value as? JsonObject ?: continue
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            val enumValues = (schema["enum"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
            out.add(
                ToolParamDescriptor(
                    path = path,
                    type = (schema["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                    enumValues = enumValues,
                    defaultDescription = (schema["description"] as? JsonPrimitive)
                        ?.takeIf { it.isString }?.content,
                ),
            )
            (schema["properties"] as? JsonObject)?.let { walkProperties(it, path, out) }
            val items = schema["items"] as? JsonObject
            if (items != null) {
                val itemProps = items["properties"] as? JsonObject
                if (itemProps != null) {
                    walkProperties(itemProps, "$path.items", out)
                } else {
                    val itemDesc = (items["description"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    if (itemDesc != null) {
                        out.add(
                            ToolParamDescriptor(
                                path = "$path.items",
                                type = (items["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                                enumValues = (items["enum"] as? JsonArray)
                                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() },
                                defaultDescription = itemDesc,
                            ),
                        )
                    }
                }
            }
        }
    }

    // —— search_web (search_tool_service.dart L51-69) ——

    private fun searchWebDefinition(): JsonObject = definition(
        name = "search_web",
        description = """
            Search the web for current information, news, and real-time data.

            Use this when:
            - The user asks about recent events, current prices, or live data
            - You need to verify facts you are uncertain about or that may have changed
            - The user references something you don't have context on (products, people, docs, APIs)

            Don't use for:
            - Math, code reasoning, or things you can answer from your training
            - Well-known facts unlikely to have changed

            Write focused keyword queries, not full sentences. You may call this multiple times to broaden coverage:
            - If the topic likely has more authoritative sources in another language (English for tech/scientific topics, the local language for regional news), repeat the search with the query translated into that language.
            - If the first results miss an angle, refine with synonyms or sub-aspects.

            Response format:
            - items[]: search results, each with index (result number), id (short unique id), title, url, text
            - answer: an optional pre-synthesized answer (may be absent)

            Cite: append [cite:id] immediately after each statement a result supports, using that result's exact `id` field.
        """.trimIndent(),
        properties = listOf(
            param("query", "string", description = "The search query to look up online"),
        ),
        required = listOf("query"),
    )

    // —— memory tools (memory_tools.dart L883-1143) ——

    fun catalogMemoryDefinitions(lang: MemoryPromptLang): List<JsonObject> = listOf(
        defMemoryRead(lang),
        defMemorySearchProfile(lang),
        defMemoryUpdate(lang, includeScope = true),
        defMemoryEdit(lang),
        defMemoryDelete(lang),
        defUpdateUserProfile(lang),
        defChatSearch(lang),
    )

    private fun defMemoryRead(lang: MemoryPromptLang): JsonObject {
        val zh = lang == MemoryPromptLang.zh
        return definition(
            name = "memory_read",
            description = if (zh)
                "读取用户的长期记忆。type 可选：identity（姓名、身边的人、职业等身份信息）、workflow（做事方式、工具偏好、调试习惯）、voice（行文风格、句式节奏、用词习惯）、instruction（用户对你的明确要求）。不传 type 则返回全部类型。对话中已经提供了记忆摘要，只有在摘要标了 mode=\"summary\" 被截断、或需要拿到条目 id 时才需要调用。"
            else
                "Read the user's long-term memory. Optional type: identity (name, people around them, occupation, etc.), workflow (ways of working, tool preferences, debugging habits), voice (writing style, rhythm, word choice), instruction (explicit requests to you). Omit type to return all types. A memory summary is already in the conversation; call this only when a block is marked mode=\"summary\" (truncated) or you need entry ids.",
            properties = listOf(
                param(
                    "type", "string",
                    if (zh) "只返回该类型的记忆。省略则返回全部类型。" else "Return only memories of this type. Omit to return all types.",
                    enumValues = listOf("identity", "workflow", "voice", "instruction"),
                ),
                param(
                    "include_archived", "boolean",
                    if (zh) "是否包含已归档的记忆。默认 false。" else "Whether to include archived memories. Default false.",
                ),
                param(
                    "limit", "integer",
                    if (zh) "最多返回多少条，默认 50。" else "Maximum number of entries to return. Default 50.",
                ),
            ),
            required = emptyList(),
        )
    }

    private fun defMemoryUpdate(lang: MemoryPromptLang, includeScope: Boolean): JsonObject {
        val zh = lang == MemoryPromptLang.zh
        val properties = mutableListOf(
            param(
                "type", "string",
                if (zh)
                    "identity 身份信息；workflow 做事方式与工具偏好；voice 表达风格；instruction 用户对你的明确要求。"
                else
                    "identity: identity facts; workflow: ways of working and tool preferences; voice: expression style; instruction: explicit requests to you.",
                enumValues = listOf("identity", "workflow", "voice", "instruction"),
            ),
            param(
                "content", "string",
                if (zh)
                    "一条完整、自包含的第三人称陈述句，例如「用户偏好直接、可落地的中文说明」。不要使用「这个」「刚才」等指回本次对话的词。"
                else
                    "One complete, self-contained third-person statement, e.g. \"The user prefers direct, actionable explanations in Chinese.\" Avoid deictic words that refer back to this conversation.",
            ),
        )
        if (includeScope) {
            properties.add(
                param(
                    "scope", "string",
                    if (zh)
                        "global 对所有助手可见；assistant 只对当前助手可见。省略时按用户设置的默认值。"
                    else
                        "global is visible to all assistants; assistant is visible only to the current assistant. When omitted, uses the user's configured default.",
                    enumValues = listOf("global", "assistant"),
                ),
            )
        }
        return definition(
            name = "memory_update",
            description = if (zh)
                "写入一条用户长期记忆。系统会自动与已有记忆去重合并，不需要先读取再全文替换。只写下次新开对话时仍然成立的稳定信息；本次对话内的临时上下文不要写。"
            else
                "Write one long-term user memory. The system deduplicates and merges with existing memories automatically; you do not need to read then replace. Only write stable facts that will still hold in a future conversation; do not write ephemeral context from this chat.",
            properties = properties,
            required = listOf("type", "content"),
        )
    }

    private fun defMemorySearchProfile(lang: MemoryPromptLang): JsonObject {
        val zh = lang == MemoryPromptLang.zh
        return definition(
            name = "memory_search_profile",
            description = if (zh)
                "搜索用户的长期记忆。当对话中提供的记忆摘要不够详细、被截断（标了 mode=\"summary\"），或需要查找某个特定信息时使用。按关键词匹配，多个关键词之间是「且」关系。"
            else
                "Search the user's long-term memory. Use when the in-conversation memory summary is incomplete, truncated (mode=\"summary\"), or you need a specific fact. Keyword match; multiple keywords are ANDed.",
            properties = listOf(
                param(
                    "query", "string",
                    if (zh) "关键词，多个用空格分隔。中文可以直接写连续短语。" else "Keywords separated by spaces. Continuous Chinese phrases can be used as-is.",
                ),
                param(
                    "type", "string",
                    if (zh) "只在该类型内搜索。省略则搜索全部类型。" else "Search only within this type. Omit to search all types.",
                    enumValues = listOf("identity", "workflow", "voice", "instruction"),
                ),
                param(
                    "limit", "integer",
                    if (zh) "最多返回多少条，默认 10。" else "Maximum number of entries to return. Default 10.",
                ),
            ),
            required = listOf("query"),
        )
    }

    private fun defMemoryEdit(lang: MemoryPromptLang): JsonObject {
        val zh = lang == MemoryPromptLang.zh
        return definition(
            name = "memory_edit",
            description = if (zh)
                "修改一条已有记忆的内容。需要先用 memory_read 或 memory_search_profile 拿到条目 id（形如 mem_xxxxxxxx）。只在记忆内容确实过时或有错时使用；补充新信息请用 memory_update。"
            else
                "Edit the content of an existing memory. First obtain the entry id (e.g. mem_xxxxxxxx) via memory_read or memory_search_profile. Use only when content is outdated or wrong; for new information use memory_update.",
            properties = listOf(
                param("id", "string", if (zh) "条目 id，形如 mem_a1b2c3d4。" else "Entry id, e.g. mem_a1b2c3d4."),
                param("content", "string", if (zh) "修改后的完整内容，会整体替换原内容。" else "The full replacement content."),
            ),
            required = listOf("id", "content"),
        )
    }

    private fun defMemoryDelete(lang: MemoryPromptLang): JsonObject {
        val zh = lang == MemoryPromptLang.zh
        return definition(
            name = "memory_delete",
            description = if (zh)
                "归档一条记忆（软删除）。归档后不再出现在记忆摘要和搜索结果里，用户仍可以在设置中看到并恢复。需要先用 memory_read 或 memory_search_profile 拿到条目 id。只在用户明确表示某条记忆不再成立时使用。"
            else
                "Archive a memory (soft delete). Archived entries disappear from the memory summary and search results, but the user can still see and restore them in settings. First obtain the entry id via memory_read or memory_search_profile. Use only when the user clearly says a memory no longer holds.",
            properties = listOf(
                param("id", "string", if (zh) "条目 id，形如 mem_a1b2c3d4。" else "Entry id, e.g. mem_a1b2c3d4."),
            ),
            required = listOf("id"),
        )
    }

    private fun defUpdateUserProfile(lang: MemoryPromptLang): JsonObject {
        val zh = lang == MemoryPromptLang.zh
        val fieldsItem = param(
            "fields", "array",
            if (zh) "要更新的字段列表。" else "List of fields to update.",
            items = JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "properties" to JsonObject(
                        mapOf(
                            "key" to param(
                                "key", "string",
                                if (zh)
                                    "可用字段：preferred_name（用户希望你怎么称呼他）、gender、pronouns、preferred_language、timezone、occupation、location。其他稳定字段用 custom.<名称>，名称只能是字母、数字、下划线或连字符。"
                                else
                                    "Allowed keys: preferred_name (how the user wants to be addressed), gender, pronouns, preferred_language, timezone, occupation, location. Other stable fields use custom.<name> where name is letters, digits, underscore, or hyphen only.",
                            ),
                            "value" to param(
                                "value", "string",
                                if (zh) "字段取值。传空字符串表示清除该字段。" else "Field value. An empty string clears the field.",
                            ),
                        ),
                    ),
                    "required" to JsonArray(listOf(JsonPrimitive("key"), JsonPrimitive("value"))),
                ),
            ),
        )
        return definition(
            name = "update_user_profile",
            description = if (zh)
                "更新用户画像字段。这些是最稳定的身份信息。不确定的时候不要写。"
            else
                "Update user profile fields. These are the most stable identity facts. Do not write when uncertain.",
            properties = listOf(fieldsItem),
            required = listOf("fields"),
        )
    }

    private fun defChatSearch(lang: MemoryPromptLang): JsonObject {
        val zh = lang == MemoryPromptLang.zh
        return definition(
            name = "chat_search",
            description = if (zh)
                "在历史对话中按关键词搜索消息内容（仅当前助手的会话，以及没有归属助手的旧会话）。需要回忆之前聊过什么，或者用户提到「上次」「之前说的」「我们讨论过」时，优先使用这个工具。默认不搜索当前对话，因为当前对话的内容已经在上下文里。"
            else
                "Search message content in this assistant's past conversations (and unowned older chats) by keywords. Prefer this when recalling prior discussion, or when the user mentions \"last time\", \"earlier\", or \"we discussed\". By default the current conversation is excluded because it is already in context.",
            properties = listOf(
                param("query", "string", if (zh) "关键词，多个用空格分隔。" else "Keywords separated by spaces."),
                param("limit", "integer", if (zh) "最多返回多少条，默认 10。" else "Maximum number of results. Default 10."),
                param(
                    "conversation_id", "string",
                    if (zh)
                        "只在指定会话内搜索。省略则搜索除当前会话外、当前助手可见的会话。"
                    else
                        "Search only within this conversation. Omit to search this assistant's visible conversations except the current one.",
                ),
            ),
            required = listOf("query"),
        )
    }

    // —— local tools (local_tools_service.dart L533-970, Android availability) ——

    object LocalToolNames {
        const val TIME_INFO = "get_time_info"
        const val CLIPBOARD = "clipboard_tool"
        const val TEXT_TO_SPEECH = "text_to_speech"
        const val ASK_USER = "ask_user_input_v0"
        const val CALCULATE = "calculate"
        const val SCREEN_TIME = "get_screen_time"
        const val CALENDAR_QUERY = "calendar_query"
        const val CALENDAR_CREATE = "calendar_create"
        const val CURRENT_LOCATION = "get_current_location"
        const val WEATHER = "get_weather"
        const val HEALTH_SUMMARY = "get_health_summary"
        const val REMINDERS_QUERY = "reminders_query"
        const val REMINDERS_CREATE = "reminders_create"
        const val REMINDERS_COMPLETE = "reminders_complete"

        /**
         * 绘制图表（`render_chart`，自研功能）：模型把数据画成图，本地渲染成 SVG。
         * 名字以 [com.psyche.memo.provider.chart.VisualTools] 为准，这里只是把它接进
         * 「本地工具」这一组（设置 → 工具描述 / 助手编辑页的本地工具 tab 都认这份名单）。
         */
        /**
         * 可视化绘图（`render_visual`，自研）：一个工具、一个 kind 枚举 —— 结构化数据图
         * 由我们画，`kind = "svg"` 时模型直接写 SVG（流程图/时间轴/仪表盘等）。
         */
        const val RENDER_VISUAL = com.psyche.memo.provider.chart.VisualTools.TOOL_NAME

        /** Mermaid 图（`render_mermaid`，自研）：流程图/时序图/状态图/ER/类图/甘特/思维导图等。 */
        const val RENDER_MERMAID = com.psyche.memo.provider.chart.MermaidTools.TOOL_NAME

        val all = listOf(
            TIME_INFO, CLIPBOARD, TEXT_TO_SPEECH, ASK_USER, CALCULATE, SCREEN_TIME,
            CALENDAR_QUERY, CALENDAR_CREATE, CURRENT_LOCATION, WEATHER, HEALTH_SUMMARY,
            REMINDERS_QUERY, REMINDERS_CREATE, REMINDERS_COMPLETE, RENDER_VISUAL, RENDER_MERMAID,
        )
    }

    /** local_tools_service.dart definitionFor — schema builders for the Android-visible set. */
    fun localDefinition(name: String): JsonObject = when (name) {
        // 自研的可视化工具（render_chart）：schema 由工具自己维护（数组套对象的参数
        // 用 param() 那几个 helper 表达不了）。
        LocalToolNames.RENDER_VISUAL -> com.psyche.memo.provider.chart.VisualTools.DEFINITION
        LocalToolNames.RENDER_MERMAID -> com.psyche.memo.provider.chart.MermaidTools.DEFINITION
        // 上游 `_currentLocationDefinition`：空参数 + 那句「只在用户要位置或查天气时用」。
        LocalToolNames.CURRENT_LOCATION -> definition(
            name = LocalToolNames.CURRENT_LOCATION,
            description = com.psyche.memo.provider.LocationTool.DESCRIPTION,
            properties = emptyList(),
            required = null,
        )
        LocalToolNames.TIME_INFO -> definition(
            name = LocalToolNames.TIME_INFO,
            description = "Get the current local date and time info from the device. Returns year, month, day, weekday, ISO date and time strings, timezone, UTC offset, and timestamp.",
            properties = emptyList(),
            required = null,
        )
        LocalToolNames.CLIPBOARD -> definition(
            name = LocalToolNames.CLIPBOARD,
            description = "Read or write plain text from the device clipboard. Use action: read or write. For write, provide text. Do NOT write to the clipboard unless the user has explicitly requested it.",
            properties = listOf(
                param("action", "string", "Operation to perform: read or write", enumValues = listOf("read", "write")),
                param("text", "string", "Text to write to the clipboard. Required for write."),
            ),
            required = listOf("action"),
        )
        LocalToolNames.TEXT_TO_SPEECH -> definition(
            name = LocalToolNames.TEXT_TO_SPEECH,
            description = "Speak text aloud to the user using the configured text-to-speech playback. Use this when the user asks you to read something aloud, or when audio output is appropriate. The tool returns after playback has been requested; audio may continue in the background. Provide natural, readable text without markdown formatting.",
            properties = listOf(
                param("text", "string", "The text to speak aloud."),
            ),
            required = listOf("text"),
        )
        LocalToolNames.ASK_USER -> definition(
            name = LocalToolNames.ASK_USER,
            description = "Ask the user one or more short choice questions when you need clarification, additional information, or a decision before continuing. Supports single-choice and multi-choice questions. The UI will provide Other and Skip options automatically, so do not include those options yourself.",
            properties = listOf(
                param(
                    "questions", "array",
                    "One to four questions to ask the user.",
                    items = JsonObject(
                        mapOf(
                            "type" to JsonPrimitive("object"),
                            "properties" to JsonObject(
                                mapOf(
                                    "id" to param("id", "string", "Unique stable identifier for this question."),
                                    "question" to param("question", "string", "The full question text shown to the user."),
                                    "type" to param(
                                        "type", "string", "Answer type: single choice or multi choice.",
                                        enumValues = listOf("single", "multi"),
                                    ),
                                    "options" to param(
                                        "options", "array", "Suggested options for the user to choose from.",
                                        items = JsonObject(mapOf("type" to JsonPrimitive("string"))),
                                    ),
                                ),
                            ),
                            "required" to JsonArray(listOf(JsonPrimitive("id"), JsonPrimitive("question"))),
                        ),
                    ),
                ),
            ),
            required = listOf("questions"),
        )
        LocalToolNames.CALCULATE -> definition(
            name = LocalToolNames.CALCULATE,
            description = "Evaluate a mathematical expression. Supports: + - * / ^ % !, sin() cos() tan() sqrt() ln() abs() floor() ceil() sgn(), log(base, value), constants pi e. Example: \"5!\", \"sin(pi/4)\", \"log(2, 8)\", \"floor(3.7)\"",
            properties = listOf(
                param(
                    "expression", "string",
                    "A mathematical expression in standard notation, e.g. \"(15 + 3) * 2\", \"2^10\", \"sqrt(144)\"",
                ),
            ),
            required = listOf("expression"),
        )
        LocalToolNames.SCREEN_TIME -> definition(
            name = LocalToolNames.SCREEN_TIME,
            description = deviceTz(
                "Get the user's app screen usage (screen time) over a time range. " +
                    "Specify a custom interval with 'begin'/'end', or use the 'range' preset (today/week). " +
                    "Returns the total foreground time and a per-app breakdown sorted by usage time (descending). " +
                    "{tz} " +
                    "Requires the 'Usage access' special permission; if it is not granted, the device's usage " +
                    "access settings page is opened automatically and an error is returned.",
            ),
            properties = listOf(
                param(
                    "begin", "string",
                    "Start time (inclusive). Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                        "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds. " +
                        "When provided, 'range' is ignored.",
                ),
                param("end", "string", "End time (exclusive), same formats as 'begin'. Defaults to now."),
                param(
                    "range", "string",
                    "Convenience preset, used only when 'begin' is omitted: today or week. Default today.",
                    enumValues = listOf("today", "week"),
                ),
                param(
                    "top", "integer",
                    "Maximum number of top apps to return, sorted by usage time. Default 10.",
                ),
            ),
            required = null,
        )
        LocalToolNames.CALENDAR_QUERY -> definition(
            name = LocalToolNames.CALENDAR_QUERY,
            description = deviceTz(
                "Query calendar events on the user's device within a time range. " +
                    "Specify a custom interval with 'begin'/'end', or use the 'range' preset (today/week/month). " +
                    "Returns a list of events with title, description, location, start/end times, and calendar info. " +
                    "{tz} " +
                    "Requires the 'Calendar' permission; if it is not granted, an error is returned.",
            ),
            properties = listOf(
                param(
                    "begin", "string",
                    "Start time (inclusive). Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                        "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds. " +
                        "When provided, 'range' is ignored.",
                ),
                param("end", "string", "End time (exclusive), same formats as 'begin'."),
                param(
                    "range", "string",
                    "Convenience preset, used only when 'begin' is omitted: today, week, or month. Default today.",
                    enumValues = listOf("today", "week", "month"),
                ),
                param(
                    "query", "string",
                    "Optional keyword to filter events by title (case-insensitive substring match).",
                ),
                param("limit", "integer", "Maximum number of events to return. Default 20."),
            ),
            required = null,
        )
        LocalToolNames.CALENDAR_CREATE -> definition(
            name = LocalToolNames.CALENDAR_CREATE,
            description = deviceTz(
                "Create a new calendar event on the user's device. " +
                    "Requires title and start time at minimum. End time defaults to 1 hour after start. " +
                    "Use 'reminders' to attach notification alerts ahead of the event. " +
                    "The user will be asked to confirm before the event is created. " +
                    "{tz} " +
                    "Requires the 'Calendar' permission; if it is not granted, an error is returned.",
            ),
            properties = listOf(
                param("title", "string", "Event title."),
                param("description", "string", "Event description or notes."),
                param("location", "string", "Event location."),
                param(
                    "start", "string",
                    "Start time. Accepts an ISO-8601 date 'yyyy-MM-dd', a local " +
                        "date-time 'yyyy-MM-ddTHH:mm:ss', an offset date-time, or epoch milliseconds.",
                ),
                param("end", "string", "End time, same formats as 'start'. Defaults to 1 hour after start."),
                param("all_day", "boolean", "Whether this is an all-day event. Default false."),
                param(
                    "reminders", "array",
                    "Optional notification reminders, as minutes before the event start " +
                        "(e.g. [10] for 10 minutes before, [0] for exactly at the start time, " +
                        "[30, 1440] for 30 minutes and 1 day before). For all-day events the " +
                        "offset counts back from the start of the day. No reminder is attached " +
                        "unless you pass this, so include one whenever the user expects to be " +
                        "notified. At most 5 reminders; values are clamped to 0-40320 minutes " +
                        "(4 weeks) and de-duplicated, and the result reports what was actually " +
                        "saved.",
                    items = JsonObject(mapOf("type" to JsonPrimitive("integer"))),
                ),
            ),
            required = listOf("title", "start"),
        )
        else -> definition(name = name, description = "", properties = emptyList(), required = null)
    }

    /** local_tools_service.dart _deviceTimezoneHint. */
    private fun deviceTz(template: String): String {
        val cal = java.util.Calendar.getInstance()
        val offsetMs = java.util.TimeZone.getDefault().getOffset(cal.timeInMillis).toLong()
        val sign = if (offsetMs < 0) "-" else "+"
        val absMin = Math.abs(offsetMs) / 60000L
        val hh = (absMin / 60).toString().padStart(2, '0')
        val mm = (absMin % 60).toString().padStart(2, '0')
        val tzName = java.util.TimeZone.getDefault().id
        val hint =
            "The device timezone is '$tzName' (UTC offset $sign$hh:$mm); times without an explicit offset are interpreted in this timezone."
        return template.replace("{tz}", hint)
    }

    // —— schema builders ——

    private fun param(
        name: String,
        type: String?,
        description: String? = null,
        enumValues: List<String>? = null,
        items: JsonObject? = null,
    ): JsonObject {
        val obj = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        // Property name rides along; definition() strips it when building the
        // properties map (keeps the param() call sites 1:1 with the Dart maps).
        obj["__name"] = JsonPrimitive(name)
        if (type != null) obj["type"] = JsonPrimitive(type)
        if (enumValues != null) obj["enum"] = JsonArray(enumValues.map { JsonPrimitive(it) })
        if (description != null) obj["description"] = JsonPrimitive(description)
        if (items != null) obj["items"] = items
        return JsonObject(obj)
    }

    private fun definition(
        name: String,
        description: String,
        properties: List<JsonObject>,
        required: List<String>?,
    ): JsonObject {
        val parameters = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        parameters["type"] = JsonPrimitive("object")
        parameters["properties"] = JsonObject(
            properties.associate { schema ->
                val name = (schema["__name"] as JsonPrimitive).content
                name to JsonObject(schema.toMap() - "__name")
            },
        )
        if (required != null) {
            parameters["required"] = JsonArray(required.map { JsonPrimitive(it) })
        }
        return JsonObject(
            mapOf(
                "type" to JsonPrimitive("function"),
                "function" to JsonObject(
                    mapOf(
                        "name" to JsonPrimitive(name),
                        "description" to JsonPrimitive(description),
                        "parameters" to JsonObject(parameters),
                    ),
                ),
            ),
        )
    }
}

/** Resolves `auto` → zh when the interface is Chinese, else en (settings_provider L4216). */
fun resolvedMemoryPromptLang(stored: String): MemoryPromptLang = when (stored) {
    "zh" -> MemoryPromptLang.zh
    "en" -> MemoryPromptLang.en
    else -> if (java.util.Locale.getDefault().language == "zh") MemoryPromptLang.zh else MemoryPromptLang.en
}
