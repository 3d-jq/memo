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
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.psyche.memo.ui.markdown.MarkdownTableActions
import com.psyche.memo.ui.markdown.MarkdownText
import org.junit.Assert.assertEquals
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

    // ── Toolbar (_MarkdownTableToolbar) ──────────────────────────────────

    /** Renders [markdown] with [actions] attached, the way HomeScreen does. */
    private fun renderWithActions(
        markdown: String,
        actions: MarkdownTableActions,
    ) {
        compose.setContent {
            MaterialTheme {
                Box(modifier = Modifier.width(360.dp)) {
                    MarkdownText(markdown = markdown, tableActions = actions)
                }
            }
        }
    }

    private val threeColumnTable =
        "| A | B | C |\n" +
            "|---|---|---|\n" +
            "| 1 | 2 | 3 |"

    @Test
    fun `toolbar is absent when no actions are supplied`() {
        // Without injected platform actions there is nothing to tap, so the
        // bar must not appear — the original only builds it when the host
        // supplies handlers.
        renderInViewport(threeColumnTable)
        compose.waitForIdle()
        compose.onNodeWithText("Table").assertDoesNotExist()
    }

    @Test
    fun `toolbar shows the label and every wired button`() {
        renderWithActions(
            threeColumnTable,
            MarkdownTableActions(
                onCopyMarkdown = {},
                onCopyImage = {},
                onExportCsv = {},
                onSaveImage = {},
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText("Table").assertExists()
        compose.onNodeWithContentDescription("Table copied.").assertExists()
        compose.onNodeWithContentDescription("Save to Gallery").assertExists()
        compose.onNodeWithContentDescription("Export CSV").assertExists()
    }

    @Test
    fun `toolbar omits buttons whose action is null`() {
        // Only a markdown copy handler: the two image buttons must vanish.
        renderWithActions(threeColumnTable, MarkdownTableActions(onCopyMarkdown = {}))
        compose.waitForIdle()
        compose.onNodeWithText("Table").assertExists()
        compose.onNodeWithContentDescription("Table copied.").assertExists()
        compose.onNodeWithContentDescription("Save to Gallery").assertDoesNotExist()
        compose.onNodeWithContentDescription("Export CSV").assertDoesNotExist()
    }

    @Test
    fun `tapping copy hands the markdown serialisation to the host`() {
        var copied: String? = null
        renderWithActions(
            threeColumnTable,
            MarkdownTableActions(onCopyMarkdown = { copied = it }),
        )
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Table copied.").performClick()
        compose.waitForIdle()
        assertEquals("| A | B | C |\n| --- | --- | --- |\n| 1 | 2 | 3 |", copied)
    }

    @Test
    fun `tapping export hands CSV to the host`() {
        var csv: String? = null
        renderWithActions(
            threeColumnTable,
            MarkdownTableActions(onExportCsv = { csv = it }),
        )
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Export CSV").performClick()
        compose.waitForIdle()
        assertEquals("A,B,C\r\n1,2,3", csv)
    }

    @Test
    fun `toolbar keeps the table inside its viewport`() {
        renderWithActions(
            threeColumnTable,
            MarkdownTableActions(onCopyMarkdown = {}, onExportCsv = {}, onSaveImage = {}),
        )
        compose.waitForIdle()
        assertTrue(
            "toolbar must not widen the card past its viewport (root right=${rootRight()})",
            rootRight() <= 360f + 1f,
        )
    }
}
