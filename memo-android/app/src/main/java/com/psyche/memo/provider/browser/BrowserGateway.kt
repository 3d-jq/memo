package com.psyche.memo.provider.browser

/**
 * `BrowserTool` 面向的执行端：真身是 [BrowserSession]，单测里换成替身。
 *
 * 属性一律 `val` + `publishSnapshot(...)` 而不是 `var`：实现方（Session）要用私有
 * setter 守状态，接口给写入器最省事。
 */
interface BrowserGateway {
    val generation: Int
    val url: String
    val userControls: Boolean
    val snapshot: BrowserPageSnapshot?
    val canGoBack: Boolean
    suspend fun navigate(url: String): Result<Unit>
    suspend fun run(script: String, timeoutMs: Long): Pair<Boolean, String>
    suspend fun screenshotPng(): ByteArray
    suspend fun goBack(): Boolean
    suspend fun reload(): Result<Unit>
    fun publishSnapshot(snapshot: BrowserPageSnapshot?)

    /** `click`/`type` 之后调用：页面大概已经变了，旧的 index 一律作废。 */
    fun bumpGenerationAndDropSnapshot()

    /**
     * 取走并清空「这一轮本机替模型挡掉了什么」（JS 弹窗 / 下载 / 站点权限）。
     * spec §7.3：拒绝也要说明，否则模型会以为动作正常完成了。
     */
    fun drainNotice(): String?
}
