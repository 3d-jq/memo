package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.skill.SkillMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 输入栏的技能选择面板 —— 直接开关当前助手的技能。
 *
 * 用户 2026-09-14：「这个 skill 管理这个你理解错了 是那个可以开关 skill 的 sheet 界面」。
 * 对应上游 RikkaHub 输入栏的「扩展」入口（`FilesPicker` + `ExtensionSelector` 的
 * Skills 分页）—— 那里就是一份技能开关清单；上游用 4 个 tab 把快捷短语/注入/世界书
 * 也塞进来，Memo 这些各有各的既有入口，所以这里只做技能这一件事。
 *
 * 打开时清一次「幽灵技能名」（用户可能在 App 外删了技能目录），与上游
 * `pruneOrphanedEnabledSkills` 一致。样式走全站统一件（[MemoSheetOptionRow] +
 * `IosSwitch`，见 `UnifiedSheetUsageTest` / `SwitchWidgetUsageTest` 两条守卫）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillSelectorSheet(
    container: AppContainerImpl,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
) {
    var assistant by remember { mutableStateOf(container.currentAssistant()) }
    var skills by remember { mutableStateOf<List<SkillMetadata>>(emptyList()) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val existing = container.skillStore.listSkills().map { it.name }.toSet()
            container.assistantStore.getAll().forEach { candidate ->
                val pruned = candidate.enabledSkills.filter { it in existing }
                if (pruned.size != candidate.enabledSkills.size) {
                    container.assistantStore.update(candidate.copy(enabledSkills = pruned))
                }
            }
            skills = container.skillStore.listSkills()
        }
        assistant = container.currentAssistant()
    }

    fun toggle(name: String, enabled: Boolean) {
        val current = assistant ?: return
        val next = if (enabled) {
            (current.enabledSkills + name).distinct()
        } else {
            current.enabledSkills - name
        }
        val updated = current.copy(enabledSkills = next)
        container.assistantStore.update(updated)
        assistant = updated
    }

    ModalBottomSheet(
        containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MemoSheetHandle(trailingGap = 0.dp)

            if (skills.isEmpty()) {
                Text(
                    text = stringResource(R.string.skills_page_empty_title),
                    style = TextStyle(
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
            } else {
                skills.forEach { skill ->
                    val enabled = skill.name in assistant?.enabledSkills.orEmpty()
                    MemoSheetOptionRow(
                        label = skill.name,
                        selected = false,
                        // 说明走 ⓘ 紧贴文字（不是挂到开关那侧）。
                        tip = skill.description,
                        // 整行可点：点哪儿都能开关（开关自己也响应）。
                        onClick = { toggle(skill.name, !enabled) },
                        trailing = {
                            IosSwitch(
                                value = enabled,
                                onValueChanged = { toggle(skill.name, it) },
                                semanticLabel = skill.name,
                            )
                        },
                    )
                }
            }

            // 进管理页（增删/导入）——上游 `onNavigateToSkills` 的同款出口。
            MemoSheetOptionRow(
                label = stringResource(R.string.skills_page_title),
                selected = false,
                icon = Lucide.Settings2,
                onClick = {
                    onDismiss()
                    onManage()
                },
            )
        }
    }
}
