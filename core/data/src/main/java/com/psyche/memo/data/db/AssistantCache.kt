package com.psyche.memo.data.db

import com.psyche.memo.data.model.Assistant
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程内 `assistant_rows` 解码缓存（与 [ProviderConfigCache] 同一套路）。
 *
 * `AssistantStore.get(id)` 每次调用都是「查库 + 解 payload JSON」，而它在**组合期**被
 * 调用：抽屉的当前助手（名字/头像）、消息头归属助手、技能/工作区/MCP 选择 sheet、
 * 顶栏助手头像…… 一次对话打开就能打十几次。缓存之后这些读取变成内存查表。
 *
 * 失效统一由写入侧负责：[PayloadEntityDao] 在 `assistant_rows` 发生
 * upsert / delete / replaceAll 时调用 [invalidate]（工程内没有绕过 DAO 直接写
 * `assistant_rows` 的裸 SQL，备份恢复也走 DAO）。启动时由
 * `AppContainerImpl.prewarmConfigCaches()` 预热一次。
 */
object AssistantCache {

    private val entries = ConcurrentHashMap<String, Assistant>()

    fun get(id: String): Assistant? = entries[id]

    fun put(id: String, assistant: Assistant) {
        entries[id] = assistant
    }

    /** 清掉单个键；[id] 为 null 时整表失效（写入侧只知道「这张表变了」）。 */
    fun invalidate(id: String? = null) {
        if (id == null) entries.clear() else entries.remove(id)
    }
}
