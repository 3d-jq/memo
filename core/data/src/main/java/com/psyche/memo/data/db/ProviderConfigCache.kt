package com.psyche.memo.data.db

import com.psyche.memo.data.model.ProviderConfig
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程内 `provider_rows` 解码缓存。
 *
 * `AppContainer.providerConfig()` 原本每次调用都要「新建 DAO → 查库 → 解 JSON」，
 * 而它在组合期被大量调用（光是一次请求的组装路径就打 4 次）。没有缓存时，任何
 * 「打开就要读供应商配置」的界面都会在主线程上打出多次 SQLite 往返 —— 表现就是
 * 点开界面卡一下（用户 2026-09-15「只要有加载数据的界面 点击显示该界面就要卡顿」）。
 *
 * 失效统一由写入侧负责：[PayloadEntityDao] 在 `provider_rows` 发生
 * upsert / delete / replaceAll 时调用 [invalidate]。工程内没有绕过 DAO 直接写
 * `provider_rows` 的裸 SQL（备份恢复也走 DAO），所以失效是完整的。
 */
object ProviderConfigCache {

    private val entries = ConcurrentHashMap<String, ProviderConfig>()

    fun get(key: String): ProviderConfig? = entries[key]

    fun put(key: String, config: ProviderConfig) {
        entries[key] = config
    }

    /**
     * 清掉单个键；[key] 为 null 时整表失效 —— 写入侧（DAO）只知道「这张表变了」，
     * 所以它永远传 null。单键失效留给明确知道改了哪一行的调用方。
     */
    fun invalidate(key: String? = null) {
        if (key == null) entries.clear() else entries.remove(key)
    }
}
