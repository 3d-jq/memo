package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import com.psyche.memo.ui.overlaySurfaceColor
import com.psyche.memo.ui.rememberMemoSheetState
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.psyche.memo.ui.MemoSheetHandle
import com.psyche.memo.ui.R as UiR
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * 联网搜索引用 — 1:1 port of citation_sources_sheet.dart：来源卡
 * （favicon + 来源行 + 序号徽标 + 标题/引文/标签）、来源摘要卡、
 * 全量来源 bottom sheet。点击外链用系统浏览器打开。
 */

data class CitationSourceTag(val title: String, val description: String?)

data class CitationSourceItem(
    val index: Int?,
    val id: String? = null,
    val title: String,
    val url: String,
    val text: String = "",
    val sourceName: String? = null,
    val webSiteSource: String? = null,
    val publishedText: String? = null,
    val tags: List<CitationSourceTag> = emptyList(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** citation_sources_sheet.dart CitationSourceItem.fromMap。 */
        fun fromMap(map: JsonObject, fallbackIndex: Int): CitationSourceItem = CitationSourceItem(
            index = map.int("index") ?: fallbackIndex,
            id = map.str("id"),
            title = map.str("title").orEmpty(),
            url = map.str("url").orEmpty(),
            text = (map.str("text") ?: map.str("quote") ?: map.str("snippet")).orEmpty(),
            sourceName = map.str("sourceName") ?: map.str("source_name") ?: map.str("web_site_name"),
            webSiteSource = map.str("webSiteSource"),
            publishedText = map.str("publish_time") ?: map.str("publishedText"),
            tags = tagsFrom(map["tags"] as? JsonArray),
        )

        private fun tagsFrom(arr: JsonArray?): List<CitationSourceTag> {
            if (arr == null) return emptyList()
            val out = ArrayList<CitationSourceTag>()
            for (tag in arr) {
                val obj = tag as? JsonObject ?: continue
                val title = obj.str("title")?.trim().orEmpty()
                if (title.isEmpty()) continue
                out.add(CitationSourceTag(title = title, description = obj.str("desc")))
            }
            return out
        }
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content

private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

/** chat_message_widget.dart _tryNormalizeExternalUri + _domain 等价。 */
fun citationDomain(url: String): String {
    val uri = normalizeExternalUri(url) ?: return ""
    return uri.host.orEmpty()
}

/** 归一化 http/https 外链；无法解析返回 null。 */
fun normalizeExternalUri(raw: String): Uri? {
    var value = raw.trim()
    if (value.isEmpty()) return null
    if (value.startsWith("//")) {
        value = "https:$value"
    } else if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(value)) {
        value = "https://$value"
    }
    return try {
        val uri = Uri.parse(value)
        if ((uri.scheme == "http" || uri.scheme == "https") && uri.host.isNullOrBlank()) null else uri
    } catch (e: Exception) {
        null
    }
}

private val citationJson = Json { ignoreUnknownKeys = true }

/** 正文里的 Markdown 链接 `[label](https://…)`。 */
private val markdownLinkRegex = Regex("""\[([^\]\n]*)\]\((https?://[^\s)]+)\)""")

/**
 * 标签只是"链接/link/here"这类占位词的，进来源列表时不要当标题（留空 → 卡片回落到
 * 域名/URL），否则来源卡会显示十行"链接"。
 */
private val genericLinkLabels = setOf(
    "链接", "鏈接", "连接", "link", "links", "url", "here", "点击", "点击这里", "来源", "source", "详情",
)

/**
 * chat_message_widget.dart `_allSearchItems` — 从消息的工具结果里提取引用来源
 * （从后往前扫，key = `id ?? url` 去重，"latest wins"）。
 *
 * **有意偏离原版（2026-09-12 用户决定）**：原版只认 `search_web` /
 * `builtin_search` 两个工具名，导致任何别的工具（尤其 MCP 搜索类）即使回了
 * 同一套 `items[]` 也永远进不了引用列表、模型写下的 `[cite:id]` 一律解不出
 * 而显示 `?`。这里改成**按结构判定**：任何工具只要 content 是 JSON 且带
 * `items` 数组，就当作引用来源。判定条件收紧到"必须真的是 items[]"，所以
 * `get_time_info` / `memory_*` 这类无关 JSON 依然被排除。
 *
 * **第二批（2026-09-12 用户实测 DeepSeek）**：模型不一定照提示词写 `[cite:id]`，
 * 实测它把来源写成普通 Markdown 链接 `[链接](https://aihot.news/items/…)`。这种
 * 情况下既没有 items[] 也没有 id ⇒ 既没有胶囊、也没有来源列表，正文里就只剩
 * "链接"两个字（用户原话：「不是显示我弄到的胶囊加数字呀，是直接显示链接这两个
 * 字」）。所以**正文里的 http(s) Markdown 链接也收作来源**（按 URL 去重、排在
 * 工具来源之后），渲染层再把命中来源的链接画成序号胶囊。
 */
fun extractCitationItems(parts: List<com.psyche.memo.data.model.MessagePart>): List<CitationSourceItem> {
    val out = ArrayList<CitationSourceItem>()
    val seen = HashSet<String>()
    for (part in parts.asReversed()) {
        if (part !is com.psyche.memo.data.model.ToolCallPart) continue
        val tool = ToolUiPart.fromPayload(part.payloadJson) ?: continue
        val content = tool.content ?: continue
        val obj = try { citationJson.parseToJsonElement(content).jsonObject } catch (e: Exception) { continue }
        val arr = obj["items"] as? JsonArray ?: continue
        for (item in arr) {
            val map = item as? JsonObject ?: continue
            val key = map.str("id") ?: map.str("url") ?: continue
            if (key.isNotEmpty() && !seen.add(key)) continue
            out.add(CitationSourceItem.fromMap(map, fallbackIndex = out.size + 1))
        }
    }
    // 正文里的链接（只扫助手正文，不扫工具返回的散文 —— 工具说了什么不等于
    // 模型引用了什么）。**只有本条消息真的用过工具（搜索/MCP）才收**：普通聊天
    // 里模型随手给的链接不该变成"来源胶囊"。
    val usedTools = parts.any { it is com.psyche.memo.data.model.ToolCallPart }
    if (!usedTools) return out
    val seenUrls = out.mapNotNull { it.url.takeIf { u -> u.isNotEmpty() } }
        .map { normalizeExternalUri(it)?.toString() ?: it }
        .toHashSet()
    for (part in parts) {
        if (part !is com.psyche.memo.data.model.TextPart) continue
        for (match in markdownLinkRegex.findAll(part.text)) {
            val label = match.groupValues[1].trim()
            val url = match.groupValues[2].trim()
            val normalized = normalizeExternalUri(url)?.toString() ?: url
            if (!seenUrls.add(normalized)) continue
            out.add(
                CitationSourceItem(
                    index = out.size + 1,
                    title = label.takeIf {
                        it.isNotEmpty() && it.lowercase() !in genericLinkLabels && it != url
                    }.orEmpty(),
                    url = url,
                ),
            )
        }
    }
    return out
}

/** chat_message_widget.dart _openCitationSource — 系统浏览器打开。 */
fun openExternal(context: Context, url: String): Boolean {
    val uri = normalizeExternalUri(url) ?: return false
    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/**
 * 来源卡（citation_sources_sheet.dart CitationSourceCard）：favicon 14dp +
 * 来源名分隔点 + 序号徽标；标题 2 行 14sp semibold；引文 2 行 12sp；
 * tags 横向滚动。点击打开外链。
 */
@Composable
fun CitationSourceCard(item: CitationSourceItem, displayIndex: Int, onTap: (CitationSourceItem) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val domain = citationDomain(item.url)
    val displayTitle = item.title.trim().takeIf { it.isNotEmpty() && !it.matches(Regex("^\\d+$")) }
        ?: domain.ifEmpty { item.url }
    val quote = buildString {
        val q = item.text.trim()
        if (q.isEmpty()) return@buildString
        val published = item.publishedText?.trim().orEmpty()
        append(if (published.isEmpty()) q else "$published - $q")
    }
    val sourceParts = buildList {
        item.sourceName?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        item.webSiteSource?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it) }
        if (isEmpty() && domain.isNotEmpty()) add(domain)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .background(
                cs.surfaceContainerHighest.copy(alpha = 0.45f),
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .clickable { onTap(item) }
            .padding(start = 8.dp, top = 20.dp, end = 4.dp, bottom = 20.dp),
    ) {
        if (sourceParts.isNotEmpty() || domain.isNotEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                FaviconIcon(domain = domain)
                Spacer(Modifier.width(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    sourceParts.forEachIndexed { i, part ->
                        if (i > 0) InlineDivider()
                        Text(
                            text = part,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                color = cs.onSurface.copy(alpha = 0.56f),
                            ),
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                IndexBadge(index = item.index ?: displayIndex + 1)
            }
        }
        Text(
            text = displayTitle,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.titleSmall.copy(
                fontSize = 14.sp,
                lineHeight = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
            ),
        )
        if (quote.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = quote,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontSize = 12.sp,
                    lineHeight = 16.sp,
                    color = cs.onSurface.copy(alpha = 0.62f),
                ),
            )
        }
        if (item.tags.isNotEmpty()) {
            Spacer(Modifier.height(3.dp))
            Row(
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            ) {
                for (tag in item.tags) CitationTag(tag)
            }
        }
    }
}

@Composable
private fun FaviconIcon(domain: String) {
    val cs = MaterialTheme.colorScheme
    if (domain.isEmpty()) {
        Icon(
            Lucide.Globe,
            contentDescription = null,
            tint = cs.onSurface.copy(alpha = 0.52f),
            modifier = Modifier.size(14.dp),
        )
        return
    }
    // citation_sources_sheet.dart _FaviconIcon —— favicone.com 服务 +
    // Globe 失败回退。
    SubcomposeAsyncImage(
        model = "https://favicone.com/$domain",
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .size(14.dp)
            .clip(CircleShape),
        loading = { GlobeFallback() },
        error = { GlobeFallback() },
    )
}

@Composable
private fun GlobeFallback() {
    val cs = MaterialTheme.colorScheme
    Icon(
        Lucide.Globe,
        contentDescription = null,
        tint = cs.onSurface.copy(alpha = 0.52f),
        modifier = Modifier.size(14.dp),
    )
}

@Composable
private fun InlineDivider() {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .padding(horizontal = 5.dp)
            .size(2.5.dp)
            .background(cs.onSurface.copy(alpha = 0.28f), CircleShape),
    )
}

@Composable
private fun IndexBadge(index: Int) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .width(if (index >= 10) 24.dp else 18.dp)
            .height(18.dp)
            .background(cs.primary.copy(alpha = 0.08f), RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .border(0.5.dp, cs.primary.copy(alpha = 0.22f), RoundedCornerShape(MemoRadius.SMALL_DP.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 10.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.primary,
            ),
        )
    }
}

@Composable
private fun CitationTag(tag: CitationSourceTag) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .background(cs.onSurface.copy(alpha = 0.06f), RoundedCornerShape(MemoRadius.SMALL_DP.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Text(
            text = tag.title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = cs.onSurface.copy(alpha = 0.62f),
            ),
        )
    }
}

/**
 * 消息内的来源摘要卡（chat_message_widget.dart _SourcesSummaryCard）：
 * 最多 3 个重叠 favicon + "N citations"，点击打开全量来源 sheet。
 */
@Composable
fun CitationSourcesSummaryCard(items: List<CitationSourceItem>, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val isDark = cs.surface.luminance() < 0.5f
    val domains = ArrayList<String>(3)
    val seen = HashSet<String>()
    for (item in items) {
        val host = citationDomain(item.url)
        if (host.isEmpty() || !seen.add(host)) continue
        domains.add(host)
        if (domains.size == 3) break
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(Color.Transparent, RoundedCornerShape(MemoRadius.CARD_DP.dp))
            .border(0.8.dp, cs.onSurface.copy(alpha = if (isDark) 0.16f else 0.10f), RoundedCornerShape(MemoRadius.CARD_DP.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (domains.isEmpty()) {
            Icon(
                Lucide.Globe,
                contentDescription = null,
                tint = cs.onSurface.copy(alpha = 0.52f),
                modifier = Modifier.size(18.dp),
            )
        } else {
            // _SourceFaviconStack：16dp 图标 11dp 重叠，至多 3 个。
            Box(modifier = Modifier.width(18.dp + 11.dp * (domains.size - 1))) {
                domains.forEachIndexed { i, domain ->
                    Box(
                        modifier = Modifier
                            .padding(start = (i * 11).dp)
                            .size(18.dp)
                            .background(cs.surface, CircleShape)
                            .border(0.8.dp, cs.onSurface.copy(alpha = if (isDark) 0.14f else 0.06f), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        FaviconIcon(domain)
                    }
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = androidx.compose.ui.res.stringResource(
                UiR.string.chat_message_widget_citations_count,
                items.size.toString(),
            ),
            maxLines = 1,
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface.copy(alpha = if (isDark) 0.90f else 0.86f),
            ),
        )
    }
}

/**
 * 全量来源 sheet（citation_sources_sheet.dart mobile bottom sheet 路径）：
 * 标题行 + 来源卡列表；点击卡片打开外链。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CitationSourcesSheet(items: List<CitationSourceItem>, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        containerColor = cs.overlaySurfaceColor(),
        dragHandle = null,
    ) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            MemoSheetHandle()
            // 标题行（citation_sources_sheet.dart _CitationSourcesDialogHeader）。
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
            ) {
                Text(
                    text = androidx.compose.ui.res.stringResource(
                        UiR.string.chat_message_widget_search_results_title,
                    ),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = cs.onSurface,
                    ),
                )
                if (items.size > 1) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = items.size.toString(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cs.onSurface.copy(alpha = 0.62f),
                        ),
                    )
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
            ) {
                itemsIndexed(items) { index, item ->
                    CitationSourceCard(
                        item = item,
                        displayIndex = index,
                        onTap = { openExternal(context, item.url) },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
