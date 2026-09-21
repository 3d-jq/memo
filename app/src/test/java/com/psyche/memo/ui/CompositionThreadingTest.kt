package com.psyche.memo.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import com.psyche.memo.ui.chat.loadQuickPhrases

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
        "loadQuickPhrases" to "快捷短语仓库（两条 SQL）",
        "enabledServers(" to "MCP 仓库查询",
    )

    /**
     * **通用**规则（比固定词表更重要）：`remember { … }` 块里出现
     * 「构造 DAO/仓库**紧接着调用**它」——`XxxDao(db).something()` /
     * `XxxRepository(db).something()` —— 一律算违规。
     *
     * 为什么要有它：光靠词表抓不住「包在辅助函数里的查询」（例如
     * `remember { loadQuickPhrases(container) }`，那函数里是两条 SQL；以及
     * `McpRepository(container.database.readableDatabase).enabledServers()`）。这两处
     * 曾经躲过了第一版守卫，用户 2026-09-15「还是卡呀」之后才被翻出来。
     */
    private val constructThenCall = Regex(
        "\\b\\w+(Dao|Repository)\\([^)]*\\)\\s*\\.",
    )

    /**
     * 豁免 = 文件 + **具体 token** + 原因。按 token 豁免而不是按文件整包豁免：同一个
     * 文件里出现**新的**那类调用（例如 HomeScreen 里冒出一个 `.rawQuery(`）照样失败。
     */
    private class Exemption(val path: String, val token: String, val reason: String)

    private val exemptions = listOf(
        // --- 启动已预热解码缓存（AppContainerImpl.prewarmConfigCaches，IO）---
        // 2026-09-16 第 3 步：会话页从 ui/HomeScreen.kt 拆成 ui/chat/ChatContent.kt 与
        // ui/chat/ChatInputBar.kt，豁免按**文件**登记，所以跟着搬（三条 providerConfig/
        // currentAssistant/assistantStore 现在都在 ChatContent.kt 里）。
        Exemption("ui/chat/ChatContent.kt", "providerConfig(", "启动预热 ProviderConfigCache；命中即内存读"),
        Exemption("ui/chat/ChatContent.kt", "currentAssistant(", "启动预热 AssistantCache；未命中是一次单行查询"),
        Exemption("ui/chat/ChatContent.kt", "assistantStore", "启动预热 AssistantCache"),
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
                    val hit = forbidden.firstOrNull { block.text.contains(it.first) }
                    if (hit != null && exemptions.none { it.path == rel && it.token == hit.first }) {
                        val line = block.line + block.text.substring(0, block.text.indexOf(hit.first))
                            .count { it == '\n' }
                        offenders += "$rel:$line remember{ …${hit.first}… } ← ${hit.second}"
                        continue
                    }
                    // 通用规则：DAO/仓库「构造 + 立刻调用」（词表抓不到辅助函数里的查询）。
                    val call = constructThenCall.find(block.text)
                    if (call != null &&
                        exemptions.none { it.path == rel && it.token == "construct-then-call" }
                    ) {
                        val line = block.line + block.text.substring(0, call.range.first)
                            .count { it == '\n' }
                        offenders += "$rel:$line remember{ …${call.value.trim()}… } ← 组合期查库" +
                            "（构造 DAO/仓库后立刻调用）"
                    }
                }
            }

        assertTrue(
            "组合期（remember 块）里不许查库/读文件/解码整表 —— 用 rememberLoaded（AsyncLoad.kt）" +
                "或把工作挪进 withContext(Dispatchers.IO)；确实安全的（启动预热过的缓存、小表单次读取）" +
                "写进 CompositionThreadingTest 的 exemptions 并说明原因：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * 同一条约定在 **effect 体**上的守卫：`LaunchedEffect` / `DisposableEffect` 的 lambda
     * 默认就跑在组合线程上（换线程只有靠自己写 `withContext(Dispatchers.IO)`），所以体里
     * 出现「真的打库/读盘」的调用却没先切调度器，就等于组合期查库。
     *
     * 为什么单独补这一条：原来的守卫只扫 `remember {}`，于是设置域四个子页在
     * `LaunchedEffect(Unit) { preferenceRepository.readJson(...) }` 里主线程读库，
     * **门禁全绿、真机点进去卡一下**（用户 2026-09-20「很多界面点击都会加载卡一下」）。
     * 偏好读取在 `remember` 里豁免过（进程内缓存），但冷启动后第一次未命中是真 SQL 往返，
     * 在 effect 体里同样属主线程 I/O，所以这里纳入禁止项。
     */
    @Test
    fun `no effect body touches the database without dispatchers IO`() {
        val offenders = mutableListOf<String>()
        uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.invariantSeparatorsPath }
            .forEach { file ->
                val rel = file.relativeTo(uiDir).invariantSeparatorsPath
                val text = blankComments(file.readText())
                for (block in effectBlocks(text)) {
                    val hit = EFFECT_FORBIDDEN.firstOrNull { block.text.contains(it) }
                    val callAt = when {
                        hit != null -> block.text.indexOf(hit)
                        else -> constructThenCall.find(block.text)?.range?.first
                    } ?: continue
                    val wrapped = ioSwitch.find(block.text)
                    if (wrapped != null && wrapped.range.first < callAt) continue
                    val token = hit ?: "construct-then-call"
                    if (exemptions.any { it.path == rel && it.token == token }) continue
                    val line = block.line + block.text.substring(0, callAt).count { it == '\n' }
                    offenders += "$rel:$line effect { …$token… } ← 主线程体里查库，缺 withContext(Dispatchers.IO)"
                }
            }

        assertTrue(
            "LaunchedEffect / DisposableEffect 体也在主线程上：查库/读文件要用 " +
                "withContext(Dispatchers.IO) 包住（或用 rememberLoaded）。" +
                "确实安全的写进 exemptions 并说明原因：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /** effect 体专用的词表：偏好的冷读/写、整表读、裸 SQL、几个仓库的取数方法。 */
    private val EFFECT_FORBIDDEN = listOf(
        "preferenceRepository.readJson",
        "preferenceRepository.writeJson",
        ".getAll(",
        ".rawQuery(",
        ".execSQL(",
        ".services(",
        ".items(",
        ".books(",
        ".tags(",
        "assistantStore",
        "conversationDao",
        "messageDao",
    )

    /** `withContext(Dispatchers.IO)`，带不带 `kotlinx.coroutines.` 限定名都算。 */
    private val ioSwitch = Regex(
        "withContext\\(\\s*(?:kotlinx\\.coroutines\\.)?Dispatchers\\.(?:IO|Default)",
    )

    private class EffectBlock(val line: Int, val text: String)

    /** 扫 `LaunchedEffect(…) { … }` / `DisposableEffect(…) { … }` 的尾随 lambda 体。 */
    private fun effectBlocks(source: String): List<EffectBlock> {
        val out = mutableListOf<EffectBlock>()
        var i = 0
        while (i < source.length) {
            val start = listOf("LaunchedEffect", "DisposableEffect")
                .mapNotNull { name -> source.indexOf(name, i).takeIf { it >= 0 } }
                .minOrNull() ?: break
            val name = if (source.startsWith("LaunchedEffect", start)) "LaunchedEffect" else "DisposableEffect"
            i = start + name.length
            val before = source.getOrNull(start - 1)
            if (before != null && (before.isLetterOrDigit() || before == '_' || before == '.')) continue
            // 第一个 `(` 是 key 实参，第二个才是尾随 lambda：交给同一个解析器。
            val paren = source.indexOf('(', i)
            if (paren < 0) break
            val afterArgs = matchingParen(source, paren) ?: break
            val brace = trailingLambdaStart(source, afterArgs + 1) ?: { i = afterArgs + 1; null }()
                ?: continue
            val end = matchingBrace(source, brace) ?: break
            out += EffectBlock(
                line = source.substring(0, brace).count { it == '\n' } + 1,
                text = source.substring(brace + 1, end),
            )
            i = end
        }
        return out
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
