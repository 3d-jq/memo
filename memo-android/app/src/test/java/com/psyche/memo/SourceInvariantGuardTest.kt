package com.psyche.memo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * C5 / C6 两条源码不变式的 **JUnit 版**（2026-09-26）。
 *
 * 来历：`tools/invariant_checks.sh` 里的 C5（禁用系统 Toast）/ C6（改动文件无行尾空白、
 * 以一个换行结尾）原本只在有 bash 的环境里跑，而开发机是 Windows、CI 的 workflow 也没接
 * 这两步 —— 结果是规则只靠人记。搬成单测后，`testDebugUnitTest`（CI 每次必跑）就是门禁。
 *
 * 与 shell 版的差别（刻意）：
 * - 扫描范围从「git 改动文件」放宽到**全部主源码**：存量已在同一批清理（12 个文件），
 *   硬规则 = 0 才能当回归网用；哪天真要放过存量，照 `RadiusTokenGuardTest` 改登记表。
 * - 规则本身与 shell 版逐条一致：C5 禁 `android.widget.Toast`；C6 禁行尾空白、
 *   禁 EOF 多空行、禁缺结尾换行。
 *
 * 按 `ENGINEERING_HARNESS` §4：新护栏必须附反例，证明喂它坏输入会红 —— 见
 * [guardRejectsViolations]。
 */
class SourceInvariantGuardTest {

    private val sourceRoots = listOf(
        File("src/main/java"),
        File("../core/common/src/main/java"),
        File("../core/ui/src/main/java"),
        File("../core/highlight/src/main/java"),
        File("../core/data/src/main/java"),
        File("../core/llm/src/main/java"),
        File("../core/workspace/src/main/java"),
    )

    /** C5：统一走 `SnackbarManager` / `AppSnackBarOverlay`（Dialog 内自挂 overlay）。 */
    private val systemToastRe = Regex("""android\.widget\.Toast""")

    private fun scan(roots: List<File>): List<String> {
        val offenders = mutableListOf<String>()
        for (root in roots) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val rel = file.path.replace(File.separatorChar, '/')
                    val text = file.readText()
                    text.lineSequence().forEachIndexed { index, line ->
                        if (systemToastRe.containsMatchIn(line)) {
                            offenders += "C5 $rel:${index + 1} 用了系统 Toast"
                        }
                        if (line != line.trimEnd(' ', '\t')) {
                            offenders += "C6 $rel:${index + 1} 行尾空白"
                        }
                    }
                    // C6：必须以恰好一个换行结尾（不缺、不多）。
                    if (!text.endsWith("\n")) {
                        offenders += "C6 $rel 缺结尾换行"
                    } else if (text.endsWith("\n\n")) {
                        offenders += "C6 $rel 结尾多了空行"
                    }
                }
        }
        return offenders
    }

    @Test
    fun mainSourcesStayFreeOfSystemToastAndWhitespaceDebt() {
        val offenders = scan(sourceRoots)
        assertTrue(
            "源码不变式被破坏（C5 禁系统 Toast；C6 禁行尾空白 / EOF 空行 / 缺结尾换行）：\n" +
                offenders.joinToString("\n") +
                "\n提示统一走 SnackbarManager；空白问题直接清掉，別往登记表里躲。",
            offenders.isEmpty(),
        )
    }

    /**
     * 反例自证（ENGINEERING_HARNESS §4）：同一条扫描逻辑，喂一个**故意写坏**的文件必须变红。
     * 没有这条，「守卫永远绿」可能只是它压根不会数数。
     */
    @Test
    fun guardRejectsViolations() {
        val dir = createTempDir("source-invariant-bad").apply { deleteOnExit() }
        val bad = File(dir, "Bad.kt").apply {
            writeText(
                "package x\n" +
                    "fun a() {\n" +
                    "    android.widget.Toast.makeText(c, \"m\", 1).show() \n" + // C5 + 行尾空白
                    "}\n\n", // EOF 多空行
            )
        }
        val noFinalNewline = File(dir, "NoNewline.kt").apply {
            writeText("package x") // 缺结尾换行
        }
        val offenders = scan(listOf(dir))
        assertTrue(
            "反例必须被判红，否则守卫本身失灵。实际输出：$offenders",
            offenders.any { it.contains("C5") && it.contains("Bad.kt:3") } &&
                offenders.any { it.contains("C6") && it.contains("Bad.kt:3") } &&
                offenders.any { it.contains("C6") && it.contains("Bad.kt") && it.contains("结尾多了空行") } &&
                offenders.any { it.contains("C6") && it.contains("NoNewline.kt") && it.contains("缺结尾换行") },
        )
        // 干净文件不许误报
        val cleanDir = createTempDir("source-invariant-clean").apply { deleteOnExit() }
        File(cleanDir, "Clean.kt").writeText("package x\nfun a() {}\n")
        assertTrue("干净文件被误报：${scan(listOf(cleanDir))}", scan(listOf(cleanDir)).isEmpty())
        bad.delete(); noFinalNewline.delete(); dir.delete(); cleanDir.delete()
    }
}
