package com.psyche.memo.provider.workspace

/**
 * 「读过才许改」的账本（学 deepseek-harness 的 B10：未读不可 edit + 乐观并发）。
 *
 * 为什么需要：`workspace_edit_file` 只按 `old_text` 匹配。模型没读过文件也能猜出一段
 * 存在的原文并把整篇写回去 —— 用户在工作区里手改过的内容就这么被覆盖了，而且工具会
 * 报「成功替换 1 处」，看起来完全正常。
 *
 * 记的是**读取那一刻的字节数**：改之前再量一次，不一样就是「你读的那份已经不是现在
 * 这份」，必须重读。用大小而不是内容哈希，是因为工作区只有一条 `rootfsFileSize` 的
 * 廉价通道 —— 要算哈希就得整篇再导出一次，等于每次编辑都先读一遍文件。
 *
 * 键含会话 id：同一条会话里读过才许改，换会话重新读（与上游「模型每轮该看到自己做过
 * 什么」的口径一致）。表是容器级的，所以按 LRU 限长，不因会话变多而无限涨。
 */
class WorkspaceReadLedger(private val capacity: Int = 512) {

    /** 编辑前的三种判定：没读过 / 读的那份已经变了 / 可以改。 */
    sealed interface EditCheck {
        data object Ok : EditCheck
        data object NotRead : EditCheck
        data class Stale(val seenBytes: Long, val nowBytes: Long) : EditCheck
    }

    private val sizes = LinkedHashMap<String, Long>(16, 0.75f, true)

    private fun key(conversationId: String?, workspaceId: String, path: String) =
        "${conversationId.orEmpty()}|$workspaceId|$path"

    /** 读到了（或刚写完）就记一次；[sizeBytes] 是当时的字节数。 */
    fun record(conversationId: String?, workspaceId: String, path: String, sizeBytes: Long) {
        synchronized(sizes) { sizes[key(conversationId, workspaceId, path)] = sizeBytes }
        trim()
    }

    /** 编辑前问一句：这份文件是不是还在「模型读过的那个版本」上。 */
    fun check(
        conversationId: String?,
        workspaceId: String,
        path: String,
        currentSizeBytes: Long,
    ): EditCheck {
        val seen = synchronized(sizes) { sizes[key(conversationId, workspaceId, path)] }
            ?: return EditCheck.NotRead
        return if (seen == currentSizeBytes) EditCheck.Ok
        else EditCheck.Stale(seen, currentSizeBytes)
    }

    /** 删掉某条路径的记录（删除文件后不该再算「读过」）。 */
    fun forget(conversationId: String?, workspaceId: String, path: String) {
        synchronized(sizes) { sizes.remove(key(conversationId, workspaceId, path)) }
    }

    val size: Int get() = synchronized(sizes) { sizes.size }

    private fun trim() {
        while (size > capacity) {
            val oldest = synchronized(sizes) { sizes.keys.firstOrNull() } ?: return
            synchronized(sizes) { sizes.remove(oldest) }
        }
    }

    /** 一条会话结束（会话被删）时清掉它的全部记录。 */
    fun dropConversation(conversationId: String) {
        val prefix = "$conversationId|"
        synchronized(sizes) { sizes.keys.removeAll { it.startsWith(prefix) } }
    }
}

/** 供 [WorkspaceReadLedger] 之外的调用点复用的错误形状（与 ToolResults 同一口径）。 */
object WorkspaceEditGuards {

    fun notRead(path: String): String =
        "You have not read `$path` in this conversation, so it cannot be edited."

    fun notReadInstruction(path: String): String =
        "Call $READ_FILE on `$path` first, then edit against the text you actually saw."

    fun stale(path: String, seenBytes: Long, nowBytes: Long): String =
        "`$path` changed since you read it ($seenBytes -> $nowBytes bytes)."

    fun staleInstruction(path: String): String =
        "Re-read `$path` before editing: the version you based old_text on is no longer " +
            "the one on disk, and writing it back would discard what changed in between."

    const val READ_FILE = "workspace_read_file"
}
