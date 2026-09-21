package com.psyche.memo.llm.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「JSON null 陷阱」的机制证明（用户实测：换 DeepSeek 后输出全是乱的、夹着 null）。
 *
 * kotlinx.serialization 里 `JsonNull` **也是 `JsonPrimitive`**，所以
 * `(element as? JsonPrimitive)?.content` 对 `"content": null` 会返回**字面量
 * "null"**，而不是空值 —— DeepSeek 思考阶段每个 chunk 都是
 * `{"content":null,"reasoning_content":"…"}`，于是正文被塞进一堆 "null"。
 * 正确写法是 `contentOrNull`（对 JsonNull 返回 null）。
 *
 * 所有解析**外部 JSON**（provider 响应、模型输出的工具参数、用户粘贴的配置）
 * 的取值处都必须用 `contentOrNull`，这条测试把该行为钉住。
 */
class JsonNullTrapTest {

    @Test
    fun jsonNullLooksLikeAPrimitiveWhoseContentIsTheLiteralNull() {
        val element = Json.parseToJsonElement("null")
        assertEquals("null", (element as JsonPrimitive).content)
        assertNull((element as JsonPrimitive).contentOrNull)
    }

    @Test
    fun missingFieldAndExplicitNullAgreeWithContentOrNull() {
        val delta = Json.parseToJsonElement("""{"content":null,"reasoning_content":"x"}""")
        val obj = delta as kotlinx.serialization.json.JsonObject
        assertNull((obj["content"] as? JsonPrimitive)?.contentOrNull)
        assertEquals("x", (obj["reasoning_content"] as? JsonPrimitive)?.contentOrNull)
    }
}
