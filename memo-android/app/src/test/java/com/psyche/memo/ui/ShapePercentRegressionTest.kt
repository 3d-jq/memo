package com.psyche.memo.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 防闪退回归：Compose 的 `RoundedCornerShape(Int)` / `RoundedCornerShape(Float)`
 * 重载是**百分比**（0..100），不是 dp。写 `RoundedCornerShape(999)` 想表达
 * "全圆角胶囊" 会在组合期抛
 * `IllegalArgumentException: The percent should be in the range of [0, 100]`
 * —— 已导致语言选择 sheet 与消息 More sheet 点击即崩溃（2026-09-06）。
 *
 * 这里扫描 app/core 主源码，禁止不带单位的数字型圆角参数。
 */
class ShapePercentRegressionTest {

    private val sourceRoots = listOf(
        File("src/main/java"),
        File("../core/ui/src/main/java"),
        File("../core/data/src/main/java"),
    )

    // 形如 RoundedCornerShape(999) / (50) / (12.5) —— 没有 .dp/.sp 单位。
    private val badNumber = Regex("""RoundedCornerShape\(\s*[0-9]+(\.[0-9]+)?f?\s*\)""")

    @Test
    fun noUnitlessRoundedCornerShapeInSources() {
        val offenders = mutableListOf<String>()
        for (root in sourceRoots) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .forEach { file ->
                    file.readLines().forEachIndexed { index, line ->
                        if (badNumber.containsMatchIn(line)) {
                            offenders += "${file.path}:${index + 1}: ${line.trim()}"
                        }
                    }
                }
        }
        assertTrue(
            "RoundedCornerShape 的 Int/Float 重载是百分比(0..100)，必须带 dp 单位。可疑处：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
