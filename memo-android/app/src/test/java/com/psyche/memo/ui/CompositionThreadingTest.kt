package com.psyche.memo.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「组合期不许干重活」的**机器守卫**（PORTING §5.13）。
 *
 * 约定：`remember { … }` 块里的代码就发生在**跑组合的那一帧**上（主线程），所以里面
 * 不许出现 SQLite / DAO / `ContentResolver` / 文件读写 —— 那是「点开界面卡一下」
 * 的直接来源（2026-09-15 普查：统计页组合期兜底聚合、进会话那帧查库 + 画旧像素都
 * 属于这一类）。要「按 key 读一次」用 `rememberLoaded`（`ui/AsyncLoad.kt`，内部
 * `produceState` + `Dispatchers.IO`）；要进 `LaunchedEffect` 的记得自己包
 * `withContext(Dispatchers.IO)`（effect 体也是主线程）。
 *
 * **偏好读取（`preferenceRepository.readJson`）刻意不在禁止之列**：它已经带进程内
 * 缓存（`PreferenceCacheTest` 钉住语义），组合期读是内存查表。**别**把 readJson
 * 加进 [FORBIDDEN] —— 那会让约 195 处合法调用全部报错。
 *
 * 误报的处理方式：**先修**（换成 `rememberLoaded`），确实不碰库/文件的（例如只读
 * 内存字段）才进 [ALLOWLIST]，并写清为什么。
 */
class CompositionThreadingTest {

    private val uiDir = File("src/main/java/com/psyche/memo")

    /**
     * 只列**真的会干活**的调用：查库/读写文件/整表解码。
     *
     * 刻意不列 `readableDatabase` / `writableDatabase` / `XxxDao(` / `XxxRepository(` ——
     * `remember { SomeRepository(container.database.writableDatabase, …) }` 只是**造对象**
     * （拿库句柄），真正的读写在 `LaunchedEffect` + `withContext(Dispatchers.IO)` 里；
     * 把它们算进来会让守卫变成噪音。造完对象**在组合期真读**的（`remember(k) { repo.items() }`）
     * 由下面这些调用词抓住。
     */
    private val forbidden = listOf(
        ".getAll(" to "整表读取",
        ".query(" to "SQL 查询",
        ".rawQuery(" to "SQL 查询",
        ".execSQL(" to "SQL 写入",
        ".count(" to "COUNT 查询",
        ".items(" to "仓库读取（InstructionInjection）",
        ".books(" to "仓库读取（WorldBook）",
        ".tags(" to "仓库读取（Tag）",
        ".prune(" to "仓库写入（WorldBook）",
        "loadModelOptions" to "provider_rows 整表 + 逐条 JSON 解码",
        "providerConfig(" to "供应商配置（首次未命中缓存要查库）",
        "currentAssistant(" to "助手行（查 assistant_rows）",
        "assistantStore" to "助手 DAO",
        "userProfileStore" to "用户资料 DAO",
        "StatsAggregation." to "统计聚合",
        "contentResolver" to "ContentResolver",
        "AssetManager" to "资源读取",
        "decodeFile" to "图片解码（文件 IO）",
        "listFiles(" to "目录遍历",
        "isFile" to "文件系统",
        "readText()" to "文件读取",
        "exists()" to "文件系统",
    )

    /**
     * 豁免 = 文件 + **具体 token** + 原因。按 token 豁免而不是按文件整包豁免：同一个
     * 文件里出现**新的**那类调用（例如 HomeScreen 里冒出一个 `.rawQuery(`）照样失败。
     */
    private class Exemption(val path: String, val token: String, val reason: String)

    private val exemptions = listOf(
        // --- 启动已预热解码缓存（AppContainerImpl.prewarmConfigCaches，IO）---
        Exemption("ui/HomeScreen.kt", "providerConfig(", "启动预热 ProviderConfigCache；命中即内存读"),
        Exemption("ui/HomeScreen.kt", "currentAssistant(", "启动预热 AssistantCache；未命中是一次单行查询"),
        Exemption("ui/HomeScreen.kt", "assistantStore", "启动预热 AssistantCache"),
        Exemption("ui/SideDrawerContent.kt", "assistantStore", "启动预热 AssistantCache"),
        Exemption("ui/ModelDetailSheet.kt", "providerConfig(", "启动预热 ProviderConfigCache"),
        Exemption("ui/ModelSelectSheet.kt", "providerConfig(", "启动预热 ProviderConfigCache"),
        Exemption("ui/ProviderAvatar.kt", "providerConfig(", "列表行级，启动预热 ProviderConfigCache 兜住"),
        Exemption("ui/SkillSelectorSheet.kt", "currentAssistant(", "启动预热 AssistantCache"),
        Exemption("ui/WorkspaceSelectorSheet.kt", "currentAssistant(", "启动预热 AssistantCache"),
        Exemption("ui/chat/McpAssistantSheet.kt", "currentAssistant(", "启动预热 AssistantCache"),
        // --- 小表单次读取，进页面/开 sheet 一次（<2ms，已知欠账见 PORTING §5.13）---
        Exemption("ui/InstructionInjectionScreen.kt", ".items(", "指令注入小表，随 reload key 一次"),
        Exemption("ui/TagsManagerScreen.kt", ".tags(", "标签小表，随 reload key 一次"),
        Exemption("ui/WorldBookScreen.kt", ".books(", "世界书小表，随 reload key 一次"),
        Exemption("ui/WorldBookSheet.kt", ".books(", "世界书小表，随 reload key 一次"),
    )

    @Test
    fun `ui code reaches the test working dir`() {
        assertTrue(
            "expected ${uiDir.absolutePath} to exist — check the Gradle test working dir",
            uiDir.isDirectory,
        )
    }

    @Test
    fun `no remember block touches the database or the file system`() {
        val offenders = mutableListOf<String>()
        uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.invariantSeparatorsPath }
            .forEach { file ->
                val rel = file.relativeTo(uiDir).invariantSeparatorsPath
                // 注释里的示例代码（「原来是 remember { dao.getAll() }」）不算违规 ——
                // 先把注释抹成空白（**保留换行**，行号才不会漂）再扫。
                val text = blankComments(file.readText())
                for (block in rememberBlocks(text)) {
                    val hit = forbidden.firstOrNull { block.text.contains(it.first) } ?: continue
                    if (exemptions.any { it.path == rel && it.token == hit.first }) continue
                    val line = block.line + block.text.substring(0, block.text.indexOf(hit.first))
                        .count { it == '\n' }
                    offenders += "$rel:$line remember{ …${hit.first}… } ← ${hit.second}"
                }
            }

        assertTrue(
            "组合期（remember 块）里不许查库/读文件/解码整表 —— 用 rememberLoaded（AsyncLoad.kt）" +
                "或把工作挪进 withContext(Dispatchers.IO)；确实安全的（启动预热过的缓存、小表单次读取）" +
                "写进 CompositionThreadingTest 的 exemptions 并说明原因：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private class RememberBlock(val line: Int, val text: String)

    /**
     * 扫出文件里所有 `remember { … }` / `remember(keys) { … }` 的 lambda 体
     * （含嵌套花括号）。刻意**不**匹配 `rememberLoaded` / `rememberSaveable` /
     * `rememberCoroutineScope` 这类以 remember 开头的其它函数：紧跟 `remember`
     * 的字符必须是分隔符。
     */
    private fun rememberBlocks(source: String): List<RememberBlock> {
        val out = mutableListOf<RememberBlock>()
        var i = 0
        while (i < source.length) {
            val idx = source.indexOf("remember", i)
            if (idx < 0) break
            i = idx + "remember".length
            val before = source.getOrNull(idx - 1)
            if (before != null && (before.isLetterOrDigit() || before == '_' || before == '.')) continue
            if (source.getOrNull(i)?.let { it.isLetterOrDigit() || it == '_' } == true) continue
            val brace = trailingLambdaStart(source, i) ?: continue
            val end = matchingBrace(source, brace) ?: break
            out += RememberBlock(
                line = source.substring(0, brace).count { it == '\n' } + 1,
                text = source.substring(brace + 1, end),
            )
            i = end
        }
        return out
    }

    /** `remember` 之后跳过可选的 `(…)` 实参，返回尾随 lambda 的 `{` 下标。 */
    private fun trailingLambdaStart(source: String, from: Int): Int? {
        var i = from
        while (i < source.length && source[i].isWhitespace()) i++
        if (i >= source.length) return null
        if (source[i] == '(') {
            i = matchingParen(source, i) ?: return null
            i++
            while (i < source.length && source[i].isWhitespace()) i++
        }
        return if (i < source.length && source[i] == '{') i else null
    }

    private fun matchingParen(source: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return i
                '"', '\'' -> i = skipStringLiteral(source, i) - 1
            }
            i++
        }
        return null
    }

    private fun matchingBrace(source: String, open: Int): Int? {
        var depth = 0
        var i = open
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
                '"', '\'' -> i = skipStringLiteral(source, i) - 1
            }
            i++
        }
        return null
    }
}