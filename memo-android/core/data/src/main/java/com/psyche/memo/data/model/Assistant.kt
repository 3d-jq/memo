package com.psyche.memo.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 助手绑定的生成服务（自研功能，见 [GenerationService]）：图片/视频各一份。
 *
 * 只有「开关 + 选哪个服务 + 覆盖哪些参数」—— key/地址留在服务里，所以多个助手
 * 可以共用一份配置（和 TTS 服务、工作区一个路子）。字段为 null = 用服务里的值。
 */
@Serializable
data class AssistantGenerationBinding(
    val enabled: Boolean = false,
    val serviceId: String? = null,
    /** 覆盖服务里的模型（空 = 用服务里的）。 */
    val model: String? = null,
    /** 图片：覆盖尺寸/张数。 */
    val size: String? = null,
    val count: Int? = null,
    /** 视频：覆盖秒数（尺寸共用 [size]）。 */
    val durationSeconds: Int? = null,
) {
    val isUsable: Boolean get() = enabled && !serviceId.isNullOrBlank()

    companion object {
        /**
         * 选中 / 取消一个服务（+ 面板的生成选择器与助手编辑页共用同一条规则）：
         * [serviceId] 为 null 表示关掉（工具不再下发给模型）——此时 `enabled=false`
         * **且 serviceId 一并清空**（与助手编辑页的 `selectedId` 判据一致，否则
         * 页面会出现「勾着服务但其实没开」的矛盾态）；覆盖参数（model/size/count…）
         * 原样保留，下次选中同一个服务不用重填。
         */
        fun select(
            current: AssistantGenerationBinding?,
            serviceId: String?,
        ): AssistantGenerationBinding = (current ?: AssistantGenerationBinding()).copy(
            enabled = serviceId != null,
            serviceId = serviceId,
        )
    }
}

/**
 * Assistant entity DTO — mirrors Flutter Assistant.toJson keys
 * (stored in assistant_rows.payload). Missing/unknown keys are tolerated:
 * the serializer maps the core fields and keeps unknown keys in [raw] for
 * lossless backup round-trips.
 */
@Serializable
data class Assistant(
    val id: String = "",
    val name: String = "",
    val avatar: String? = null,
    val useAssistantAvatar: Boolean = false,
    val useAssistantName: Boolean = false,
    val chatModelProvider: String? = null,
    val chatModelId: String? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val contextMessageSize: Int = 64,
    val limitContextMessages: Boolean = false,
    val streamOutput: Boolean = true,
    val thinkingBudget: Int? = null,
    val maxTokens: Int? = null,
    val systemPrompt: String = "",
    val messageTemplate: String = "{{ message }}",
    val searchEnabled: Boolean = false,
    val mcpServerIds: List<String> = emptyList(),
    val localToolIds: List<String> = emptyList(),
    /** Agent Skills：助手启用的技能名（技能本体在 filesDir/skills/<name>/SKILL.md）。 */
    val enabledSkills: List<String> = emptyList(),
    /** 沙箱工作区绑定（RikkaHub `assistant.workspaceId` / `workspaceCwd`）。 */
    val workspaceId: String? = null,
    val workspaceCwd: String? = null,
    /** 生成图片 / 生成视频服务绑定（自研功能）：开关 + 选服务 + 覆盖参数。 */
    val imageGeneration: AssistantGenerationBinding? = null,
    val videoGeneration: AssistantGenerationBinding? = null,
    val healthDataTypeIds: List<String> = emptyList(),
    val background: String? = null,
    val customHeaders: List<Map<String, String>> = emptyList(),
    val customBody: List<Map<String, String>> = emptyList(),
    val enableMemory: Boolean = false,
    val autoOrganizeMemory: Boolean = false,
    val memoryOrganizeEveryNTurns: Int = 1,
    val memorySmartAddMode: String = "batched",
    val memoryWriteScope: String = "alwaysGlobal",
    val allowPastConversationRecall: Boolean = false,
    val generateConversationSummary: Boolean = false,
    val recentChatsSummaryMessageCount: Int = 5,
    val appendCurrentTimeToUserMessage: Boolean = false,
    val presetMessages: List<JsonElement> = emptyList(),
    val regexRules: List<JsonElement> = emptyList(),
) {
    /** 该类型的生成绑定（kind = [GenerationKind.IMAGE] / [GenerationKind.VIDEO]）。 */
    fun generationBinding(kind: String): AssistantGenerationBinding? =
        if (kind == GenerationKind.VIDEO) videoGeneration else imageGeneration

    /** 写回该类型的生成绑定（+ 面板的生成选择器与助手编辑页共用）。 */
    fun withGenerationBinding(kind: String, binding: AssistantGenerationBinding?): Assistant =
        if (kind == GenerationKind.VIDEO) {
            copy(videoGeneration = binding)
        } else {
            copy(imageGeneration = binding)
        }

    companion object {
        /** assistant.dart L20-22. */
        const val DefaultTemperature: Double = 1.0
        const val MinContextMessageSize: Int = 1
        const val MaxContextMessageSize: Int = 4096

        fun fromJsonString(json: kotlinx.serialization.json.Json, text: String): Assistant =
            json.decodeFromString(serializer(), text)
    }
}
