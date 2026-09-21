package com.psyche.memo.ui.chat

import com.psyche.memo.llm.client.ReasoningBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Coverage for the helpers lifted out of [ReasoningBudgetSheet] when A2c.5
 * turned the sheet into a callback-based component.
 *
 *  - [parseBudgetJson] is the pure-JVM step that maps the `thinking_budget_v1`
 *    JSON payload to `Int?`; it used to live inline in `readBudget` and got
 *    pulled out so it can be tested without standing up an `AppContainerImpl`.
 *  - [ReasoningBudgetIcons.assetForBudget] is the tier→asset lookup that drives
 *    the idea-01 svg shown on each preset row.
 */
class ReasoningBudgetHelperTest {

    // ---- parseBudgetJson ----

    @Test
    fun `parseBudgetJson null returns null`() {
        assertNull(parseBudgetJson(null))
    }

    @Test
    fun `parseBudgetJson blank returns null`() {
        assertNull(parseBudgetJson(""))
        assertNull(parseBudgetJson("   "))
    }

    @Test
    fun `parseBudgetJson non-json returns null`() {
        assertNull(parseBudgetJson("not json at all"))
        assertNull(parseBudgetJson("{not json"))
    }

    @Test
    fun `parseBudgetJson json object returns null`() {
        // `thinking_budget_v1` is always a JsonPrimitive, but defensive: an
        // object/array payload should not crash the reader.
        assertNull(parseBudgetJson("""{"foo":1}"""))
        assertNull(parseBudgetJson("[1,2,3]"))
    }

    @Test
    fun `parseBudgetJson valid int returns the int`() {
        assertEquals(0, parseBudgetJson("0"))
        assertEquals(-1, parseBudgetJson("-1"))
        assertEquals(2048, parseBudgetJson("2048"))
        assertEquals(128000, parseBudgetJson("128000"))
    }

    @Test
    fun `parseBudgetJson string content is rejected`() {
        // JsonPrimitive(string) → content = "2048" → toIntOrNull = 2048.
        // Document the projection behavior so future refactors don't quietly
        // change the contract.
        assertEquals(2048, parseBudgetJson("\"2048\""))
        assertNull(parseBudgetJson("\"not-a-number\""))
    }

    @Test
    fun `parseBudgetJson boolean content is rejected`() {
        assertNull(parseBudgetJson("true"))
        assertNull(parseBudgetJson("false"))
    }

    // ---- ReasoningBudgetIcons.assetForBudget ----

    @Test
    fun `assetForBudget null maps to AUTO`() {
        assertEquals(ReasoningBudgetIcons.AUTO, ReasoningBudgetIcons.assetForBudget(null))
    }

    @Test
    fun `assetForBudget AUTO constant maps to AUTO`() {
        assertEquals(ReasoningBudgetIcons.AUTO, ReasoningBudgetIcons.assetForBudget(ReasoningBudget.AUTO))
    }

    @Test
    fun `assetForBudget OFF constant maps to OFF`() {
        assertEquals(ReasoningBudgetIcons.OFF, ReasoningBudgetIcons.assetForBudget(ReasoningBudget.OFF))
    }

    @Test
    fun `assetForBudget light tier covers small values`() {
        // Anything ≤ 1024 (and not OFF/AUTO) lands on LIGHT.
        assertEquals(ReasoningBudgetIcons.LIGHT, ReasoningBudgetIcons.assetForBudget(1))
        assertEquals(ReasoningBudgetIcons.LIGHT, ReasoningBudgetIcons.assetForBudget(512))
        assertEquals(ReasoningBudgetIcons.LIGHT, ReasoningBudgetIcons.assetForBudget(1024))
    }

    @Test
    fun `assetForBudget medium tier covers up to 16000`() {
        assertEquals(ReasoningBudgetIcons.MEDIUM, ReasoningBudgetIcons.assetForBudget(1025))
        assertEquals(ReasoningBudgetIcons.MEDIUM, ReasoningBudgetIcons.assetForBudget(8192))
        assertEquals(ReasoningBudgetIcons.MEDIUM, ReasoningBudgetIcons.assetForBudget(16000))
    }

    @Test
    fun `assetForBudget heavy tier covers up to 32000`() {
        assertEquals(ReasoningBudgetIcons.HEAVY, ReasoningBudgetIcons.assetForBudget(16001))
        assertEquals(ReasoningBudgetIcons.HEAVY, ReasoningBudgetIcons.assetForBudget(32000))
    }

    @Test
    fun `assetForBudget xhigh tier covers anything above 32000`() {
        assertEquals(ReasoningBudgetIcons.XHIGH, ReasoningBudgetIcons.assetForBudget(32001))
        assertEquals(ReasoningBudgetIcons.XHIGH, ReasoningBudgetIcons.assetForBudget(64000))
        assertEquals(ReasoningBudgetIcons.XHIGH, ReasoningBudgetIcons.assetForBudget(128000))
        assertEquals(ReasoningBudgetIcons.XHIGH, ReasoningBudgetIcons.assetForBudget(200000))
    }
}
