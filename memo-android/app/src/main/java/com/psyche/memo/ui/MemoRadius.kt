package com.psyche.memo.ui

/**
 * 全站圆角 token —— 采自 Apple HIG 风格参考（Pinguo/Pinguo Design System，
 * `css.json`：全局 radius = 19.2px ≈ 20dp，内嵌元素 radius−4 ≈ 16dp，胶囊 = 999）。
 *
 * 只取几何不取颜色：配色仍走 Memo 主题（LocalSemanticColors / ColorScheme）。
 *
 * 用法：
 *  - [Card]：卡片、sheet、对话框、输入框等**一级容器**（原 r12/r14/r16 混用收口到此）。
 *  - [Inner]：一级容器**内部**的嵌套块（卡内选项行、内嵌输入、分组 tile）。
 *  - [Pill]：胶囊按钮、标签、chips（全圆，无硬角）。
 */
object MemoRadius {
    /** 一级容器圆角（卡片 / sheet / 对话框 / 输入框）。 */
    const val CARD_DP = 20

    /** 容器内嵌套块圆角（全局圆角 − 4）。 */
    const val INNER_DP = 16

    /** 胶囊（按钮 / 标签 / chips）。 */
    const val PILL_DP = 999
}
