package com.psyche.memo.provider.search

import com.psyche.memo.data.model.SearchCommonOptions
import com.psyche.memo.data.model.SearchResultItem
import com.psyche.memo.data.model.SearchServiceOptions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * Port of lib/core/services/search/search_tool_service.dart — the `search_web`
 * tool definition, its citation system prompt, and the execution wrapper that
 * stamps short ids onto results.
 */
object SearchToolService {

    const val TOOL_NAME = "search_web"

    val TOOL_DESCRIPTION = """
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

Cite: append [cite:id] immediately after each statement a result supports, using that result's exact `id` field.""".trim()

    val SYSTEM_PROMPT = """
<citations>
When a statement in your answer is based on a search_web result, append a citation marker immediately after that statement: [cite:id], where id is the exact `id` field of the supporting result item.
- Example: "The event took place yesterday afternoon. [cite:a1b2c3]"
- Chain markers when several results support one statement: [cite:a1b2c3][cite:d4e5f6]
- Copy ids exactly as returned by the tool. Never invent, renumber, or reuse ids from other results.
- Place markers inline right after the supported statement (after its punctuation). Do not collect them at the end of the response, and do not add a "References" or "Sources" section — the app renders citations from the inline markers.
- Statements from your own knowledge take no marker.
</citations>
""".trim()

    /** Tool parameters schema (search_web → required query string). */
    fun parametersJson(): String = buildJsonObject {
        put("type", "object")
        put(
            "properties",
            buildJsonObject {
                put(
                    "query",
                    buildJsonObject {
                        put("type", "string")
                        put("description", "The search query to look up online")
                    },
                )
            },
        )
        put("required", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("query"))))
    }.toString()

    /**
     * Executes the query through [engine] and returns the tool payload
     * (`{answer?, items[]}` or `{"error": ...}`) as the model sees it.
     */
    suspend fun executeSearch(
        query: String,
        engine: SearchEngine,
        service: SearchServiceOptions?,
        common: SearchCommonOptions,
    ): String {
        if (service == null) {
            // 规范形状：模型看 `status` 就知道没跑成，并且拿到下一步该干什么。
            return com.psyche.memo.provider.tool.ToolResults.error(
                code = "search_unavailable",
                message = "No search services configured",
                tool = TOOL_NAME,
                instruction = "Ask the user to configure a search service in settings; answer " +
                    "from your own knowledge and say it may be out of date.",
            )
        }
        return try {
            val result = engine.search(query, service, common)
            val items = result.items.mapIndexed { index, item ->
                SearchResultItem(
                    title = item.title,
                    url = SearchParsers.normalizeUrl(item.url),
                    text = item.text,
                    id = UUID.randomUUID().toString().take(6),
                    index = index + 1,
                )
            }
            buildJsonObject {
                result.answer?.let { put("answer", it) }
                put("items", JsonArray(items.map { it.toJson() }))
            }.toString()
        } catch (e: Exception) {
            buildJsonObject { put("error", "Search failed: $e") }.toString()
        }
    }
}
