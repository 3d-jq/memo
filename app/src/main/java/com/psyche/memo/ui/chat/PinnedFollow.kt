package com.psyche.memo.ui.chat

/**
 * 流式跟随的**判定核心**（纯函数，好测）。
 *
 * 抖动根因（完整论证见 `.stepcode/plans/session-01a0ce17-*.md`）：
 * 我们原先的贴底是**事件驱动**（`LaunchedEffect(messages, …)`，每个 SSE chunk 贴一次），
 * 而流式尾部的高度是**异步落地**（Markdown 解析在 `Dispatchers.Default`、代码块 `animateContentSize`）。
 * 两者节奏对不齐 ⇒ `chunk 贴底 → 下一帧长高 → 没人贴底 → 提示被顶高一行 → 下个 chunk 又贴回来`
 * = 肉眼看到的「上下抖」。
 *
 * 两个成熟参照（RikkaHub `ChatList.kt:278-290`、dsh `ChatView.tsx:331-341`）的共识是：
 * **跟随由「内容尺寸/布局变化」驱动，由「读者是否贴底」把门**；数据事件只承担结构变化那一下。
 * 所以这里只做判定，触发源在 `ChatContent` 里换成 `snapshotFlow { 尾部缝隙 }`（布局驱动）。
 */
object PinnedFollow {

    /** 贴底容差：小于它认为「已经在底部」，不再动（防抖 + 防自激）。 */
    const val STICK_TOLERANCE_DP = 10

    /** 流式结束后尾部还会长高（操作行/Token 统计/思考卡收起），这段窗口继续允许贴底。 */
    const val FINISH_GRACE_MS = 450L

    /**
     * 这一次布局变化要不要把列表贴到底。
     *
     * 判据（缺一不可，顺序即优先级）：
     * 1. 有内容；
     * 2. **读者还贴着底**（[following]）—— 用户往上滑走之后绝不把他拽回来；
     * 3. 自动滚动开着（[autoScrollEnabled]）；
     * 4. 手指不在屏上（[pointerDown]，§4.42 硬规则）；
     * 5. 此刻没在滚动（[isScrollInProgress]）；
     * 6. 生成中**或**在结束后的宽限窗口内（[streaming] / [graceActive]）；
     * 7. 尾部确实离开底部超过容差（[gapPx] > [tolerancePx]）—— 已经在底部就不必再滚，
     *    否则会自激（滚一次 → 又触发一次 → …）。
     */
    fun shouldPinToBottom(
        hasMessages: Boolean,
        following: Boolean,
        autoScrollEnabled: Boolean,
        pointerDown: Boolean,
        isScrollInProgress: Boolean,
        streaming: Boolean,
        graceActive: Boolean,
        gapPx: Float,
        tolerancePx: Float,
    ): Boolean = hasMessages &&
        following &&
        autoScrollEnabled &&
        !pointerDown &&
        !isScrollInProgress &&
        (streaming || graceActive) &&
        gapPx != Float.MAX_VALUE &&
        gapPx > tolerancePx

    /**
     * 列表末尾**哨兵项**的下标 = 消息数 + 额外项数。
     *
     * 额外项必须与 `LazyColumn` 的 gating **逐条对齐**（照 2026-09-23 的实证）：
     * - 压缩进度行：`compacting && streamingMessageId == null`；
     * - 流式等待提示项：存在流式中的消息。
     *
     * 原实现在 `compacting && streaming` 时**漏算**了流式项 ⇒ 哨兵下标偏小 1 ⇒ 「到底」落在
     * 提示项上而不是列表真末 ⇒ 位置差一行（用户看到的抖动/贴不到底，两个症状同源）。
     */
    fun bottomAnchorIndexFor(
        messagesCount: Int,
        compactionProgress: Boolean,
        streamingIndicator: Boolean,
    ): Int = messagesCount +
        (if (compactionProgress) 1 else 0) +
        (if (streamingIndicator) 1 else 0)
}
