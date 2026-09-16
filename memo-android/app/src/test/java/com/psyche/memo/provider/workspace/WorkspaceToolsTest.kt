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

    // ---- 审批 ----

    @Test
    fun onlyShellNeedsApprovalByDefault() {
        assertTrue(WorkspaceTools.DEFAULT_APPROVALS.getValue(WorkspaceTools.SHELL))
        assertFalse(WorkspaceTools.resolveApproval(WorkspaceTools.READ_FILE, emptyMap()))
        assertFalse(WorkspaceTools.resolveApproval(WorkspaceTools.WRITE_FILE, emptyMap()))
        assertFalse(WorkspaceTools.resolveApproval(WorkspaceTools.EDIT_FILE, emptyMap()))
        assertTrue(WorkspaceTools.resolveApproval(WorkspaceTools.SHELL, emptyMap()))
        // 未知工具默认不放行。
        assertFalse(WorkspaceTools.resolveApproval("nope", emptyMap()))
    }

    @Test
    fun workspaceOverridesBeatTheDefaults() {
        assertFalse(WorkspaceTools.resolveApproval(WorkspaceTools.SHELL, mapOf(WorkspaceTools.SHELL to false)))
        assertTrue(WorkspaceTools.resolveApproval(WorkspaceTools.READ_FILE, mapOf(WorkspaceTools.READ_FILE to true)))
    }

    // ---- 路径边界 ----

    @Test
    fun onlyWorkspaceAndTmpAreWritableRoots() {
        listOf("/workspace", "/workspace/a/b.txt", "/tmp", "/tmp/x", "/workspace/").forEach {
            assertFalse("$it 应当在可写安全区内", WorkspaceTools.isOutsideWritableRoots(it))
        }
        listOf("/", "/etc/passwd", "/usr", "/workspaceX", "/tmpfoo").forEach {
            assertTrue("$it 应当在可写安全区外", WorkspaceTools.isOutsideWritableRoots(it))
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

    /**
     * 路径判定的三态：
     * - 能解析出绝对路径 → 按前缀判内外；
     * - 解析不出（缺 `path`、相对路径、带 `\0`）→ `null`，调用方**不弹审批**，直接让
     *   工具报参数错误（上游这里返回 true ⇒ 先弹一次审批、点完只收到「path is required」）。
     */
    @Test
    fun pathOutsideWritableRootsIsTriState() {
        assertNull(WorkspaceTools.pathOutsideWritableRoots(buildJsonObject { }, "path"))
        assertNull(WorkspaceTools.pathOutsideWritableRoots(args("path" to "relative"), "path"))
        assertEquals(true, WorkspaceTools.pathOutsideWritableRoots(args("path" to "/etc/x"), "path"))
        assertEquals(false, WorkspaceTools.pathOutsideWritableRoots(args("path" to "/workspace/x"), "path"))
    }

    /** 闸门判据只认 `== true`；`null`（参数不可用）不进审批。 */
    @Test
    fun unusablePathArgumentsDoNotAskForApproval() {
        fun gate(args: JsonObject) = WorkspaceTools.pathOutsideWritableRoots(args, "path") == true
        assertFalse(gate(buildJsonObject { }))
        assertFalse(gate(args("path" to "relative/x")))
        assertTrue(gate(args("path" to "/etc/passwd")))
        assertFalse(gate(args("path" to "/workspace/a.txt")))
    }

    // ---- 定义 ----

    @Test
    fun noWorkspaceBindingMeansNoTools() {
        assertTrue(WorkspaceTools.buildDefinitions(null).isEmpty())
        assertTrue(WorkspaceTools.buildDefinitions("   ").isEmpty())
    }

    @Test
    fun boundWorkspaceExposesTheFourTools() {
        val defs = WorkspaceTools.buildDefinitions("ws-1")
        assertEquals(
            listOf(
                WorkspaceTools.READ_FILE,
                WorkspaceTools.WRITE_FILE,
                WorkspaceTools.EDIT_FILE,
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