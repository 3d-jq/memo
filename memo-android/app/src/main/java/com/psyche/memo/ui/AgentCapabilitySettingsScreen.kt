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
 * Agent 能力（本工程新增：上游 kelivo 与 RikkaHub 都没有内嵌浏览器）。
 *
 * 读偏好在 `LaunchedEffect` + `Dispatchers.IO` 里做 —— 组合期不许打 SQLite
 * （`CompositionThreadingTest` 会红），写法照 `ChatItemDisplaySettingsScreen:154-172`。
 *
 * 清理动作（关开关 / 清空数据）派发在**容器级 `appScope`** 而不是 `rememberCoroutineScope()`：
 * `BrowserSessionStore.closeAll` 第一步就挂起（切 Main），页面组合一离开作用域即取消 ——
 * 「点清空 → 立刻返回」会把清理掐在 `close()` 中间，留半清状态且无任何提示。
 */
@Composable
fun AgentCapabilitySettingsScreen(
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
            title = stringResource(UiR.string.agent_capabilities_title),
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
                        label = stringResource(UiR.string.agent_capabilities_browser_title),
                        tip = stringResource(UiR.string.agent_capabilities_browser_desc),
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
                SectionHeader(stringResource(UiR.string.agent_capabilities_data_title))
            }
            item(key = "card_data") {
                SettingsSectionCard {
                    SettingsRow(
                        icon = Lucide.Trash2,
                        label = stringResource(UiR.string.agent_capabilities_clear),
                        onTap = { container.appScope.launch { container.browserSessions.closeAll() } },
                    )
                }
            }
        }
    }
}
