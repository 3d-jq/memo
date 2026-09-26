package com.psyche.memo.provider.browser

/**
 * [BrowserTools] 面向的执行端：真身是 [BrowserSession]，单测里换成替身。
 *
 * 属性一律 `val` + `publishSnapshot(...)` 而不是 `var`：实现方（Session）要用私有
 * setter 守状态，接口给写入器最省事。
 *
 * **标签（spec §12.2）也是这个接口的一部分**：模型侧默认永远只碰活动标签，唯一能换活动标签的
 * 入口是 [openTab]（`new_tab`）与 [selectTab]。这几颗方法在接口上而不是藏在 `BrowserSession`
 * 里，是因为 `browser_tabs` 与 `browser_open` 要能在**不碰 WebView** 的单测里验到
 * 「越界编号不执行」「超上界回 TAB_LIMIT」这些真会错的地方。
 */
interface BrowserGateway {
    val generation: Int
    val url: String

    /**
     * 当前页标题 —— **每颗工具调用的信封都带它**。
     *
     * 理由不是"好看"：用户 2026-09-26 实测「模型自己说做了，其实什么都没发生」。动作类调用的结果
     * 过去只有 `url` + `generation` + 一个它自己传进来的把手，模型**看不到任何页面状态**，
     * 于是它只能凭想象写下一步。标题是 `WebChromeClient.onReceivedTitle` 实时维护的，取它零成本
     * （不再多跑一支 JS），却是"这一页到底还是不是我以为的那一页"最便宜的一张收据。
     */
    val title: String

    /**
     * 页面**还在加载吗**（`WebChromeClient.onProgressChanged` 里 progress<100 就是 true）。
     * 动作之后要等它落下来再回话，否则 SPA 的第二跳还没渲染完就报"成功"，模型读到的是半张页面。
     */
    val loading: Boolean
    val userControls: Boolean
    val snapshot: BrowserPageSnapshot?

    /**
     * 实例是否已关闭。**关掉的实例绝不许再交回任何会话**（[BrowserSessionStore.sessionFor]
     * 会重建、`peek` 会当它不存在）：复用尸体的后果是该会话从此每颗动作都 `RENDERER_GONE`。
     */
    val isClosed: Boolean
    suspend fun navigate(url: String): Result<Unit>
    suspend fun run(script: String, timeoutMs: Long): Pair<Boolean, String>
    suspend fun screenshotPng(): ByteArray
    suspend fun goBack(): Boolean

    /** 历史前进 —— 与 [goBack] 同形状：没有下一页就 false，成功那一次换代次（spec §12.1 的新动作）。 */
    suspend fun goForward(): Boolean
    suspend fun reload(): Result<Unit>
    fun publishSnapshot(snapshot: BrowserPageSnapshot?)

    /** `click`/`type` 之后调用：页面大概已经变了，旧的 index 一律作废。 */
    fun bumpGenerationAndDropSnapshot()

    /**
     * 取走并清空「这一轮本机替模型挡掉了什么」（JS 弹窗 / 下载 / 站点权限 / 新窗口）。
     * spec §7.3：拒绝也要说明，否则模型会以为动作正常完成了。
     * 一轮里多笔来源各挡一次时会**累积**（分号分隔），不是互相覆盖；累积有上界
     * （BrowserSession 私有的 NOTICE_MAX_ENTRIES/NOTICE_MAX_CHARS：最多 3 条、总长 ≤480），
     * 超上界丢最旧，串尾以 `…(+N)` 报丢了几条。
     *
     * **整会话一份账**，不是每标签一份：多标签之后每份都各自封顶会变成 5×480 字符一起进信封。
     */
    fun drainNotice(): String?

    /** 当前标签清单（活动那一条 `active=true`）。纯读，不建标签。 */
    fun tabInfos(): List<BrowserTabInfo>

    /**
     * 开新标签并设为活动，然后在它上面 navigate。失败码：`TAB_LIMIT`（已到
     * [BrowserSession.MAX_TABS]）、`BLOCKED_SCHEME`、以及 navigate 那几条。
     */
    suspend fun openTab(url: String): Result<Unit>

    /** 换活动标签。编号越界 → 失败 `TAB_INDEX_INVALID`，**不做任何事**。 */
    suspend fun selectTab(index: Int): Result<Unit>

    /** 关一个标签。编号越界 → `TAB_INDEX_INVALID`；关掉最后一个会留一枚空白标签（会话不许没有标签）。 */
    suspend fun closeTab(index: Int): Result<Unit>
}

/**
 * 标签清条目：给 `browser_tabs` 的返回值，也给接管遮罩的标签条。
 *
 * 只有 `index` / `title` / `url` / `active` 四件事 —— **不带代次**：代次是「这一页的可交互元素
 * 清单」的把手，归 `browser_find` 与 `browser_page_info`，把它塞进清单条目会让模型误以为
 * 「换了标签还能沿用旧号」（spec §12.2 的跨标签别名就是这么产生的，解法见
 * [BrowserSession.nextEpoch]）。
 */
data class BrowserTabInfo(
    val index: Int,
    val title: String,
    val url: String,
    val active: Boolean,
)
