package com.psyche.memo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 守着根 `build.gradle.kts` 里那份 "CI 跳过" 名单：**每个名字都必须对应源码里真实存在的测试类**。
 *
 * 为什么需要：那份名单一旦因为重命名/删除而失效，`excludeTestsMatching` 会静默匹配不到任何东西
 * —— CI 看起来"跳过了一堆用例"，实际上一个都没跳，而名单里写的理由会变成假话。这条守卫把
 * "名单与源码对得上"变成机械可查的。（反过来如果某天 CI 又偶发红，先看这里没红，再看名单里
 * 是不是少登记了类。）
 */
class CiSkipListTest {

    @Test
    fun everyClassNameInTheCiSkipListExistsInTestSources() {
        val buildFile = File("../build.gradle.kts")
        assertTrue("找不到根构建脚本：${buildFile.absolutePath}", buildFile.isFile)

        val block = Regex("""val ciSkippedTests = listOf\(([\s\S]*?)\)""")
            .find(buildFile.readText())
            ?.groupValues?.get(1)
            ?: throw AssertionError("根 build.gradle.kts 里找不到 ciSkippedTests 名单")

        val names = Regex(""""([\w.]+Test)"""").findAll(block).map { it.groupValues[1] }.toList()
        assertTrue("名单是空的，那这个开关就没有意义了", names.isNotEmpty())

        val missing = names.filter { fqcn ->
            val fileName = fqcn.substringAfterLast('.') + ".kt"
            File("src/test/java").walkTopDown().none { it.isFile && it.name == fileName }
        }
        assertTrue(
            "这些名字在 app/src/test 里找不到对应文件（改名了？还是删了？）：$missing\n" +
                "请同步更新根 build.gradle.kts 的 ciSkippedTests。",
            missing.isEmpty(),
        )
    }
}
