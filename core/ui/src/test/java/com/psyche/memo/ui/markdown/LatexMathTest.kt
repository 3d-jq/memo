package com.psyche.memo.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数学公式的**纯判定**部分（渲染本体是 jlatexmath drawable，跑不了单测）：
 * 块级/行内定界符识别 + 定界符剥离。两个开关对应显示设置 → 渲染页的两行
 * （`display_enable_math_rendering_v1` / `display_enable_dollar_latex_v1`）。
 */
class LatexMathTest {

    private val on = MathConfig(enabled = true, dollarLatex = true)
    private val noDollar = MathConfig(enabled = true, dollarLatex = false)
    private val off = MathConfig(enabled = false, dollarLatex = true)

    // ---- 块级公式 ----

    @Test
    fun displayDollarAndBracketAreBothBlockMath() {
        assertEquals("x^2", displayMathBody("\$\$x^2\$\$", on))
        assertEquals("x^2", displayMathBody("\\[x^2\\]", on))
        // 前后空白无所谓（段落文本会带缩进/换行）。
        assertEquals("x^2", displayMathBody("  \$\$x^2\$\$  ", on))
    }

    @Test
    fun displayMathKeepsInnerNewlines() {
        assertEquals("a + b\nc", displayMathBody("\$\$\na + b\nc\n\$\$", on))
    }

    @Test
    fun bracketFormSurvivesTheDollarSwitch() {
        // dollarLatex 只管 $ 形式：关掉后 \[..\] 仍渲染。
        assertNull(displayMathBody("\$\$x^2\$\$", noDollar))
        assertEquals("x^2", displayMathBody("\\[x^2\\]", noDollar))
    }

    @Test
    fun masterSwitchDisablesEverything() {
        assertNull(displayMathBody("\$\$x^2\$\$", off))
        assertNull(displayMathBody("\\[x^2\\]", off))
    }

    @Test
    fun plainParagraphIsNotMath() {
        assertNull(displayMathBody("普通段落", on))
        assertNull(displayMathBody("\$x^2\$", on)) // 单 $ 是行内，不是块级
        assertNull(displayMathBody("", on))
        assertNull(displayMathBody("\$\$\$\$", on)) // 空公式
    }

    // ---- 行内公式 ----

    @Test
    fun inlineDollarAndParenAreSplitOut() {
        assertEquals(
            listOf(MathSegment.Plain("面积 "), MathSegment.Formula("x^2"), MathSegment.Plain(" 平方米")),
            splitInlineMath("面积 \$x^2\$ 平方米", on),
        )
        assertEquals(
            listOf(MathSegment.Plain("a"), MathSegment.Formula("y"), MathSegment.Plain("b")),
            splitInlineMath("a\\(y\\)b", on),
        )
    }

    @Test
    fun escapedDollarStaysPlain() {
        assertEquals(listOf(MathSegment.Plain("价格 \\\$5 起")), splitInlineMath("价格 \\\$5 起", on))
    }

    @Test
    fun unpairedOrSpacedDollarsStayPlain() {
        // 只有开定界符。
        assertEquals(listOf(MathSegment.Plain("cost \$5")), splitInlineMath("cost \$5", on))
        // 定界符内侧有空格：不当公式（价格一类文本的常见形态）。
        assertEquals(listOf(MathSegment.Plain("A \$ 5 和 \$ 6")), splitInlineMath("A \$ 5 和 \$ 6", on))
    }

    @Test
    fun inlineMathDoesNotCrossLines() {
        assertEquals(
            listOf(MathSegment.Plain("\$a\nb\$")),
            splitInlineMath("\$a\nb\$", on),
        )
    }

    @Test
    fun doubleDollarIsLeftToTheBlockRenderer() {
        assertEquals(listOf(MathSegment.Plain("前 \$\$x\$\$ 后")), splitInlineMath("前 \$\$x\$\$ 后", on))
    }

    @Test
    fun switchesGateInlineMath() {
        assertEquals(listOf(MathSegment.Plain("\$x\$")), splitInlineMath("\$x\$", noDollar))
        assertEquals(listOf(MathSegment.Plain("\\(x\\)")), splitInlineMath("\\(x\\)", off))
        assertTrue(splitInlineMath("纯文本", on).single() is MathSegment.Plain)
    }

    // ---- 定界符剥离（RikkaHub processLatex） ----

    @Test
    fun processLatexStripsAllFourDelimiterForms() {
        assertEquals("x^2", processLatex("\$x^2\$"))
        assertEquals("x^2", processLatex("\$\$x^2\$\$"))
        assertEquals("x^2", processLatex("\\(x^2\\)"))
        assertEquals("x^2", processLatex("\\[x^2\\]"))
        assertEquals("x^2", processLatex("  \$x^2\$  "))
        // 没有定界符时原样（只去首尾空白）。
        assertEquals("\\frac{1}{2}", processLatex(" \\frac{1}{2} "))
    }

    @Test
    fun defaultConfigHasBothSwitchesOn() {
        assertTrue(MathConfig.DEFAULT.enabled)
        assertTrue(MathConfig.DEFAULT.dollarLatex)
    }
}
