package com.psyche.memo.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * VoiceWaveform 布局回归 ——「波形出现但永远不动」（用户 2026-09-16 二次实测）的根因：
 * Canvas 本体是 Spacer，调用点只给 fillMaxWidth 时高度约束宽松（min=0）→ 测量高度 0
 * → maxH=0 → 所有条贴 2px 最小值。上游靠 AnimatedSwitcher 的 StackFit.expand 撑满
 * 32dp；组件内现以 WAVE_SLOT_HEIGHT_DP 兜底。本测试锁定「宽松约束下槽高必须是 32dp」，
 * 兜底一旦被删这里直接红。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-160dpi")
class VoiceWaveformLayoutTest {

    @get:org.junit.Rule
    val compose = createComposeRule()

    @Test
    fun `waveform has real slot height under loose height constraints`() {
        compose.setContent {
            MaterialTheme {
                // Column 里不约束高度 —— 复现调用点「只有 fillMaxWidth」的宽松环境。
                Column {
                    VoiceWaveform(
                        levels = List(VoiceInputController.WAVE_BAR_COUNT) { 0.5f },
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        // 160dpi → density=1 → 1dp = 1px；bounds 是 Dp，.value 取 Float 对比。
        val bounds = compose.onNodeWithTag(VOICE_WAVEFORM_TAG).getUnclippedBoundsInRoot()
        assertEquals(
            com.psyche.memo.ui.ChatStyleSpec.WAVE_SLOT_HEIGHT_DP.dp.value,
            (bounds.bottom - bounds.top).value,
            0.5f,
        )
    }

    @Test
    fun `explicit caller height wins over the slot fallback`() {
        compose.setContent {
            MaterialTheme {
                Column {
                    VoiceWaveform(
                        levels = listOf(0.5f),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    )
                }
            }
        }
        val bounds = compose.onNodeWithTag(VOICE_WAVEFORM_TAG).getUnclippedBoundsInRoot()
        assertEquals(48f, (bounds.bottom - bounds.top).value, 0.5f)
    }
}
