package com.psyche.memo.provider.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 沙箱工作区工具面的纯逻辑（RikkaHub `WorkspaceTools.kt` 的移植）。
 *
 * 重点覆盖三类容易错的地方：**审批默认值与可写安全区**（判错就会静默放行越界写入）、
 * **rootfs 元数据的 `\0` 解析**、以及**编辑的三级替换阶梯**。
 */
class WorkspaceToolsTest {

    private fun args(vararg pairs: Pair<String, Any>): JsonObject = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v)
                is Boolean -> put(k, v)
                is Int -> put(k, v)
                else -> error("unsupported $v")
            }
        }
    }

    /**
     * 每一处「工具名清单」都要跟着 [WorkspaceTools.ALL_TOOL_NAMES] 走：定义表、提示词块。
     * 漏一个的后果是「模型根本不知道有这个工具」—— 位置工具就栽过（用户 2026-09-21
     * 「为什么大模型说没有呀」）。审批表那一处随审批体系一起删除（2026-09-25）。
     */
    @Test
    fun everyToolNameIsCoveredByDefinitions() {
        val definitionNames = WorkspaceTools.catalogDefinitions().map { it.name }
        assertEquals(WorkspaceTools.ALL_TOOL_NAMES.size, definitionNames.size)
        assertTrue(definitionNames.containsAll(WorkspaceTools.ALL_TOOL_NAMES))
        WorkspaceTools.ALL_TOOL_NAMES.forEach {
            assertTrue("$it 缺少参数 schema", WorkspaceTools.catalogDefinitions().any { spec -> spec.name == it })
        }
    }

    @Test
    fun absolutePathRequiresRootfsAbsolutePaths() {
        assertEquals("/workspace/a.txt", WorkspaceTools.absolutePath(args("path" to "/workspace/a.txt"), "path"))
        // 反斜杠归一化（模型偶尔给 Windows 风格）。
        assertEquals("/a/b", WorkspaceTools.absolutePath(args("path" to "\\a\\b"), "path"))

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceTools.absolutePath(args("path" to "relative/x"), "path")
        }
        // 缺参数走的是上游的 error()（IllegalStateException），路径形状问题才是 require。
        assertThrows(IllegalStateException::class.java) {
            WorkspaceTools.absolutePath(buildJsonObject { }, "path")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceTools.absolutePath(args("path" to "/a\u0000b"), "path")
        }
    }

    // ---- 定义 ----

    @Test
    fun noWorkspaceBindingMeansNoTools() {
        assertTrue(WorkspaceTools.buildDefinitions(null).isEmpty())
        assertTrue(WorkspaceTools.buildDefinitions("   ").isEmpty())
    }

    @Test
    fun boundWorkspaceExposesEveryTool() {
        val defs = WorkspaceTools.buildDefinitions("ws-1")
        assertEquals(
            listOf(
                WorkspaceTools.READ_FILE,
                WorkspaceTools.WRITE_FILE,
                WorkspaceTools.EDIT_FILE,
                WorkspaceTools.LIST,
                WorkspaceTools.GLOB,
                WorkspaceTools.GREP,
                WorkspaceTools.SHELL,
            ),
            defs.map { it.name },
        )
        assertTrue(defs.all { it.description.isNotBlank() })
        // 描述里不能有换行（上游 trimIndent().replace("\n", " ")）。
        assertTrue(defs.none { it.description.contains('\n') })
        assertTrue(defs.first { it.name == WorkspaceTools.SHELL }.description.contains("/workspace"))
    }

    @Test
    fun shellDescriptionAndSchemaMentionTheDefaultCwd() {
        val defs = WorkspaceTools.buildDefinitions("ws-1", cwd = "/workspace/proj")
        val shell = defs.first { it.name == WorkspaceTools.SHELL }
        assertTrue("默认 cwd 应当出现在描述里", shell.description.contains("proj"))
        assertTrue(shell.inputSchemaJson.contains("proj"))
        assertTrue(shell.inputSchemaJson.contains("600"))
    }

    @Test
    fun shellCwdStripsTheWorkspacePrefix() {
        assertEquals("proj", WorkspaceTools.shellCwd("/workspace/proj"))
        assertEquals("", WorkspaceTools.shellCwd("/workspace"))
        assertEquals(null, WorkspaceTools.shellCwd(null))
    }

    // ---- rootfs 元数据 ----

    @Test
    fun parsesNulSeparatedEntryMetadata() {
        val stdout = "f\u000012\u00001700000000\u0000/workspace/a.txt\u0000"
        val entry = WorkspaceTools.parseRootfsEntries(stdout).single()
        assertEquals("/workspace/a.txt", entry.path)
        assertEquals("a.txt", entry.name)
        assertEquals(12L, entry.sizeBytes)
        assertEquals(1_700_000_000_000L, entry.updatedAt)
        assertFalse(entry.isDirectory)
    }

    @Test
    fun parsesDirectoriesAndTrailingSlashNames() {
        val entry = WorkspaceTools.parseRootfsEntries("d\u00000\u000010\u0000/workspace/dir/\u0000").single()
        assertTrue(entry.isDirectory)
        assertEquals("dir", entry.name)
    }

    @Test
    fun rejectsMalformedMetadata() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceTools.parseRootfsEntries("f\u000012\u00001\u0000")
        }
        assertThrows(IllegalStateException::class.java) {
            WorkspaceTools.parseRootfsEntries("f\u0000notanumber\u00001\u0000/p\u0000")
        }
    }

    @Test
    fun entryJsonCarriesEveryField() {
        val json = WorkspaceTools.entryJson(
            com.psyche.memo.workspace.WorkspaceFileEntry("/w/a", "a", false, 3, 9),
        )
        val obj = Json.parseToJsonElement(json).jsonObject
        assertEquals("/w/a", obj["path"]!!.toString().trim('"'))
        assertEquals("false", obj["isDirectory"]!!.toString())
        assertEquals("3", obj["sizeBytes"]!!.toString())
    }

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'a b'", WorkspaceTools.shellQuote("a b"))
        assertEquals("'a'\"'\"'b'", WorkspaceTools.shellQuote("a'b"))
    }

    @Test
    fun timeoutDefaultsAndClamps() {
        assertEquals(
            com.psyche.memo.workspace.WorkspaceManager.DEFAULT_COMMAND_TIMEOUT_MS,
            WorkspaceTools.timeoutMillis(args("command" to "ls")),
        )
        assertEquals(5_000L, WorkspaceTools.timeoutMillis(args("timeout" to 5)))
        assertEquals(600_000L, WorkspaceTools.timeoutMillis(args("timeout" to 99_999)))
        assertEquals(1_000L, WorkspaceTools.timeoutMillis(args("timeout" to 0)))
    }

    // ---- 只读搜索工具：list / glob / grep（本工程新增，上游没有） ----

    @Test
    fun searchToolsDefaultToTheWorkspaceRoot() {
        assertEquals(
            WorkspaceTools.DEFAULT_SEARCH_ROOT,
            WorkspaceTools.absolutePathOrDefault(buildJsonObject { }, "path", WorkspaceTools.DEFAULT_SEARCH_ROOT),
        )
        assertEquals(
            WorkspaceTools.DEFAULT_SEARCH_ROOT,
            WorkspaceTools.absolutePathOrDefault(args("path" to "  "), "path", WorkspaceTools.DEFAULT_SEARCH_ROOT),
        )
        assertEquals(
            "/workspace/src",
            WorkspaceTools.absolutePathOrDefault(args("path" to "/workspace/src"), "path", WorkspaceTools.DEFAULT_SEARCH_ROOT),
        )
        // 相对路径仍然拒绝 —— 搜索根也必须在 rootfs 内。
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceTools.absolutePathOrDefault(args("path" to "src"), "path", WorkspaceTools.DEFAULT_SEARCH_ROOT)
        }
    }

    @Test
    fun globPatternsResolveAgainstTheSearchRoot() {
        assertEquals("/workspace/*.md", WorkspaceTools.globExpression("/workspace", "*.md"))
        assertEquals("/workspace/src/**/*.kt", WorkspaceTools.globExpression("/workspace", "src/**/*.kt"))
        // 已经是绝对路径就原样用（模型经常照着 /workspace/... 抄）；`./` 前缀当相对处理。
        assertEquals("/workspace/a.md", WorkspaceTools.globExpression("/workspace", "/workspace/a.md"))
        assertEquals("/workspace/a.md", WorkspaceTools.globExpression("/workspace", "./a.md"))
        // 根带尾斜杠时不能拼出 `//`。
        assertEquals("/workspace/a.md", WorkspaceTools.globExpression("/workspace/", "a.md"))
    }

    @Test
    fun listCommandGuardsAgainstNonDirectories() {
        val command = WorkspaceTools.listCommand("/workspace/notes")
        assertTrue(command.startsWith("dir='/workspace/notes'"))
        assertTrue(command.contains("[ ! -d \"\$dir\" ]"))
        assertTrue(command.contains("-mindepth 1 -maxdepth 1"))
        // 路径要过 shell 引号，含空格的目录名不能被拆成两个参数。
        assertTrue(
            WorkspaceTools.listCommand("/workspace/my notes").contains("'/workspace/my notes'"),
        )
    }

    @Test
    fun grepCommandCarriesTheRightFlags() {
        val plain = WorkspaceTools.grepCommand("/workspace", "TODO", glob = null, ignoreCase = false)
        assertTrue(plain.contains("grep -rInEZ"))
        assertTrue(plain.contains("-- 'TODO'"))
        assertFalse(plain.contains("--include="))
        assertFalse(plain.contains("-rInEZi"))

        val filtered = WorkspaceTools.grepCommand("/workspace", "todo", glob = "*.kt", ignoreCase = true)
        assertTrue(filtered.contains("grep -rInEZi"))
        assertTrue(filtered.contains("--include='*.kt'"))
        // 正则里的引号/空格不能让 shell 拆词。
        assertTrue(
            WorkspaceTools.grepCommand("/workspace", "a 'b' c", glob = null, ignoreCase = false)
                .contains("'a '\"'\"'b'\"'\"' c'"),
        )
    }

    /**
     * `find -printf '%y\0%s\0%T@\0%p\0'`：`%T@` 带小数（秒.纳秒）要取整，输出被 runner
     * 按字节截断时末尾的半条记录要丢掉而不是报错。
     */
    @Test
    fun parsesPrintfEntriesWithFractionalMtimeAndPartialTail() {
        val stdout = "d\u00004096\u00001700000000.1234567890\u0000/workspace/sub\u0000" +
            "f\u000012\u00001700000001.0000000000\u0000/workspace/a.txt\u0000"
        val entries = WorkspaceTools.parsePrintfEntries(stdout)
        assertEquals(2, entries.size)
        assertEquals("/workspace/sub", entries[0].path)
        assertEquals("sub", entries[0].name)
        assertTrue(entries[0].isDirectory)
        assertEquals(1_700_000_000_000L, entries[0].updatedAt)
        assertEquals("a.txt", entries[1].name)
        assertEquals(12L, entries[1].sizeBytes)

        val truncated = WorkspaceTools.parsePrintfEntries(stdout + "f\u000012\u0000170000")
        assertEquals(2, truncated.size)
        assertEquals(0, WorkspaceTools.parsePrintfEntries("").size)
    }

    @Test
    fun parsesGrepMatchesByNulAndFirstColon() {
        // `-Z` 把文件名和 `line:text` 用 NUL 隔开 —— 文件名里有冒号也拆不错。
        val stdout = "/workspace/a:b.kt\u000012:val x = 1\n/workspace/b.md\u00003:hello: world\n"
        val matches = WorkspaceTools.parseGrepMatches(stdout)
        assertEquals(2, matches.size)
        assertEquals("/workspace/a:b.kt", matches[0].path)
        assertEquals(12, matches[0].line)
        assertEquals("val x = 1", matches[0].text)
        assertEquals("hello: world", matches[1].text)
        // 没有匹配就是空表，不是异常。
        assertEquals(0, WorkspaceTools.parseGrepMatches("").size)
    }

    @Test
    fun grepMatchesAreCappedAndLongLinesTrimmed() {
        val longLine = "x".repeat(2_000)
        val stdout = (1..400).joinToString("") { "/w/f.txt\u0000$it:$longLine\n" }
        val matches = WorkspaceTools.parseGrepMatches(stdout)
        assertEquals(200, matches.size)
        assertEquals(500, matches.first().text.length)
    }

    @Test
    fun searchToolsExposeTheirOwnSchemas() {
        val list = Json.parseToJsonElement(WorkspaceTools.listParametersJson()).jsonObject
        assertNull(list["required"])
        val glob = Json.parseToJsonElement(WorkspaceTools.globParametersJson()).jsonObject
        assertTrue(glob["required"].toString().contains("pattern"))
        val grep = Json.parseToJsonElement(WorkspaceTools.grepParametersJson()).jsonObject
        assertTrue(grep["required"].toString().contains("pattern"))
        val props = grep["properties"]!!.jsonObject
        assertTrue(props.containsKey("glob"))
        assertTrue(props.containsKey("ignore_case"))
        // 三个只读工具的 path 都是可选的（缺省 = /workspace）。
        listOf(list, glob, grep).forEach {
            assertTrue(it["properties"]!!.jsonObject.containsKey("path"))
        }
    }

    // ---- 替换阶梯 ----

    @Test
    fun exactReplacementRequiresUniquenessUnlessReplaceAll() {
        val single = WorkspaceTools.replaceText("a b c", "b", "B", replaceAll = false)
        assertEquals("a B c", single.updated)
        assertEquals(1, single.replacements)
        assertEquals(ExactReplacer.name, single.strategy)

        val all = WorkspaceTools.replaceText("b b b", "b", "x", replaceAll = true)
        assertEquals("x x x", all.updated)
        assertEquals(3, all.replacements)

        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceTools.replaceText("b b", "b", "x", replaceAll = false)
        }
    }

    /**
     * 精确匹配失败时退化到「逐行 trim 后相等」——模型给的行首缩进常常对不上。
     * 这里 oldText 跨行且内层缩进与原文不一致，所以精确匹配必然落空。
     */
    @Test
    fun fallsBackToWhitespaceTolerantLineMatching() {
        val original = "fun f() {\n    a()\n    b()\n}\n"
        val result = WorkspaceTools.replaceText(
            original = original,
            oldText = "a()\nb()",          // 少了内层缩进 ⇒ 不是原文的子串
            newText = "c()\n    d()",
            replaceAll = false,
        )
        assertEquals(LineTrimmedReplacer.name, result.strategy)
        assertEquals("fun f() {\n    c()\n        d()\n}\n", result.updated)
    }

    @Test
    fun whitespaceTolerantMatchingAlsoHonoursReplaceAll() {
        val original = "  x\n  y\n  x\n  y\n"
        val result = WorkspaceTools.replaceText(
            original = original,
            oldText = "x\ny",
            newText = "X\nY",
            replaceAll = true,
        )
        assertEquals(LineTrimmedReplacer.name, result.strategy)
        assertEquals("  X\n  Y\n  X\n  Y\n", result.updated)
        assertEquals(2, result.replacements)
    }

    @Test
    fun noMatchAndEmptyNeedleAreErrors() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceTools.replaceText("abc", "zzz", "y", replaceAll = false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceTools.replaceText("abc", "", "y", replaceAll = false)
        }
    }

    @Test
    fun oversizedReadsAreRefusedWithAHint() {
        WorkspaceTools.requireReadableSize("/w/a", 1024)
        val error = assertThrows(IllegalArgumentException::class.java) {
            WorkspaceTools.requireReadableSize("/w/big", 9L * 1024 * 1024)
        }
        assertTrue(error.message!!.contains("head, tail, or grep"))
    }

    // ---- 提示词注入 ----

    @Test
    fun promptBlockTellsTheModelWhereEverythingIsMounted() {
        val block = WorkspaceTools.buildSystemPromptBlock("Scratch")
        assertTrue(block.startsWith("<workspace>"))
        assertTrue(block.endsWith("</workspace>"))
        assertTrue(block.contains("Scratch"))
        // 三个挂载点必须都讲清楚，否则模型只能瞎试路径。
        assertTrue(block.contains("`/workspace`"))
        assertTrue(block.contains("`/skills`"))
        assertTrue(block.contains("`/upload`"))
        // /upload 必须写明只读 —— 模型改掉用户上传的原件是不可接受的。
        assertTrue(block.contains("READ-ONLY"))
        // 四个工具名都要点到。
        WorkspaceTools.ALL_TOOL_NAMES.forEach { assertTrue("提示词应当提到 $it", block.contains(it)) }
    }

    @Test
    fun promptBlockOnlyMentionsCwdWhenThereIsOne() {
        assertFalse(WorkspaceTools.buildSystemPromptBlock("W").contains("Current working directory"))
        assertTrue(
            WorkspaceTools.buildSystemPromptBlock("W", cwd = "proj")
                .contains("Current working directory: `proj`"),
        )
    }

    /**
     * 内存/CPU 那句是**本工程新增**（上游提示词没有）：沙箱与手机共享内存，重运行时
     * 容易 OOM 起不来 —— 真机上模型为这件事白烧了好几轮（§5.16①）。这句不能再掉。
     */
    @Test
    fun promptBlockWarnsThatHeavyRuntimesMayRunOutOfMemory() {
        val block = WorkspaceTools.buildSystemPromptBlock("Scratch")
        assertTrue(block.contains("shared with the host device"))
        assertTrue(block.contains("out-of-memory"))
        assertTrue(block.contains("Python, Node"))
    }

    // ---- 第三级 block_anchor + 缩进重排（上游 TextReplacers.kt 的细节）----

    /**
     * old_text ≥3 行时，只要**首尾两行**对得上就认定是同一块 —— 中间行可以不一致。
     * 前两级都落空才会走到这里。
     */
    @Test
    fun blockAnchorMatchesOnFirstAndLastLineOnly() {
        val original = "fun f() {\n    if (x) {\n        body()\n    }\n}\n"
        val result = WorkspaceTools.replaceText(
            original = original,
            oldText = "if (x) {\n    totally different middle\n}",
            newText = "if (x) {\n    changed()\n}",
            replaceAll = false,
        )
        assertEquals(BlockAnchorReplacer.name, result.strategy)
        assertEquals("fun f() {\n    if (x) {\n        changed()\n    }\n}\n", result.updated)
    }

    /**
     * 少于 3 行、或首尾是空行时 block_anchor 不适用（否则会命中任意空白区间）。
     * `isApplicable` 是 protected（照上游），所以这里用 findMatches 验行为。
     */
    @Test
    fun blockAnchorNeedsThreeLinesWithNonEmptyAnchors() {
        val content = "if (x) {\n    junk\n}\n"
        assertTrue(BlockAnchorReplacer.findMatches(content, "junk\n}", "y").isEmpty())
        assertTrue(BlockAnchorReplacer.findMatches(content, "if (x) {\nmiddle\n}", "y").isNotEmpty())
        assertTrue(BlockAnchorReplacer.findMatches(content, "\njunk\n", "y").isEmpty())
    }

    // ---- 读图片（工具结果里的 image 部分） ----

    @Test
    fun imagePathsAreDetectedByExtensionOnly() {
        assertTrue(WorkspaceTools.isImagePath("/workspace/shot.PNG"))
        assertTrue(WorkspaceTools.isImagePath("/workspace/a/b/photo.jpeg"))
        assertTrue(WorkspaceTools.isImagePath("/workspace/icon.webp"))
        assertFalse(WorkspaceTools.isImagePath("/workspace/notes.md"))
        assertFalse(WorkspaceTools.isImagePath("/workspace/archive.zip"))
        // 没有扩展名（Makefile 这类）不当图片，否则二进制会被当成图片发给模型。
        assertFalse(WorkspaceTools.isImagePath("/workspace/Makefile"))
    }

    @Test
    fun imageReadOutcomeCarriesBytesAndDescribesTheFile() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val outcome = WorkspaceTools.imageReadOutcome("/workspace/pics/shot.png", bytes)

        // 正文只留路径与描述（1:1 上游 readImageInRootfs 的文本部分）——不能把二进制当文本塞进去。
        val json = Json.parseToJsonElement(outcome.json).jsonObject
        assertEquals("/workspace/pics/shot.png", json["path"].toString().trim('"'))
        assertEquals("Image file read successfully", json["description"].toString().trim('"'))
        assertFalse(json.containsKey("text"))
        // 字节与文件名交给调用方落盘。
        assertEquals("shot.png", outcome.image!!.name)
        assertTrue(bytes.contentEquals(outcome.image!!.bytes))
    }

    @Test
    fun nonImageReadsCarryNoAttachment() {
        val outcome = WorkspaceTools.Outcome.Success("{}")
        assertEquals(null, outcome.image)
    }
}