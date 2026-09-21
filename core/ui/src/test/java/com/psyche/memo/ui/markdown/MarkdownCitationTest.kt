package com.psyche.memo.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ports markdown_with_highlight.dart `_normalizeCiteMarkers` /
 * `_normalizeRawCitationMetadata` / `_parseCitationRef`: `[cite:id]` and legacy
 * `[citation:ref]` markers become `[citation](id)` markdown links so the
 * renderer draws them as numbered capsules, and the ref parser mirrors the
 * Flutter `_parseCitationRef` rules (index:id, pure index, id-only).
 *
 * Capsule labels are always numeric indices (original-project behaviour);
 * domain metadata carried by drifted `[citation,domain](id)` spellings is
 * accepted but never displayed.
 *
 * Deviation from the original (2026-09-12, user decision): an unresolvable
 * citation is dropped rather than rendered as "?" — see the capsule section.
 */
class MarkdownCitationTest {

    @Test
    fun singleCiteBecomesCitationLink() {
        // Normalization is in-place: only the [cite:...] token is rewritten,
        // surrounding prose is preserved.
        assertEquals("see [citation](abc) here", preprocessCitations("see [cite:abc] here"))
    }

    @Test
    fun commaSeparatedCiteExpandsToMultipleLinks() {
        assertEquals("[citation](a) [citation](b)", preprocessCitations("[cite:a, b]"))
    }

    @Test
    fun legacyCitationColonRefKeepsIndexAndId() {
        // markdownTarget = "index:id" when index != id.
        assertEquals("[citation](1:abc)", preprocessCitations("[citation:1:abc]"))
    }

    @Test
    fun legacyCitationPureIndexShorthand() {
        assertEquals("[citation](5)", preprocessCitations("[citation:5]"))
    }

    @Test
    fun normalLinksAreUntouched() {
        assertEquals(
            "a [link](https://x.com) b",
            preprocessCitations("a [link](https://x.com) b"),
        )
    }

    // ---- 来源链接 → 序号胶囊（2026-09-12 用户实测 DeepSeek） ----

    @Test
    fun sourceUrlBecomesCapsuleWithTheResolvedIndex() {
        val capsule = resolveSourceUrlCapsule("https://aihot.news/items/cmtx301no") { key ->
            if (key == "https://aihot.news/items/cmtx301no") CitationInfo(domain = "aihot.news", index = 7) else null
        }
        assertEquals("https://aihot.news/items/cmtx301no", capsule?.key)
        assertEquals("7", capsule?.text)
    }

    @Test
    fun unknownUrlAndUnresolvedIndexKeepThePlainLink() {
        // 不是来源的链接照旧按普通链接渲染（resolver 返回 null）。
        assertNull(resolveSourceUrlCapsule("https://example.com/x") { null })
        assertNull(resolveSourceUrlCapsule("https://example.com/x") { CitationInfo(index = null) })
        // 非 http(s) 目标不转换。
        assertNull(resolveSourceUrlCapsule("mailto:a@b.c") { CitationInfo(index = 1) })
        assertNull(resolveSourceUrlCapsule("") { CitationInfo(index = 1) })
    }

    @Test
    fun citeInsideSentenceWithAdjacentText() {
        assertEquals(
            "结论[citation](a1) 与 [citation](b2)。",
            preprocessCitations("结论[cite:a1] 与 [cite:b2]。"),
        )
    }

    @Test
    fun parsesIdOnlyRef() {
        val ref = parseCitationRef("abc")!!
        assertEquals("abc", ref.indexText)
        assertEquals("abc", ref.id)
        assertEquals("abc", ref.markdownTarget)
    }

    @Test
    fun parsesIndexIdRef() {
        val ref = parseCitationRef("1:abc")!!
        assertEquals("1", ref.indexText)
        assertEquals("abc", ref.id)
        assertEquals("1:abc", ref.markdownTarget)
    }

    @Test
    fun rejectsEmptyAndInvalidRefs() {
        assertNull(parseCitationRef(""))
        assertNull(parseCitationRef("a b")) // whitespace inside id
        assertNull(parseCitationRef("a)b")) // ')' inside id
        assertNull(parseCitationRef(":")) // empty index + id
    }

    // resolveCitationCapsule — [citation,domain](id) / [cite,domain](id).
    // The label metadata is parsed but never displayed: the capsule shows the
    // numeric index resolved from the message's search results (original
    // project behaviour). When no index is available the marker is dropped —
    // an empty text — rather than drawn as "?" (2026-09-12, user decision).

    @Test
    fun citeCommaPrefixRendersNumericCapsule() {
        // Device-observed drift: glm writes [cite,domain](id) instead of the
        // prompted [citation,domain](id) — both must become capsules.
        val c = resolveCitationCapsule("cite,news.cn", "a1b2c3") {
            CitationInfo(index = 4)
        }!!
        assertEquals("a1b2c3", c.key)
        assertEquals("4", c.text)
    }

    @Test
    fun unresolvedCapsuleIsMarkedForDropping() {
        // Empty text = "citation-shaped but unresolvable": the renderer emits
        // nothing instead of a "?" capsule.
        val c = resolveCitationCapsule("cite,e2dce5", "e2dce5", null)!!
        assertEquals("e2dce5", c.key)
        assertEquals("", c.text)
    }

    @Test
    fun canonicalCitationCommaPrefixResolvesIndexFromSearchResults() {
        val c = resolveCitationCapsule("citation,example.com", "abc123") {
            CitationInfo(domain = "example.com", index = 2)
        }!!
        assertEquals("abc123", c.key)
        assertEquals("2", c.text)
    }

    @Test
    fun prefixMatchIsCaseInsensitive() {
        val c = resolveCitationCapsule("CITE,news.cn", "a1b2c3") {
            CitationInfo(index = 7)
        }!!
        assertEquals("7", c.text)
    }

    @Test
    fun idEchoLabelResolvesIndexFromSearchResults() {
        // [cite,e2dce5](e2dce5): the label carries the id, not a domain —
        // the display text comes from the message's search results.
        val c = resolveCitationCapsule("cite,e2dce5", "e2dce5") {
            CitationInfo(domain = "news.cn", index = 1)
        }!!
        assertEquals("e2dce5", c.key)
        assertEquals("1", c.text)
    }

    @Test
    fun domainMetadataIsNeverDisplayed() {
        // Even when the model supplies a real domain, the capsule shows digits.
        val c = resolveCitationCapsule("citation,www.example.co.uk", "abc123") {
            CitationInfo(domain = "www.example.co.uk", index = 3)
        }!!
        assertEquals("3", c.text)
    }

    @Test
    fun unresolvedIndexIsDroppedRatherThanQuestionMarked() {
        val c = resolveCitationCapsule("citation,e2dce5", "e2dce5", null)!!
        assertEquals("", c.text)
    }

    @Test
    fun numericMetadataSurvivesWhenUnresolved() {
        // Legacy [citation,3](...) style metadata is still a usable number, so
        // the marker stays — only a truly unknown index is dropped.
        val c = resolveCitationCapsule("citation,3", "abc123", null)!!
        assertEquals("3", c.text)
    }

    @Test
    fun urlDestinationCapsuleOpensLinkDirectly() {
        val c = resolveCitationCapsule("citation,example.com", "https://example.com/a") {
            CitationInfo(index = 5)
        }!!
        assertEquals("https://example.com/a", c.key)
        assertEquals("5", c.text)
    }

    @Test
    fun plainLinksAndLegacyExactLabelAreNotCapsules() {
        assertNull(resolveCitationCapsule("some site", "https://x.com", null))
        assertNull(resolveCitationCapsule("citation", "abc123", null)) // legacy branch
        assertNull(resolveCitationCapsule("cite,", "a1b2c3", null)) // empty metadata
        assertNull(resolveCitationCapsule("cite,news.cn", "", null)) // no key at all
    }
}
