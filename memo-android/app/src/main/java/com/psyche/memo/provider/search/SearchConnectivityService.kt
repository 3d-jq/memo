package com.psyche.memo.provider.search

import com.psyche.memo.data.model.BingLocalOptions
import com.psyche.memo.data.model.KelivoOptions
import com.psyche.memo.data.model.SearchCommonOptions
import com.psyche.memo.data.model.SearchServiceOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 搜索服务连通性共享表 —— `settings_provider.dart` 的 `_searchConnection` +
 * `_initSearchConnectivityTests` / `_testSingleSearchService`（L2235-2269）。
 *
 * **为什么必须是容器级**：原版这张表住在 `SettingsProvider`（App 生命周期），
 * 所以两件事都靠它：
 *  · 启动时「自动测试连接」开关打开 → 每个非本地服务后台探一次（`unawaited`，
 *    不互相等待），结果就是列表行右边那枚「已连接 / 失败」胶囊；
 *  · 手动长按「测试连接」的结果离开页面再回来**还在**。
 *
 * 我们此前把这张表塞在 `SearchServicesScreen` 的页面级 `remember` 里，而开关
 * （`search_auto_test_on_launch_v1`）写了却**没有任何消费点** —— 用户 2026-09-16
 * 「启动时自动测试连接没有做吧」说的就是这个（设备库里那个键确实是 1）。
 */
class SearchConnectivityService(
    private val scope: CoroutineScope,
    private val engine: SearchEngine,
    private val services: () -> List<SearchServiceOptions>,
    private val common: () -> SearchCommonOptions,
) {

    private val _states = MutableStateFlow<Map<String, Boolean?>>(emptyMap())
    val states: StateFlow<Map<String, Boolean?>> = _states

    /**
     * 本地引擎没有可测的远端：原版对 BingLocal / Kelivo 直接写 `null`
     * （「未测试」），不发请求 —— 依赖外网的 `search_web` 才是要探的。
     */
    fun skipped(service: SearchServiceOptions): Boolean =
        service is BingLocalOptions || service is KelivoOptions

    /**
     * 用一次最小搜索探活。返回 null = 这类服务不参与测试（状态写 null）。
     *
     * [commonOptions] 默认取公共选项（原版 `_testSingleSearchService` 传
     * `_searchCommonOptions`）；手动「测试连接」传 `resultSize = 1` 的那份
     * （`search_services_page.dart:121-124`）。
     */
    suspend fun probe(
        service: SearchServiceOptions,
        commonOptions: SearchCommonOptions = common(),
    ): Boolean? {
        if (skipped(service)) {
            put(service.id, null)
            return null
        }
        val ok = runCatching {
            engine.search(query = PROBE_QUERY, options = service, common = commonOptions)
        }.isSuccess
        put(service.id, ok)
        return ok
    }

    /** 启动钩子：开关打开时把每个非本地服务各探一次（原版不 await 全部）。 */
    fun testAllOnLaunch() {
        val list = runCatching { services() }.getOrDefault(emptyList())
        for (service in list) {
            if (skipped(service)) {
                put(service.id, null)
                continue
            }
            scope.launch { probe(service) }
        }
    }

    private fun put(id: String, value: Boolean?) {
        _states.value = _states.value + (id to value)
    }

    companion object {
        /** 原版探活用的查询串（`_testSingleSearchService`）。 */
        const val PROBE_QUERY = "connectivity test"
    }
}
