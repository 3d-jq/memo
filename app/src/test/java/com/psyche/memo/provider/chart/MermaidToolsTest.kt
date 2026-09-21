package com.psyche.memo.provider.chart

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.provider.LocalToolExecutors
import com.psyche.memo.provider.generation.MEDIA_TOOL_NAMES
import com.psyche.memo.ui.BuiltInToolCatalog
import com.psyche.memo.ui.BuiltInToolGroup
import com.psyche.memo.ui.MemoryPromptLang
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Mermaid 工具（用户 2026-09-18「加个 Mermaid…分成两个工具吧，Mermaid 和自由画板」）。
 *
 * WebView 渲染本身只能在真机验证；这里钉住**纯函数与接线**：
 * 主题变量、注入脚本（中文/换行转义）、PNG dataURL 解码、错误路径、四道工具门。
 */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class MermaidToolsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun args(json: String): JsonObject = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `theme variables follow the palette and are valid json`() {
        val light = JSONObject(MermaidRenderer.themeVariablesJson(ChartPalette.LIGHT))
        assertEquals("#FFFFFF", light.getString("background"))
        assertEquals("#2C2C2A", light.getString("textColor"))
        assertEquals("#185FA5", light.getString("primaryColor"))

        val dark = JSONObject(MermaidRenderer.themeVariablesJson(ChartPalette.DARK))
        assertEquals("#1C1C1B", dark.getString("background"))
        // 深色主题用提亮版系列色
        assertEquals("#85B7EB", dark.getString("primaryColor"))
        assertEquals("sans-serif", dark.getString("fontFamily"))
    }

    @Test
    fun `the render script escapes chinese and newlines`() {
        val code = "flowchart TD\n  A[读取数据] --> B{校验}"
        val script = MermaidRenderer.buildRenderScript(code, ChartPalette.LIGHT)
        assertTrue(script, script.startsWith("window.renderMermaid("))
        assertTrue(script, script.endsWith(", ${MermaidRenderer.TARGET_WIDTH_PX});"))
        // 换行与中文都被 JSON 转义，不会把脚本拆行/炸掉
        assertFalse(script, script.contains("\n"))
        assertTrue(script, script.contains("\\n"))
        assertTrue(script, script.contains("读取数据"))
        // 脚本本身是合法 JS 调用形式（引号成对）
        assertTrue(script, script.count { it == '"' } % 2 == 0)
    }

    @Test
    fun `png data urls decode and garbage returns null`() {
        val png = MermaidRenderer.decodePngDataUrl("data:image/png;base64,iVBORw0KGgo=")
        assertNotNull(png)
        assertEquals(0x89.toByte(), png!![0])
        assertNull(MermaidRenderer.decodePngDataUrl("data:image/png;base64,@@@not-base64@@@"))
        assertNull(MermaidRenderer.decodePngDataUrl("no data url here"))
    }

    @Test
    fun `a missing code field comes back as a tool error without touching the webview`() {
        val result = runBlocking {
            Json.parseToJsonElement(
                MermaidTools.execute(context, args("{}"), ChartPalette.LIGHT),
            ).jsonObject
        }
        assertEquals("tool_error", result["type"]?.jsonPrimitive?.content)
        assertEquals("mermaid_invalid", result["error"]?.jsonPrimitive?.content)
        assertTrue(
            result["message"]!!.jsonPrimitive.content,
            result["message"]!!.jsonPrimitive.content.contains("flowchart TD"),
        )

        // 空白 code 也不该去开 WebView（会超时），同样直接回错
        val blank = runBlocking {
            Json.parseToJsonElement(
                MermaidTools.execute(
                    context,
                    buildJsonObject { put("code", "   ") }.let { Json.parseToJsonElement(it.toString()).jsonObject },
                    ChartPalette.LIGHT,
                ),
            ).jsonObject
        }
        assertEquals("mermaid_failed", blank["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `is wired as its own local tool`() {
        val entry = BuiltInToolCatalog.entries(MemoryPromptLang.zh)
            .firstOrNull { it.name == MermaidTools.TOOL_NAME }
        assertTrue("render_mermaid 应该出现在内置工具目录里", entry != null)
        assertEquals(BuiltInToolGroup.LOCAL, entry!!.group)
        assertTrue(MermaidTools.TOOL_NAME in BuiltInToolCatalog.LocalToolNames.all)
        assertTrue(MermaidTools.TOOL_NAME in LocalToolExecutors.EXECUTABLE)
        assertTrue(MermaidTools.TOOL_NAME in MEDIA_TOOL_NAMES)

        // 与自由画板是两个独立工具
        assertFalse(MermaidTools.TOOL_NAME == VisualTools.TOOL_NAME)
        assertEquals(0, MermaidTools.ALL_TOOL_NAMES.intersect(VisualTools.ALL_TOOL_NAMES).size)

        // 定义：只要 code，且描述里点明「数据图用 render_visual」
        val fn = MermaidTools.DEFINITION["function"]!!.jsonObject
        assertEquals(MermaidTools.TOOL_NAME, fn["name"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("code"),
            fn["parameters"]!!.jsonObject["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertTrue(MermaidTools.DESCRIPTION, MermaidTools.DESCRIPTION.contains("render_visual"))
        assertTrue(MermaidTools.DESCRIPTION, MermaidTools.DESCRIPTION.contains("flowchart"))
    }
}
