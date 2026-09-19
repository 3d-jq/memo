package com.psyche.memo.provider

import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.llm.client.LlmToolSpec
import com.psyche.memo.ui.MemoryEntry
import com.psyche.memo.ui.MemoryPromptLang
import com.psyche.memo.ui.MemoryProviderV2
import com.psyche.memo.ui.MemoryScope
import com.psyche.memo.ui.MemorySource
import com.psyche.memo.ui.MemoryStatus
import com.psyche.memo.ui.MemoryType
import com.psyche.memo.ui.UserProfileRepository
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Port of core/services/memory/memory_tools.dart — the v2 memory tool family
 * (memory_read / memory_update / memory_search_profile / memory_edit /
 * memory_delete / update_user_profile / chat_search). Definitions are
 * localised exactly like upstream; handlers mirror the Dart gates and error
 * payloads.
 *
 * chat_search is gated on `assistant.allowPastConversationRecall` alone
 * (upstream: the recall and memory gates are independent) and searches past
 * conversations through [com.psyche.memo.data.db.MessageDao.searchMessagesForAssistant].
 *
 * Smart Add 已接：`memory_update` 走 [MemorySmartAdd] 判 NEW/MERGE/UPDATE/SKIP
 * （未配记忆模型时退化成上游的精确重复 SKIP/NEW 路径）。
 */
object MemoryTools {

    const val MEMORY_READ = "memory_read"
    const val MEMORY_UPDATE = "memory_update"
    const val MEMORY_SEARCH_PROFILE = "memory_search_profile"
    const val MEMORY_EDIT = "memory_edit"
    const val MEMORY_DELETE = "memory_delete"
    const val UPDATE_USER_PROFILE = "update_user_profile"
    const val CHAT_SEARCH = "chat_search"

    val ALL_TOOL_NAMES = setOf(
        MEMORY_READ, MEMORY_UPDATE, MEMORY_SEARCH_PROFILE,
        MEMORY_EDIT, MEMORY_DELETE, UPDATE_USER_PROFILE, CHAT_SEARCH,
    )

    val ENABLE_MEMORY_TOOL_NAMES = setOf(
        MEMORY_READ, MEMORY_UPDATE, MEMORY_SEARCH_PROFILE,
        MEMORY_EDIT, MEMORY_DELETE, UPDATE_USER_PROFILE,
    )

    /** Tools that persist something beyond the current conversation. */
    val WRITE_TOOL_NAMES = setOf(
        MEMORY_UPDATE, MEMORY_EDIT, MEMORY_DELETE, UPDATE_USER_PROFILE,
    )

    val LEGACY_TOOL_NAMES = listOf("create_memory", "edit_memory", "delete_memory")

    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    // ---------------------------------------------------------------- definitions

    /** buildDefinitions: gated by enableMemory / allowPastConversationRecall. */
    fun buildDefinitions(
        assistant: Assistant,
        lang: MemoryPromptLang,
        allowMemoryWrites: Boolean = true,
    ): List<LlmToolSpec> {
        val out = mutableListOf<LlmToolSpec>()
        if (assistant.enableMemory) {
            out.add(spec(MEMORY_READ, defMemoryRead(lang)))
            out.add(spec(MEMORY_SEARCH_PROFILE, defMemorySearchProfile(lang)))
            if (allowMemoryWrites) {
                out.add(spec(MEMORY_UPDATE, defMemoryUpdate(lang, assistant.memoryWriteScope)))
                out.add(spec(MEMORY_EDIT, defMemoryEdit(lang)))
                out.add(spec(MEMORY_DELETE, defMemoryDelete(lang)))
                out.add(spec(UPDATE_USER_PROFILE, defUpdateUserProfile(lang)))
            }
        }
        if (assistant.allowPastConversationRecall) {
            out.add(spec(CHAT_SEARCH, defChatSearch(lang)))
        }
        return out
    }

    private fun spec(name: String, definition: JsonObject): LlmToolSpec {
        val fn = definition["function"] as JsonObject
        return LlmToolSpec(
            name = name,
            description = (fn["description"] as JsonPrimitive).content,
            inputSchemaJson = (fn["parameters"] as JsonObject).toString(),
        )
    }

    private fun defMemoryRead(lang: MemoryPromptLang): JsonObject {
        val zh = lang == MemoryPromptLang.zh
        return buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", MEMORY_READ)
                put(
                    "description",
                    if (zh) {
                        "读取用户的长期记忆。type 可选：identity（姓名、身边的人、职业等身份信息）、workflow（做事方式、工具偏好、调试习惯）、voice（行文风格、句式节奏、用词习惯）、instruction（用户对你的明确要求）。不传 type 则返回全部类型。对话中已经提供了记忆摘要，只有在摘要标了 mode=\"summary\" 被截断、或需要拿到条目 id 时才需要调用。"
                    } else {
                        "Read the user's long-term memory. Optional type: identity (name, people around them, occupation, etc.), workflow (ways of working, tool preferences, debugging habits), voice (writing style, rhythm, word choice), instruction (explicit requests to you). Omit type to return all types. A memory summary is already in the conversation; call this only when a block is marked mode=\"summary\" (truncated) or you need entry ids."
                    },
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("type", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                listOf("identity", "workflow", "voice", "instruction").forEach { add(JsonPrimitive(it)) }
                            })
                            put("description", if (zh) "只返回该类型的记忆。省略则返回全部类型。" else "Return only memories of this type. Omit to return all types.")
                        })
                        put("include_archived", buildJsonObject {
                            put("type", "boolean")
                            put("description", if (zh) "是否包含已归档的记忆。默认 false。" else "Whether to include archived memories. Default false.")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", 100)
                            put("description", if (zh) "最多返回多少条，默认 50。" else "Maximum number of entries to return. Default 50.")
                        })
                    })
                    put("required", JsonArray(emptyList()))
                })
            })
        }
    }

    private fun defMemoryUpdate(lang: MemoryPromptLang, writeScope: String): JsonObject {
        val zh = lang == MemoryPromptLang.zh
        val toolScoped = writeScope == "toolDefaultGlobal" || writeScope == "toolDefaultAssistant"
        return buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", MEMORY_UPDATE)
                put(
                    "description",
                    if (zh) {
                        "写入一条用户长期记忆。系统会自动与已有记忆去重合并，不需要先读取再全文替换。只写下次新开对话时仍然成立的稳定信息；本次对话内的临时上下文不要写。"
                    } else {
                        "Write one long-term user memory. The system deduplicates and merges with existing memories automatically; you do not need to read then replace. Only write stable facts that will still hold in a future conversation; do not write ephemeral context from this chat."
                    },
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("type", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                listOf("identity", "workflow", "voice", "instruction").forEach { add(JsonPrimitive(it)) }
                            })
                            put("description", if (zh) "identity 身份信息；workflow 做事方式与工具偏好；voice 表达风格；instruction 用户对你的明确要求。" else "identity: identity facts; workflow: ways of working and tool preferences; voice: expression style; instruction: explicit requests to you.")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                if (zh) {
                                    "一条完整、自包含的第三人称陈述句，例如「用户偏好直接、可落地的中文说明」。不要使用「这个」「刚才」等指回本次对话的词。"
                                } else {
                                    "One complete, self-contained third-person statement, e.g. \"The user prefers direct, actionable explanations in Chinese.\" Avoid deictic words that refer back to this conversation."
                                },
                            )
                        })
                        if (toolScoped) {
                            put("scope", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray {
                                    listOf("global", "assistant").forEach { add(JsonPrimitive(it)) }
                                })
                                put("description", if (zh) "global 对所有助手可见；assistant 只对当前助手可见。省略时按用户设置的默认值。" else "global is visible to all assistants; assistant is visible only to the current assistant. When omitted, uses the user's configured default.")
                            })
                        }
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("type"))
                        add(JsonPrimitive("content"))
                    })
                })
            })
        }
    }

    private fun defMemorySearchProfile(lang: MemoryPromptLang): JsonObject {
        val zh = lang == MemoryPromptLang.zh
        return buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", MEMORY_SEARCH_PROFILE)
                put(
                    "description",
                    if (zh) {
                        "搜索用户的长期记忆。当对话中提供的记忆摘要不够详细、被截断（标了 mode=\"summary\"），或需要查找某个特定信息时使用。按关键词匹配，多个关键词之间是「且」关系。"
                    } else {
                        "Search the user's long-term memory. Use when the in-conversation memory summary is incomplete, truncated (mode=\"summary\"), or you need a specific fact. Keyword match; multiple keywords are ANDed."
                    },
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", if (zh) "关键词，多个用空格分隔。中文可以直接写连续短语。" else "Keywords separated by spaces. Continuous Chinese phrases can be used as-is.")
                        })
                        put("type", buildJsonObject {
                            put("type", "string")
                            put("enum", buildJsonArray {
                                listOf("identity", "workflow", "voice", "instruction").forEach { add(JsonPrimitive(it)) }
                            })
                            put("description", if (zh) "只在该类型内搜索。省略则搜索全部类型。" else "Search only within this type. Omit to search all types.")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", 20)
                            put("description", if (zh) "最多返回多少条，默认 10。" else "Maximum number of entries to return. Default 10.")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("query")) })
                })
            })
        }
    }

    private fun defMemoryEdit(lang: MemoryPromptLang): JsonObject {
        val zh = lang == MemoryPromptLang.zh
        return buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", MEMORY_EDIT)
                put(
                    "description",
                    if (zh) {
                        "修改一条已有记忆的内容。需要先用 memory_read 或 memory_search_profile 拿到条目 id（形如 mem_xxxxxxxx）。只在记忆内容确实过时或有错时使用；补充新信息请用 memory_update。"
                    } else {
                        "Edit the content of an existing memory. First obtain the entry id (e.g. mem_xxxxxxxx) via memory_read or memory_search_profile. Use only when content is outdated or wrong; for new information use memory_update."
                    },
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "string")
                            put("description", if (zh) "条目 id，形如 mem_a1b2c3d4。" else "Entry id, e.g. mem_a1b2c3d4.")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", if (zh) "修改后的完整内容，会整体替换原内容。" else "The full replacement content.")
                        })
                    })
                    put("required", buildJsonArray {
                        add(JsonPrimitive("id"))
                        add(JsonPrimitive("content"))
                    })
                })
            })
        }
    }

    private fun defMemoryDelete(lang: MemoryPromptLang): JsonObject {
        val zh = lang == MemoryPromptLang.zh
        return buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", MEMORY_DELETE)
                put(
                    "description",
                    if (zh) {
                        "归档一条记忆（软删除）。归档后不再出现在记忆摘要和搜索结果里，用户仍可以在设置中看到并恢复。需要先用 memory_read 或 memory_search_profile 拿到条目 id。只在用户明确表示某条记忆不再成立时使用。"
                    } else {
                        "Archive a memory (soft delete). Archived entries disappear from the memory summary and search results, but the user can still see and restore them in settings. First obtain the entry id via memory_read or memory_search_profile. Use only when the user clearly says a memory no longer holds."
                    },
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("id", buildJsonObject {
                            put("type", "string")
                            put("description", if (zh) "条目 id，形如 mem_a1b2c3d4。" else "Entry id, e.g. mem_a1b2c3d4.")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("id")) })
                })
            })
        }
    }

    private fun defUpdateUserProfile(lang: MemoryPromptLang): JsonObject {
        val zh = lang == MemoryPromptLang.zh
        return buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", UPDATE_USER_PROFILE)
                put(
                    "description",
                    if (zh) "更新用户画像字段。这些是最稳定的身份信息。不确定的时候不要写。" else "Update user profile fields. These are the most stable identity facts. Do not write when uncertain.",
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("fields", buildJsonObject {
                            put("type", "array")
                            put("description", if (zh) "要更新的字段列表。" else "List of fields to update.")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {
                                    put("key", buildJsonObject {
                                        put("type", "string")
                                        put(
                                            "description",
                                            if (zh) {
                                                "可用字段：preferred_name（用户希望你怎么称呼他）、gender、pronouns、preferred_language、timezone、occupation、location。其他稳定字段用 custom.<名称>，名称只能是字母、数字、下划线或连字符。"
                                            } else {
                                                "Allowed keys: preferred_name (how the user wants to be addressed), gender, pronouns, preferred_language, timezone, occupation, location. Other stable fields use custom.<name> where name is letters, digits, underscore, or hyphen only."
                                            },
                                        )
                                    })
                                    put("value", buildJsonObject {
                                        put("type", "string")
                                        put("description", if (zh) "字段取值。传空字符串表示清除该字段。" else "Field value. An empty string clears the field.")
                                    })
                                })
                                put("required", buildJsonArray {
                                    add(JsonPrimitive("key"))
                                    add(JsonPrimitive("value"))
                                })
                            })
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("fields")) })
                })
            })
        }
    }

    private fun defChatSearch(lang: MemoryPromptLang): JsonObject {
        val zh = lang == MemoryPromptLang.zh
        return buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", CHAT_SEARCH)
                put(
                    "description",
                    if (zh) {
                        "在历史对话中按关键词搜索消息内容（仅当前助手的会话，以及没有归属助手的旧会话）。需要回忆之前聊过什么，或者用户提到「上次」「之前说的」「我们讨论过」时，优先使用这个工具。默认不搜索当前对话，因为当前对话的内容已经在上下文里。"
                    } else {
                        "Search message content in this assistant's past conversations (and unowned older chats) by keywords. Prefer this when recalling prior discussion, or when the user mentions \"last time\", \"earlier\", or \"we discussed\". By default the current conversation is excluded because it is already in context."
                    },
                )
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("query", buildJsonObject {
                            put("type", "string")
                            put("description", if (zh) "关键词，多个用空格分隔。" else "Keywords separated by spaces.")
                        })
                        put("limit", buildJsonObject {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", 20)
                            put("description", if (zh) "最多返回多少条，默认 10。" else "Maximum number of results. Default 10.")
                        })
                        put("conversation_id", buildJsonObject {
                            put("type", "string")
                            put(
                                "description",
                                if (zh) {
                                    "只在指定会话内搜索。省略则搜索除当前会话外、当前助手可见的会话。"
                                } else {
                                    "Search only within this conversation. Omit to search this assistant's visible conversations except the current one."
                                },
                            )
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("query")) })
                })
            })
        }
    }

    // ------------------------------------------------------------------ dispatch

    /**
     * Handles a memory tool call. Returns null when the tool is not applicable
     * (gates off / unknown name) so the caller can fall through.
     */
    suspend fun handle(
        container: AppContainerImpl,
        assistant: Assistant?,
        conversationId: String?,
        isTemporary: Boolean,
        name: String,
        args: JsonObject,
    ): String? {
        if (assistant == null) return null
        // Temporary chats are discarded on exit: reads stay available, but a
        // recorded trace (title, args, result) would outlive the conversation.
        val trace = if (isTemporary) null else beginToolTrace(container, assistant, conversationId, name, args)

        if (name == CHAT_SEARCH) {
            if (!assistant.allowPastConversationRecall) return null
            return try {
                chatSearch(container, assistant, conversationId, args).also { finishToolTrace(trace, it) }
            } catch (e: Exception) {
                toolError(
                    error = "memory_execution_error",
                    message = e.toString(),
                    tool = name,
                    instruction = "The memory tool failed. Retry only after correcting the parameters, or inform the user about the issue.",
                ).also { finishToolTrace(trace, it, "memory_execution_error") }
            }
        }
        if (name !in ENABLE_MEMORY_TOOL_NAMES) return null
        if (!assistant.enableMemory) return null

        // Temporary chats may read but never write.
        if (name in WRITE_TOOL_NAMES && isTemporary) {
            return toolError(
                error = "temporary_conversation",
                message = "Memory cannot be written from a temporary conversation.",
                tool = name,
                instruction = "Do not retry. Tell the user that memory is disabled in temporary chats, and continue without saving.",
            )
        }

        val provider = container.memoryProviderV2
        // The tool runs against the container's provider instance while screens
        // write through their own; refresh first so a read or a duplicate check
        // never works off a snapshot that missed another instance's writes.
        provider.ensureLoaded()
        return try {
            val result = when (name) {
                MEMORY_READ -> memoryRead(provider, assistant, args)
                MEMORY_UPDATE -> memoryUpdate(container, provider, assistant, args)
                MEMORY_SEARCH_PROFILE -> memorySearchProfile(provider, assistant, args)
                MEMORY_EDIT -> memoryEdit(provider, assistant, args)
                MEMORY_DELETE -> memoryDelete(provider, assistant, args)
                UPDATE_USER_PROFILE -> updateUserProfile(container, args)
                else -> null
            }
            finishToolTrace(trace, result)
            result
        } catch (e: Exception) {
            val error = toolError(
                error = "memory_execution_error",
                message = e.toString(),
                tool = name,
                instruction = "The memory tool failed. Retry only after correcting the parameters, or inform the user about the issue.",
            )
            finishToolTrace(trace, error, "memory_execution_error")
            error
        }
    }

    /** `_beginToolTrace` — opens the one-step trace for a tool call. */
    private fun beginToolTrace(
        container: AppContainerImpl,
        assistant: Assistant,
        conversationId: String?,
        name: String,
        args: JsonObject,
    ): com.psyche.memo.provider.MemoryTraceHandle? = runCatching {
        val handle = container.memoryTraceRecorder.begin(
            trigger = com.psyche.memo.provider.MemoryTraceTrigger.TOOL_CALL,
            scope = com.psyche.memo.provider.memoryTraceScopeOf(assistant.memoryWriteScope),
            conversationId = conversationId,
            conversationTitle = conversationId?.let { container.conversationDao.get(it)?.title },
            assistantId = assistant.id,
            assistantName = assistant.name,
        )
        val step = handle?.beginStep(
            kind = if (name == CHAT_SEARCH) {
                com.psyche.memo.provider.MemoryTraceStepKind.CHAT_SEARCH
            } else {
                com.psyche.memo.provider.MemoryTraceStepKind.MEMORY_TOOL
            },
            label = name,
        )
        step?.appendPrompt(args.toString())
        handle
    }.getOrNull()

    /** `_finishToolTrace` — records the result and publishes the trace. */
    private fun finishToolTrace(
        handle: com.psyche.memo.provider.MemoryTraceHandle?,
        result: String?,
        error: String? = null,
    ) {
        if (handle == null) return
        val step = handle.trace.steps.lastOrNull()
        step?.appendResponse(result ?: "(no result)")
        // A tool error payload is a failure even though the call returned.
        val payloadError = error ?: result
            ?.takeIf { it.contains("\"error\":") }
            ?.let { step?.label ?: "memory_tool_error" }
        step?.finish(
            if (payloadError == null) {
                com.psyche.memo.provider.MemoryTraceStepStatus.SUCCESS
            } else {
                com.psyche.memo.provider.MemoryTraceStepStatus.FAILED
            },
            payloadError,
        )
        handle.commit(error = payloadError)
    }

    // ------------------------------------------------------------------ handlers

    private fun memoryRead(provider: MemoryProviderV2, assistant: Assistant, args: JsonObject): String {
        val typeRaw = args.string("type")
        val type = parseType(typeRaw)
        if (typeRaw != null && type == null) {
            return toolError(
                error = "invalid_memory_type",
                message = "type must be one of: identity, workflow, voice, instruction.",
                tool = MEMORY_READ,
            )
        }
        val includeArchived = args.bool("include_archived") ?: false
        val limit = (args.int("limit") ?: 50).coerceIn(1, 100)

        val all = provider.visibleFor(assistant.id, includeArchived)
            .filter { type == null || it.type == type }
        val returned = if (all.size <= limit) all else all.subList(0, limit)
        return buildJsonObject {
            put("total", all.size)
            put("returned", returned.size)
            put("entries", buildJsonArray {
                returned.forEach { add(entrySummary(it, includeStatus = true)) }
            })
        }.toString()
    }

    /**
     * memory_update (memory_tools.dart `_handleMemoryUpdate`): Smart Add judges
     * the new content against the closest existing entries — NEW, MERGE into a
     * candidate, CONFLICT (archive the old entry, keep the new one, link them)
     * or SKIP. Without a memory model configured the judge degrades to the
     * exact-duplicate check.
     */
    private suspend fun memoryUpdate(
        container: AppContainerImpl,
        provider: MemoryProviderV2,
        assistant: Assistant,
        args: JsonObject,
    ): String {
        val typeRaw = args.string("type")
        val type = parseType(typeRaw)
        if (type == null) {
            return toolError(
                error = "invalid_memory_type",
                message = "type is required and must be one of: identity, workflow, voice, instruction.",
                tool = MEMORY_UPDATE,
            )
        }
        val content = args.string("content") ?: ""
        if (content.trim().isEmpty()) {
            return toolError(
                error = "invalid_memory_content",
                message = "Memory content must not be empty.",
                tool = MEMORY_UPDATE,
            )
        }

        val scope = resolveWriteScope(assistant.memoryWriteScope, args.string("scope"))
        val assistantId = if (scope == MemoryScope.assistant) assistant.id else null

        val settings = com.psyche.memo.ui.MemorySettingsState(container)
        val lang = settings.resolvedPromptLang()
        val zh = lang == MemoryPromptLang.zh
        val adder = MemorySmartAdd(MemoryProviderSmartAddRepository(provider))
        val result = adder.addOne(
            item = SmartAddItem(
                type = type,
                content = content,
                scope = scope,
                assistantId = assistantId,
            ),
            visibilityAssistantId = assistant.id,
            source = MemorySource.tool,
            lang = lang,
            llmCall = MemoryLlm.callerOrNull(container),
            overrideZh = settings.prompt(com.psyche.memo.ui.MemoryPromptKind.SMART_ADD, true),
            overrideEn = settings.prompt(com.psyche.memo.ui.MemoryPromptKind.SMART_ADD, false),
        )
        return result.toToolJson().toString()
    }

    private fun memorySearchProfile(
        provider: MemoryProviderV2,
        assistant: Assistant,
        args: JsonObject,
    ): String {
        val query = args.string("query") ?: ""
        if (query.trim().isEmpty()) {
            return toolError(error = "invalid_query", message = "query must not be empty.", tool = MEMORY_SEARCH_PROFILE)
        }
        val typeRaw = args.string("type")
        val type = parseType(typeRaw)
        if (typeRaw != null && type == null) {
            return toolError(
                error = "invalid_memory_type",
                message = "type must be one of: identity, workflow, voice, instruction.",
                tool = MEMORY_SEARCH_PROFILE,
            )
        }
        val limit = (args.int("limit") ?: 10).coerceIn(1, 20)
        val tokens = searchTokens(query)
        if (tokens.isEmpty()) {
            return buildJsonObject {
                put("query", query)
                put("matched", JsonArray(emptyList()))
                put("related", JsonArray(emptyList()))
            }.toString()
        }
        val visible = provider.visibleFor(assistant.id)
            .filter { it.status == MemoryStatus.active }
            .filter { type == null || it.type == type }
            .filter { entry -> tokens.all { entry.content.lowercase().contains(it.lowercase()) } }
            .sortedWith(compareByDescending<MemoryEntry> { it.updatedAt }.thenBy { it.id })
            .take(limit)
        return buildJsonObject {
            put("query", query)
            put("matched", buildJsonArray { visible.forEach { add(entrySummary(it, includeStatus = false)) } })
            // relatedIds 邻接展开依赖 Smart Add 写入的关联，未移植 → 空数组。
            put("related", JsonArray(emptyList()))
        }.toString()
    }

    private fun memoryEdit(provider: MemoryProviderV2, assistant: Assistant, args: JsonObject): String {
        val id = (args.string("id") ?: "").trim()
        val content = args.string("content") ?: ""
        if (id.isEmpty()) {
            return toolError(error = "invalid_memory_id", message = "id is required (e.g. mem_a1b2c3d4).", tool = MEMORY_EDIT)
        }
        if (content.trim().isEmpty()) {
            return toolError(error = "invalid_memory_content", message = "Memory content must not be empty.", tool = MEMORY_EDIT)
        }
        val entry = provider.visibleFor(assistant.id).firstOrNull { it.id == id }
        if (entry == null || entry.status != MemoryStatus.active) {
            return memoryNotFound(MEMORY_EDIT, id)
        }
        provider.updateContent(id, content)
        return buildJsonObject {
            put("action", "EDIT")
            put("id", id)
            put("content", content)
        }.toString()
    }

    private fun memoryDelete(provider: MemoryProviderV2, assistant: Assistant, args: JsonObject): String {
        val id = (args.string("id") ?: "").trim()
        if (id.isEmpty()) {
            return toolError(error = "invalid_memory_id", message = "id is required (e.g. mem_a1b2c3d4).", tool = MEMORY_DELETE)
        }
        val entry = provider.visibleFor(assistant.id).firstOrNull { it.id == id }
        if (entry == null) return memoryNotFound(MEMORY_DELETE, id)
        provider.archive(id)
        return buildJsonObject {
            put("action", "DELETE")
            put("id", id)
        }.toString()
    }

    private fun updateUserProfile(container: AppContainerImpl, args: JsonObject): String {
        val rawFields = args["fields"]
        if (rawFields !is JsonArray) {
            return toolError(
                error = "invalid_profile_fields",
                message = "fields must be an array of {key, value} objects.",
                tool = UPDATE_USER_PROFILE,
            )
        }
        val updated = mutableListOf<String>()
        val cleared = mutableListOf<String>()
        val rejected = mutableListOf<JsonObject>()
        for (item in rawFields) {
            val obj = item as? JsonObject
            if (obj == null) {
                rejected.add(buildJsonObject {
                    put("key", "")
                    put("reason", "invalid_item")
                })
                continue
            }
            val key = obj.string("key") ?: ""
            val value = obj.string("value") ?: ""
            if (!UserProfileRepository.isValidKey(key)) {
                rejected.add(buildJsonObject {
                    put("key", key)
                    put("reason", "unknown_key")
                })
                continue
            }
            if (value.trim().isEmpty()) {
                UserProfileRepository.remove(container, key)
                cleared.add(key)
            } else {
                UserProfileRepository.put(container, key, value)
                updated.add(key)
            }
        }
        return buildJsonObject {
            put("action", "PROFILE_UPDATE")
            put("updated", buildJsonArray { updated.forEach { add(JsonPrimitive(it)) } })
            put("cleared", buildJsonArray { cleared.forEach { add(JsonPrimitive(it)) } })
            put("rejected", JsonArray(rejected))
        }.toString()
    }

    private fun chatSearch(
        container: AppContainerImpl,
        assistant: Assistant,
        conversationId: String?,
        args: JsonObject,
    ): String {
        val query = args.string("query") ?: ""
        if (query.trim().isEmpty()) {
            return toolError(error = "invalid_query", message = "query must not be empty.", tool = CHAT_SEARCH)
        }
        val limit = (args.int("limit") ?: 10).coerceIn(1, 20)
        val filterConversationId = args.string("conversation_id")?.trim()
        val scoped = !filterConversationId.isNullOrEmpty()
        val tokens = query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) {
            return buildJsonObject {
                put("query", query)
                put("results", JsonArray(emptyList()))
            }.toString()
        }
        val hits = container.messageDao.searchMessagesForAssistant(
            tokens = tokens,
            assistantId = assistant.id,
            onlyConversationId = if (scoped) filterConversationId else null,
            excludeConversationId = if (scoped) null else conversationId,
            limit = limit * 8,
        )
        val results = mutableListOf<JsonObject>()
        for (hit in hits) {
            if (hit.role != "user" && hit.role != "assistant") continue
            val content = hit.content.trim()
            if (content.isEmpty()) continue
            val summary = hit.summary?.trim()?.takeIf { it.isNotEmpty() }
            results.add(buildJsonObject {
                put("conversationId", hit.conversationId)
                put("title", hit.conversationTitle)
                if (summary != null) put("summary", summary)
                put("role", hit.role)
                put("date", fmtDate(hit.timestamp * 1000))
                put("snippet", snippet(content, tokens))
            })
            if (results.size >= limit) break
        }
        return buildJsonObject {
            put("query", query)
            put("results", JsonArray(results))
        }.toString()
    }

    /** memory_tools._snippet: 40-char window around the first token hit. */
    private fun snippet(content: String, tokens: List<String>): String {
        val lower = content.lowercase()
        var idx = -1
        var hitLen = 0
        for (token in tokens) {
            val i = lower.indexOf(token)
            if (i >= 0) {
                idx = i
                hitLen = token.length
                break
            }
        }
        val radius = 40
        if (idx < 0) {
            val cut = if (content.length <= 80) content else content.substring(0, 80)
            return "……$cut……"
        }
        val start = (idx - radius).coerceIn(0, content.length)
        val end = (idx + hitLen + radius).coerceIn(0, content.length)
        var snip = content.substring(start, end)
        if (start > 0) snip = "……$snip"
        if (end < content.length) snip = "$snip……"
        return snip
    }

    // ------------------------------------------------------------------- helpers

    /** resolveWriteScope. */
    fun resolveWriteScope(policy: String?, scopeArg: String?): MemoryScope = when (policy) {
        "alwaysAssistant" -> MemoryScope.assistant
        "toolDefaultGlobal" -> if (scopeArg == "assistant") MemoryScope.assistant else MemoryScope.global
        "toolDefaultAssistant" -> if (scopeArg == "global") MemoryScope.global else MemoryScope.assistant
        else -> MemoryScope.global
    }

    /** searchTokens: lowercase split + SQL LIKE escaping. */
    fun searchTokens(query: String): List<String> =
        query.trim().lowercase().split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .map { it.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") }

    fun fmtDate(epochMicros: Long): String =
        Instant.ofEpochSecond(epochMicros / 1_000_000, (epochMicros % 1_000_000) * 1_000)
            .atZone(ZoneId.systemDefault())
            .toLocalDate()
            .format(dateFormatter)

    private fun entrySummary(entry: MemoryEntry, includeStatus: Boolean): JsonObject = buildJsonObject {
        put("id", entry.id)
        put("type", entry.type.wire)
        put("scope", entry.scope.wire)
        if (includeStatus) put("status", entry.status.wire)
        put("content", entry.content)
        put("updatedAt", fmtDate(entry.updatedAt))
    }

    private fun memoryNotFound(tool: String, id: String): String = toolError(
        error = "memory_not_found",
        message = "No active visible memory was found for id $id.",
        tool = tool,
        instruction = "Use memory_read or memory_search_profile to obtain a valid id, or call memory_update to write a new entry instead of editing a missing one.",
    )

    private fun toolError(error: String, message: String, tool: String, instruction: String? = null): String =
        buildJsonObject {
            put("type", "tool_error")
            put("error", error)
            put("message", message)
            put("tool", tool)
            if (instruction != null) put("instruction", instruction)
        }.toString()

    /** _parseMemoryType：非法值返回 null（MemoryType.from 会回落 identity，不可用）。 */
    private fun parseType(raw: String?): MemoryType? {
        val value = raw?.trim()?.lowercase() ?: return null
        return MemoryType.entries.firstOrNull { it.wire == value }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
}
