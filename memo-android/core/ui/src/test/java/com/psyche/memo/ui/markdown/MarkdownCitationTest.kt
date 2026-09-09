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

    // resolveCitationCapsule — [citation,domain](id) / [cite,domain](id)

    @Test
    fun citeCommaPrefixRendersCapsuleWithDomain() {
        // Device-observed drift: glm writes [cite,domain](id) instead of the
        // prompted [citation,domain](id) — both must become capsules.
        val c = resolveCitationCapsule("cite,news.cn", "a1b2c3", null)!!
        assertEquals("a1b2c3", c.key)
        assertEquals("news.cn", c.text)
    }

    @Test
    fun canonicalCitationCommaPrefixUnchanged() {
        val c = resolveCitationCapsule("citation,example.com", "abc123", null)!!
        assertEquals("abc123", c.key)
        assertEquals("example.com", c.text)
    }

    @Test
    fun prefixMatchIsCaseInsensitive() {
        val c = resolveCitationCapsule("CITE,news.cn", "a1b2c3", null)!!
        assertEquals("news.cn", c.text)
    }

    @Test
    fun idEchoLabelResolvesDomainFromSearchResults() {
        // [cite,e2dce5](e2dce5): the label carries the id, not a domain —
        // the display text comes from the message's search results.
        val c = resolveCitationCapsule("cite,e2dce5", "e2dce5") {
            CitationInfo(domain = "news.cn", index = 1)
        }!!
        assertEquals("e2dce5", c.key)
        assertEquals("news.cn", c.text)
    }

    @Test
    fun idEchoLabelFallsBackToRawMetadataWhenUnresolved() {
        val c = resolveCitationCapsule("cite,e2dce5", "e2dce5", null)!!
        assertEquals("e2dce5", c.text)
    }

    @Test
    fun urlDestinationCapsuleOpensLinkDirectly() {
        val c = resolveCitationCapsule("citation,example.com", "https://example.com/a", null)!!
        assertEquals("https://example.com/a", c.key)
        assertEquals("example.com", c.text)
    }

    @Test
    fun plainLinksAndLegacyExactLabelAreNotCapsules() {
        assertNull(resolveCitationCapsule("some site", "https://x.com", null))
        assertNull(resolveCitationCapsule("citation", "abc123", null)) // legacy branch
        assertNull(resolveCitationCapsule("cite,", "a1b2c3", null)) // empty metadata
        assertNull(resolveCitationCapsule("cite,news.cn", "", null)) // no key at all
    }
}
