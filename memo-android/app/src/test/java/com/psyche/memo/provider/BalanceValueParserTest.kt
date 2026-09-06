package com.psyche.memo.provider

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** Port coverage of ProviderBalanceValueParser + balanceUri building. */
class BalanceValueParserTest {

    private val json = Json.parseToJsonElement(
        """
        {
          "data": {
            "total_credits": 50,
            "total_usage": 12.345,
            "items": [{"balance": "7.25"}, {"balance": 3}]
          },
          "balance_infos": [{"total_balance": "88.5"}],
          "balance": 9
        }
        """.trimIndent(),
    )

    @Test
    fun `reads nested dot paths and formats to two decimals`() {
        assertEquals("50.00", BalanceValueParser.format(json, "data.total_credits"))
        assertEquals("12.35", BalanceValueParser.format(json, "data.total_usage"))
        assertEquals("88.50", BalanceValueParser.format(json, "balance_infos[0].total_balance"))
        assertEquals("3.00", BalanceValueParser.format(json, "data.items[1].balance"))
        // String numerics parse too (upstream num.tryParse).
        assertEquals("7.25", BalanceValueParser.format(json, "data.items[0].balance"))
        assertEquals("9.00", BalanceValueParser.format(json, "balance"))
    }

    @Test
    fun `subtraction expressions with spaces around the minus`() {
        assertEquals(
            "37.66",
            BalanceValueParser.format(json, "data.total_credits - data.total_usage"),
        )
    }

    @Test
    fun `non-numeric leaf string passes through verbatim`() {
        val doc = Json.parseToJsonElement("""{"a": "pending"}""")
        assertEquals("pending", BalanceValueParser.format(doc, "a"))
    }

    @Test(expected = ProviderBalanceService.BalanceException::class)
    fun `empty expression throws`() {
        BalanceValueParser.format(json, "  ")
    }

    @Test(expected = ProviderBalanceService.BalanceException::class)
    fun `missing path throws not found`() {
        BalanceValueParser.format(json, "data.nope")
    }

    @Test(expected = ProviderBalanceService.BalanceException::class)
    fun `out of range index throws not found`() {
        BalanceValueParser.format(json, "data.items[5].balance")
    }

    @Test(expected = ProviderBalanceService.BalanceException::class)
    fun `non numeric subtraction operand throws`() {
        BalanceValueParser.format(json, "balance - data.nope")
    }

    // ---- balanceUri ----

    @Test
    fun `absolute api path wins`() {
        assertEquals(
            "https://example.com/credits",
            ProviderBalanceService.balanceUri("https://api.openai.com/v1", "https://example.com/credits"),
        )
    }

    @Test
    fun `relative path joins base without double slashes`() {
        assertEquals(
            "https://api.openai.com/v1/credits",
            ProviderBalanceService.balanceUri("https://api.openai.com/v1/", "/credits"),
        )
        assertEquals(
            "https://api.openai.com/v1/user/balance",
            ProviderBalanceService.balanceUri("https://api.openai.com/v1", "user/balance"),
        )
    }

    @Test(expected = ProviderBalanceService.BalanceException::class)
    fun `empty api path throws`() {
        ProviderBalanceService.balanceUri("https://api.openai.com/v1", "")
    }
}
