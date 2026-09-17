package com.psyche.memo.provider.generation

import com.psyche.memo.data.assistant.AssistantStore
import com.psyche.memo.data.generation.GenerationServiceStore
import com.psyche.memo.data.model.GenerationService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 生成服务（图片 / 视频）的仓储 —— 自研功能，上游 kelivo 没有这一类服务。
 *
 * 与 [com.psyche.memo.provider.workspace.WorkspaceRepository] 同一套形态：
 *  1. 记录存 drift v3 的通用表 `extension_entity_rows`（[GenerationServiceStore.KIND]），
 *     不新建表；
 *  2. 变更用 [version] 计数器广播（Memo 没有 Room 的 Flow），列表页 collect 它重读；
 *  3. 删除服务时**连带清掉助手的绑定**，别留悬空引用（助手编辑页会显示「服务已删除」）。
 */
class GenerationServiceRepository(
    private val store: GenerationServiceStore,
    private val assistants: AssistantStore,
) {

    private val _version = MutableStateFlow(0)

    /** 服务集合的变更计数（界面 collect 它来重读列表）。 */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun list(kind: String? = null): List<GenerationService> = store.getAll(kind)

    fun get(id: String): GenerationService? = store.get(id)

    suspend fun create(service: GenerationService): GenerationService = withContext(Dispatchers.IO) {
        store.create(service).also { bump() }
    }

    suspend fun update(service: GenerationService): GenerationService? = withContext(Dispatchers.IO) {
        store.update(service)?.also { bump() }
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val removed = store.delete(id)
        if (removed) {
            clearAssistantBindings(id)
            bump()
        }
        removed
    }

    /** 「测试连接」结果落库（列表/编辑页的状态显示；[state] = [GenerationTestState] 三态）。 */
    suspend fun setTestState(id: String, state: String): GenerationService? = withContext(Dispatchers.IO) {
        store.setTestState(id, state)?.also { bump() }
    }

    /**
     * 服务被删后把引用它的助手绑定清掉（只清绑定的那一类，另一类不动）。
     * 与 `WorkspaceRepository.clearAssistantBindings` 同义。
     */
    private fun clearAssistantBindings(serviceId: String) {
        for (assistant in assistants.getAll()) {
            val image = assistant.imageGeneration
            val video = assistant.videoGeneration
            val nextImage = if (image?.serviceId == serviceId) null else image
            val nextVideo = if (video?.serviceId == serviceId) null else video
            if (nextImage !== image || nextVideo !== video) {
                assistants.update(
                    assistant.copy(imageGeneration = nextImage, videoGeneration = nextVideo),
                )
            }
        }
    }

    private fun bump() {
        _version.value = _version.value + 1
    }
}
