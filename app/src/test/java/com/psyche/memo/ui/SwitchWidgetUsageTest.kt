package com.psyche.memo.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 用户 2026-09-14：「这个 skill 这个开关怎么没有接入设置里面的触觉反馈呀」——
 * 我在技能 tab 里用了裸的 M3 `Switch`，而全站的开关都是 [IosSwitch]
 * （它读 `LocalHapticsSettings`，按设置里的「触觉反馈」开关决定要不要震）。
 *
 * 所以这条扫描源码，禁止 UI 代码直接引用 `androidx.compose.material3.Switch`：
 * 开关一律走 [IosSwitch]。`IosWidgets.kt` 是定义处，不受此限。
 */
class SwitchWidgetUsageTest {

    private val uiDir = File("src/main/java/com/psyche/memo")

    @Test
    fun `ui code reaches the test working dir`() {
        assertTrue(
            "expected ${uiDir.absolutePath} to exist — check the Gradle test working dir",
            uiDir.isDirectory,
        )
    }

    @Test
    fun `no ui file uses the raw material switch`() {
        val forbidden = "androidx.compose.material3.Switch"
        val offenders = uiDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "IosWidgets.kt" }
            .filter { file -> file.readText().contains(forbidden) }
            .map { it.relativeTo(uiDir).invariantSeparatorsPath }
            .sorted()
            .toList()

        assertTrue(
            "开关必须用 IosSwitch（它才读触觉设置），不要裸 M3 Switch：" + offenders,
            offenders.isEmpty(),
        )
    }
}
