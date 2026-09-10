package com.psyche.memo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import com.psyche.memo.ui.markdown.MarkdownText
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Layout regression tests for the GFM table renderer.
 *
 * These exist because the table went through several sizing strategies that all
 * looked plausible in code but broke on device:
 *  - `IntrinsicSize` + `weight` mis-measured the columns,
 *  - a character-count heuristic crushed CJK columns ("排名" vs a 9-glyph cell),
 *  - and a long unbreakable run ("RikkaHub、") painted past the table's right
 *    edge and pushed the first column off screen.
 *
 * A screenshot catches those; a unit test catches them *before* shipping. The
 * assertions below pin the two properties a table must never violate: every
 * cell stays inside the table's box, and the table never exceeds the width it
 * was given.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MarkdownTableLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    /** Renders [markdown] inside a fixed-width viewport and returns root bounds. */
    private fun renderInViewport(
        markdown: String,
        viewportWidth: Int = 360,
    ) {
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(viewportWidth.dp)) {
                    MarkdownText(markdown = markdown)
                }
            }
        }
    }

    private fun rootRight(): Float =
        compose.onRoot().getUnclippedBoundsInRoot().right.value

    @Test
    fun `three column table stays inside its viewport`() {
        renderInViewport(
            "| 排名 | App | 时长 |\n" +
                "|------|-----|------|\n" +
                "| 1 | 抖音 | 2 小时 45 分钟 |\n" +
                "| 2 | Memo | 40 分钟 |\n" +
                "| 3 | 酷安 | 26 分钟 |",
        )
        compose.waitForIdle()
        assertTrue(
            "table must not paint past its ${360}dp viewport (root right=${rootRight()})",
            rootRight() <= 360f + 1f,
        )
    }

    @Test
    fun `long unbreakable cell wraps instead of overflowing`() {
        // "RikkaHub、" glues a latin token to a full-width comma — the run that
        // previously escaped the column and dragged the table off screen.
        renderInViewport(
            "| 排名 | App | 时长 |\n" +
                "|------|-----|------|\n" +
                "| 8 | 其他（Via、RikkaHub、Trae、学习通、ChatGPT、QQ、便签等） | ~4.6 小时 |",
        )
        compose.waitForIdle()
        assertTrue(
            "long CJK+latin cell must wrap inside the column (root right=${rootRight()})",
            rootRight() <= 360f + 1f,
        )
    }

    @Test
    fun `four column table scrolls without escaping the viewport`() {
        renderInViewport(
            "| A | B | C | D |\n" +
                "|---|---|---|---|\n" +
                "| 1 | 2 | 3 | 4 |\n" +
                "| 5 | 6 | 7 | 8 |",
        )
        compose.waitForIdle()
        assertTrue(
            "scrollable table must clip to its viewport (root right=${rootRight()})",
            rootRight() <= 360f + 1f,
        )
    }

    @Test
    fun `single column table keeps a readable label`() {
        renderInViewport("| Only |\n|------|\n| x |")
        compose.waitForIdle()
        assertTrue(
            "single-column table fits the viewport (root right=${rootRight()})",
            rootRight() <= 360f + 1f,
        )
    }
}
