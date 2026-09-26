package com.psyche.memo.provider.browser

/**
 * [BrowserTools] 面向的执行端：真身是 [BrowserSession]，单测里换成替身。
 *
 * 属性一律 `val` + `publishSnapshot(...)` 而不是 `var`：实现方（Session）要用私有
 * setter 守状态，接口给写入器最省事。
 */
interface BrowserGateway {
    val generation: Int
    val url: String
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
     */
    fun drainNotice(): String?
}
