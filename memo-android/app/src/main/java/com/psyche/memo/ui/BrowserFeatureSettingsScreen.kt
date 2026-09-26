package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.psyche.memo.ui.R as UiR

/**
 * **浏览器功能**（本工程新增：上游 kelivo 与 RikkaHub 都没有内嵌浏览器）。
 *
 * 落在「设置 → 模型与服务」那一组里，与搜索 / MCP / 技能 / 工作区并列（用户 2026-09-26
 * 「你怎么放到偏好设置里面了呀，名字也不对应，应该叫浏览器功能呀，应该在设置里的模型与服务里呀」）：
 * 它是**设备能力**的开关，不是一条显示偏好。页名与开关名也按同一件事说 —— 早先那对
 * 「Agent 能力 / 内置浏览器」的写法，行上写着 Agent、点进去只有一颗浏览器开关。
 * 14 颗工具的清单在「设置 → 工具描述」那一页（`BuiltInToolGroup.BROWSER`），这里不抄第二份。
 *
 * 读偏好在 `LaunchedEffect` + `Dispatchers.IO` 里做 —— 组合期不许打 SQLite
 * （`CompositionThreadingTest` 会红），写法照 `ChatItemDisplaySettingsScreen:154-172`。
 *
 * 清理动作（关开关 / 清空数据）派发在**容器级 `appScope`** 而不是 `rememberCoroutineScope()`：
 * `BrowserSessionStore.closeAll` 第一步就挂起（切 Main），页面组合一离开作用域即取消 ——
 * 「点清空 → 立刻返回」会把清理掐在 `close()` 中间，留半清状态且无任何提示。
 */
@Composable
fun BrowserFeatureSettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var browserEnabled by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            browserEnabled = DisplayPrefs.browserEnabled(container)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.browser_feature_title),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp,
            ),
        ) {
            // 首段不画 SectionHeader —— 它和 MemoTopBar 是同一个字符串，同屏出现两次
            //（省略法照 ImageSettingsScreen：顶栏即标题时首段直接上卡）。
            item(key = "card_browser") {
                SettingsSectionCard {
                    SettingsSwitchRow(
                        icon = Lucide.Globe,
                        label = stringResource(UiR.string.browser_enable_title),
                        tip = stringResource(UiR.string.browser_enable_desc),
                        value = browserEnabled,
                        onToggle = { value ->
                            browserEnabled = value
                            DisplayPrefs.writeBrowserEnabled(container, value)
                            if (!value) container.appScope.launch { container.browserSessions.closeAll() }
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
            item(key = "header_data") {
                SectionHeader(stringResource(UiR.string.browser_data_title))
            }
            item(key = "card_data") {
                SettingsSectionCard {
                    SettingsRow(
                        icon = Lucide.Trash2,
                        label = stringResource(UiR.string.browser_clear),
                        onTap = { container.appScope.launch { container.browserSessions.closeAll() } },
                    )
                }
            }
        }
    }
}
