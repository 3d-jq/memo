package com.psyche.memo.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Puzzle
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.skill.SkillMetadata
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.ui.theme.withAlpha

/**
 * 助手编辑页的「技能」tab —— 开关这台设备上已安装的 Agent Skills
 * （上游 `AssistantExtensionsPage` 的技能分段）。
 *
 * 只列**磁盘上真实存在**的技能：助手 `enabledSkills` 里的幽灵名由技能页在进入时清理
 * （见 `SkillsScreen` 的 prune），所以这里不需要再兜一层。
 */
@Composable
fun AssistantEditSkillsTab(
    container: AppContainerImpl,
    assistant: Assistant,
    onEdit: ((Assistant) -> Assistant) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var reload by remember { mutableIntStateOf(0) }
    val skills = remember(reload) { container.skillStore.listSkills() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
    ) {
        if (skills.isEmpty()) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Lucide.Puzzle,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = cs.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.skills_page_empty_title),
                        style = TextStyle(fontSize = 14.sp, color = cs.onSurfaceVariant),
                    )
                }
            }
        } else {
            item {
                SectionCard {
                    skills.forEachIndexed { index, skill ->
                        SkillToggleRow(
                            skill = skill,
                            checked = skill.name in assistant.enabledSkills,
                            onToggle = { enabled ->
                                onEdit { current ->
                                    // 保持磁盘顺序：新启用的技能跟在已有序列后面，不重排。
                                    val next = if (enabled) {
                                        current.enabledSkills + skill.name
                                    } else {
                                        current.enabledSkills - skill.name
                                    }
                                    current.copy(enabledSkills = next.distinct())
                                }
                            },
                        )
                        if (index != skills.lastIndex) DividerRow()
                    }
                }
            }
        }
    }
}

/** 技能开关行：图标 + 名字/描述 + 开关（与本地工具 tab 同款几何）。 */
@Composable
private fun SkillToggleRow(
    skill: SkillMetadata,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Lucide.Puzzle,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = cs.primary,
        )
        Spacer(Modifier.width(12.dp))
        // ⓘ 紧贴技能名（TipHuggingLabel 吃掉行内余量，开关照样贴边）。
        TipHuggingLabel(
            label = skill.name,
            tip = skill.description,
            labelStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            maxLines = 1,
        )
        // IosSwitch（不是裸 M3 Switch）—— 它按设置里的触觉开关反馈
        // （`enableHaptics = true` + LocalHapticsSettings），全站开关都走它。
        IosSwitch(
            value = checked,
            onValueChanged = onToggle,
            semanticLabel = skill.name,
        )
    }
}
