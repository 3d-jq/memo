package com.psyche.memo.provider.prompt

import com.psyche.memo.llm.prompt.PromptTransformer
import com.psyche.memo.ui.R as UiR

/**
 * 助手编辑页「可用变量」清单 —— **唯一来源是 [PromptTransformer.supportedKeys]**。
 *
 * 为什么要有这个对象：清单原先内联在页面里，另一份事实在 `core:llm` 的变量表里，
 * 两边各写一份必然漂移（dsh「每个事实只有一个所有者」，见 `docs/ENGINEERING_HARNESS.md` §1）。
 * 现在 UI 只读这里，`PromptVariableCatalogTest` 断言两边一致 —— 漂移即红。
 */
object PromptVariableCatalog {

    /** 变量键 → 说明文案资源 id（顺序即页面展示顺序）。 */
    val entries: List<Pair<Int, String>> = listOf(
        UiR.string.assistant_edit_variable_date to "{cur_date}",
        UiR.string.assistant_edit_variable_time to "{cur_time}",
        UiR.string.assistant_edit_variable_datetime to "{cur_datetime}",
        UiR.string.assistant_edit_variable_model_id to "{model_id}",
        UiR.string.assistant_edit_variable_model_name to "{model_name}",
        UiR.string.assistant_edit_variable_locale to "{locale}",
        UiR.string.assistant_edit_variable_timezone to "{timezone}",
        UiR.string.assistant_edit_variable_system_version to "{system_version}",
        UiR.string.assistant_edit_variable_device_info to "{device_info}",
        UiR.string.assistant_edit_variable_battery_level to "{battery_level}",
        UiR.string.assistant_edit_variable_nickname to "{nickname}",
        UiR.string.assistant_edit_variable_assistant_name to "{assistant_name}",
    )

    /** 页面用到的键集合（测试与 [PromptTransformer.supportedKeys] 比对）。 */
    val keys: Set<String> get() = entries.map { it.second }.toSet()

    /** 含时间/日期的变量：命中时提示用户会破坏缓存（页面原有的提醒）。 */
    val timeSensitiveKeys: Set<String> = setOf("{cur_date}", "{cur_time}", "{cur_datetime}")
}
