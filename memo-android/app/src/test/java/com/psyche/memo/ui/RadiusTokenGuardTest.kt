package com.psyche.memo.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 圆角收口守卫：一级容器/嵌套块/小控件/胶囊必须走 [MemoRadius]（CARD 20 / INNER 16 /
 * SMALL 10 / PILL 999），不许再回退成散落的字面 dp —— 2026-09-19 用
 * `tools/radius_token_sweep.py` 收了 433 处，正是为了让圆角能单点调。
 *
 * 照 [ShapePercentRegressionTest] 的路子扫主源码。**例外按「文件 → 值 → 次数」登记**
 * （不按行号，行号会漂），并必须写清为什么不进档位；新增例外要先想清楚是不是在绕过体系。
 */
class RadiusTokenGuardTest {

    private val sourceRoots = listOf(
        File("src/main/java"),
        File("../core/ui/src/main/java"),
    )

    /**
     * 有意保留字面量的角落：三档/四档都套不上，或压根不是"容器"。
     *  - r0  = 明确要直角
     *  - r2/r3 = 缩略图与统计条这类微圆角，进 SMALL(10) 会变糊
     *  - r4  = markdown 行内代码块的底衬
     *  - r50 = 图片查看器/历史搜索框这类"够圆就行"的伪胶囊，改动会影响既有视觉
     */
    private val allowed = mapOf(
        "com/psyche/memo/ui/backup/RemoteBackupListSheet.kt" to mapOf("2" to 1),
        "com/psyche/memo/ui/chat/ImageViewer.kt" to mapOf("50" to 2),
        "com/psyche/memo/ui/chat/ToolCallCard.kt" to mapOf("2" to 1),
        "com/psyche/memo/ui/ChatHistoryScreen.kt" to mapOf("50" to 1),
        "com/psyche/memo/ui/MiniMapSheet.kt" to mapOf("2" to 1),
        "com/psyche/memo/ui/ModelDetailSheet.kt" to mapOf("0" to 1),
        "com/psyche/memo/ui/StatsScreen.kt" to mapOf("3" to 3),
        "com/psyche/memo/ui/StorageSpace.kt" to mapOf("50" to 1),
        "com/psyche/memo/ui/ToolSchemaSettingsScreen.kt" to mapOf("0" to 1),
        "com/psyche/memo/ui/markdown/MarkdownRenderer.kt" to mapOf("4" to 1),
    )

    // 整段匹配调用，兼容位置参数式与 sheet 的 topStart/topEnd 命名参数式。
    private val callRe = Regex("""RoundedCornerShape\([^()]*\)""")
    private val literalDpRe = Regex("""(\d+(?:\.\d+)?)\.dp""")

    @Test
    fun literalCornerRadiiStayWithinAllowlist() {
        val offenders = mutableListOf<String>()

        for (root in sourceRoots) {
            if (!root.exists()) continue
            val prefix = if (root.path.startsWith("..")) "../" else ""
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    val rel = file.path
                        .substringAfter("java${File.separator}")
                        .replace(File.separatorChar, '/')
                    val seen = mutableMapOf<String, Int>()
                    callRe.findAll(file.readText()).forEach { match ->
                        literalDpRe.findAll(match.value).forEach { num ->
                            val v = num.groupValues[1].removeSuffix(".0")
                            seen[v] = (seen[v] ?: 0) + 1
                        }
                    }
                    val quota = allowed.getOrDefault(rel, emptyMap()).toMutableMap()
                    seen.forEach { (value, count) ->
                        val left = (quota[value] ?: 0) - count
                        quota[value] = left
                        if (left < 0) {
                            offenders += "$prefix$rel: r$value 用了 ${count} 处，登记的是 ${quota[value]?.plus(count)} 处"
                        }
                    }
                }
        }

        assertTrue(
            "圆角必须走 MemoRadius（CARD_DP/INNER_DP/SMALL_DP/PILL_DP）。" +
                "新出现的字面 dp 圆角：\n" + offenders.joinToString("\n") +
                "\n确属档位外形状，再把「文件 → 值 → 次数」登记进 allowed 并写明理由。",
            offenders.isEmpty(),
        )
    }
}
