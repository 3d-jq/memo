package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.model.ProviderConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0-1：`clientFor` 必须按供应商 kind 分派。
 *
 * 旧实现 `llmClients.firstOrNull { it.supports(providerId) }` 恒命中 OpenAI
 * （supports 恒 true 且排第一），Claude/Gemini 客户端是死代码：原生 anthropic /
 * google 供应商的请求会以 OpenAI 格式发到 api.anthropic.com（404），工具协议也
 * 无从生效。分派键 = `ProviderConfig.classifiedKind()`（openai/anthropic/gemini）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ClientDispatchTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(context)
    }

    private fun insertProvider(id: String, type: String): ProviderConfig {
        val config = ProviderConfig(
            id = id,
            name = id,
            providerType = type,
            apiKey = "k",
        )
        container.providerRepository.saveConfig(config)
        return config
    }

    private fun clientFor(providerId: String) =
        container.baseClientFor(providerId)

    @Test
    fun anthropicRowDispatchesToClaudeClient() {
        insertProvider("claude-row", "claude")
        val claude = clientFor("claude-row")
        val openai = clientFor("openai-row-missing")
        org.junit.Assert.assertSame(
            "anthropic 行必须走 Claude 客户端（不再恒落 OpenAI）",
            container.claudeClient,
            claude,
        )
    }

    @Test
    fun geminiRowDispatchesToGeminiClient() {
        insertProvider("gemini-row", "google")
        val gemini = clientFor("gemini-row")
        org.junit.Assert.assertSame("google 行必须走 Gemini 客户端", container.geminiClient, gemini)
        // 与 Claude 也必须是不同实例
        insertProvider("claude-row-2", "anthropic")
        org.junit.Assert.assertSame(container.claudeClient, clientFor("claude-row-2"))
    }

    @Test
    fun openaiRowAndUnknownKindFallBackToOpenAiClient() {
        val a = clientFor("openai-a")
        val b = clientFor("openai-b")
        val unknown = clientFor("totally-unknown-row")
        org.junit.Assert.assertSame("openai 行回落 OpenAI 客户端（同一实例）", container.openAiClient, a)
        org.junit.Assert.assertSame(container.openAiClient, b)
        org.junit.Assert.assertSame("未知行回落 OpenAI 客户端", container.openAiClient, unknown)
    }

    @Test
    fun explicitKindStringDispatchesWithoutARow() {
        // FetchModelsSheet 直接传 classifiedKind 的字符串（没有行）。
        org.junit.Assert.assertSame(container.claudeClient, clientFor("anthropic"))
        org.junit.Assert.assertSame(container.geminiClient, clientFor("gemini"))
        org.junit.Assert.assertSame(container.openAiClient, clientFor("openai"))
    }
}
