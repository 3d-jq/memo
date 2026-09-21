package com.psyche.memo.ui.chat

import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Citation-source extraction.
 *
 * Deliberate deviation from the original (2026-09-12, user decision): the
 * Flutter `_allSearchItems` filters on `toolName == 'search_web' ||
 * 'builtin_search'`, so a result from any other tool never entered the source
 * list and every `[cite:id]` the model wrote against it rendered as "?". The
 * filter is now **structural** — any tool whose content is JSON carrying an
 * `items` array counts.
 *
 * Note the limit this does *not* lift: a tool that answers with prose (the
 * device-observed MCP `aihot_get_latest`) still yields nothing, because there
 * is no structured id to match. Those citations are dropped by the renderer
 * instead of drawn as "?".
 */
class CitationSourcesTest {

    /** A tool result whose content is JSON text (`{"items": [...]}`). */
    private fun toolPart(name: String, contentJson: String): ToolCallPart = ToolCallPart.encode(
        id = "call-$name",
        name = name,
        arguments = JsonNull,
        content = JsonPrimitive(contentJson),
        server = true,
    )

    /** A tool result whose content is free prose. */
    private fun textPart(name: String, text: String): ToolCallPart = ToolCallPart.encode(
        id = "call-$name",
        name = name,
        arguments = JsonNull,
        content = JsonPrimitive(text),
        server = true,
    )

    private fun items(count: Int, prefix: String = "id"): String = buildString {
        append("""{"items":[""")
        repeat(count) { index ->
            if (index > 0) append(',')
            append("""{"id":"$prefix$index","url":"https://example.com/$index","title":"t$index","index":${index + 1}}""")
        }
        append("]}")
    }

    @Test
    fun `search_web items are collected`() {
        val items = extractCitationItems(listOf(toolPart("search_web", items(3))))
        assertEquals(3, items.size)
        assertEquals(listOf("id0", "id1", "id2"), items.map { it.id })
        assertEquals(listOf(1, 2, 3), items.map { it.index })
    }

    @Test
    fun `a non-search tool returning items is collected too`() {
        // The whole point of dropping the whitelist: a search-ish MCP tool
        // with the same payload shape now contributes sources.
        val items = extractCitationItems(listOf(toolPart("my_mcp_search", items(2, "mcp"))))
        assertEquals(listOf("mcp0", "mcp1"), items.map { it.id })
    }

    @Test
    fun `every tool result is considered regardless of name`() {
        val parts = listOf(
            toolPart("search_web", items(1, "web")),
            toolPart("custom_relay", items(1, "relay")),
        )
        // Scanned back to front, so the later part's items come first.
        assertEquals(
            listOf("relay0", "web0"),
            extractCitationItems(parts).map { it.id },
        )
    }

    @Test
    fun `prose results contribute nothing`() {
        // The aihot_get_latest shape: nothing to index, so nothing is added —
        // this is why the renderer must drop unresolvable markers rather than
        // showing "?".
        val parts = listOf(
            textPart("aihot_get_latest", "AIHOT 最新资讯\nAIHOT：https://aihot.news/items/cmtxabc123"),
        )
        assertTrue(extractCitationItems(parts).isEmpty())
    }

    @Test
    fun `json without an items array contributes nothing`() {
        val parts = listOf(
            toolPart("get_time_info", """{"year":2026,"month":9}"""),
            toolPart("memory_delete", """{"action":"delete","id":"x"}"""),
        )
        assertTrue(extractCitationItems(parts).isEmpty())
    }

    @Test
    fun `non-tool parts are ignored`() {
        val parts: List<MessagePart> = listOf(
            TextPart("正文 [cite:abc]"),
            toolPart("search_web", items(1)),
        )
        assertEquals(1, extractCitationItems(parts).size)
    }

    @Test
    fun `items are deduplicated by id and url`() {
        val parts = listOf(
            toolPart("search_web", """{"items":[{"id":"same","url":"https://a"},{"url":"https://b"}]}"""),
            toolPart("other", """{"items":[{"id":"same","url":"https://c"},{"url":"https://b"}]}"""),
        )
        // Back-to-front scan: the later tool's entries win, and the earlier
        // duplicates are skipped. A url-only entry keeps a null `id` — the
        // dedupe key is `id ?? url`, which is not the same as the stored id.
        val items = extractCitationItems(parts)
        assertEquals(listOf("same", null), items.map { it.id })
        assertEquals(listOf("https://c", "https://b"), items.map { it.url })
    }

    @Test
    fun `a missing index falls back to the collection position`() {
        val parts = listOf(
            toolPart("search_web", """{"items":[{"id":"a"},{"id":"b"},{"id":"c","index":9}]}"""),
        )
        assertEquals(listOf(1, 2, 9), extractCitationItems(parts).map { it.index })
    }

    @Test
    fun `no tool parts yields no sources`() {
        assertTrue(extractCitationItems(emptyList()).isEmpty())
        assertTrue(extractCitationItems(listOf(TextPart("hi"))).isEmpty())
        // 没用过工具的普通聊天：正文里的链接就是普通链接，不当来源。
        assertTrue(
            extractCitationItems(listOf(TextPart("看 [这里](https://example.com/x)"))).isEmpty(),
        )
    }

    // ---- 正文里的 Markdown 链接也算来源（2026-09-12 用户实测 DeepSeek） ----

    @Test
    fun `markdown links in the assistant text become sources`() {
        // DeepSeek 实测写法：来源是普通链接、标签是"链接"两个字；工具本身回散文
        // （aihot_get_latest 形状），没有任何 items[]。
        val parts = listOf(
            textPart("aihot_get_latest", "AIHOT 最新资讯\nAIHOT：https://aihot.news/items/cmtxabc123"),
            TextPart(
                "预算面板 64.7%。[链接](https://aihot.news/items/cmtx301no054wroedi0krzdye) - 下一条。" +
                    "[链接](https://www.rubyhack.ai/)",
            ),
        )
        val items = extractCitationItems(parts)
        assertEquals(
            listOf("https://aihot.news/items/cmtx301no054wroedi0krzdye", "https://www.rubyhack.ai/"),
            items.map { it.url },
        )
        assertEquals(listOf(1, 2), items.map { it.index })
        // 占位标签不当标题（留空 → 来源卡回落到域名）。
        assertEquals(listOf("", ""), items.map { it.title })
    }

    @Test
    fun `a meaningful link label is kept as the title`() {
        val items = extractCitationItems(
            listOf(
                textPart("search_web", "prose without items"),
                TextPart("见 [OpenAI 存储平台](https://openai.com/index/scaling-storage)"),
            ),
        )
        assertEquals("OpenAI 存储平台", items.single().title)
    }

    @Test
    fun `text links are numbered after tool sources and deduped by url`() {
        val parts = listOf(
            // `/1` 已经是工具来源（items(2) → /0、/1），正文重复引用不再新增。
            TextPart("引用 [链接](https://example.com/9) 与 [链接](https://example.com/1)"),
            toolPart("search_web", items(2)),
        )
        val items = extractCitationItems(parts)
        assertEquals(
            listOf("https://example.com/0", "https://example.com/1", "https://example.com/9"),
            items.map { it.url },
        )
        assertEquals(listOf(1, 2, 3), items.map { it.index })
    }

    @Test
    fun `non-http links and repeated urls are ignored`() {
        val parts = listOf(
            toolPart("search_web", items(1)),
            TextPart("[文件](file:///tmp/a.md) [两次](https://a.example/x) [再来](https://a.example/x)"),
        )
        assertEquals(
            listOf("https://example.com/0", "https://a.example/x"),
            extractCitationItems(parts).map { it.url },
        )
    }
}
