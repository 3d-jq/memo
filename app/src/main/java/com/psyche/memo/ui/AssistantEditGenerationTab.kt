package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Video
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.AssistantGenerationBinding
import com.psyche.memo.data.model.GenerationKind
import com.psyche.memo.data.model.GenerationService
import com.psyche.memo.ui.theme.LocalSemanticColors

/**
 * 助手编辑页的「生成图片」/「生成视频」tab（自研功能）。
 *
 * 与「工作区」tab 同一套形态：**按助手各配各的**，一块 [SectionCard] 里单选服务
 * （「不使用」永远排第一 = 关掉），下面一张卡覆盖参数（留空就用服务里的值）。
 * key / 地址 / 模型留在服务里（设置 → 生成图片 / 生成视频），所以多个助手共用一份
 * 配置，助手这边只决定「用不用、用哪个、参数怎么覆盖」。
 */
@Composable
fun AssistantEditGenerationTab(
    container: AppContainerImpl,
    kind: String,
    assistant: Assistant,
    onEdit: ((Assistant) -> Assistant) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val isImage = kind == GenerationKind.IMAGE

    val version by container.generationServices.version.collectAsState()
    val services = androidx.compose.runtime.remember(version, kind) {
        container.generationServices.list(kind)
    }

    val binding = assistant.generationBinding(kind)
    val selectedId = binding?.serviceId

    fun update(next: AssistantGenerationBinding?) = onEdit { current ->
        current.withGenerationBinding(kind, next)
    }

    // 与 + 面板的生成选择器同一条规则（只翻 enabled、保留上次选的 id）。
    fun select(serviceId: String?) = update(AssistantGenerationBinding.select(binding, serviceId))

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
    ) {
        item {
            SectionHeader(
                stringResource(R.string.assistant_generation_section),
                first = true,
            )
        }
        item {
            SectionCard {
                GenerationChoiceRow(
                    label = stringResource(R.string.workspace_none),
                    detail = null,
                    icon = if (isImage) Lucide.Image else Lucide.Video,
                    selected = selectedId == null,
                    onClick = { select(null) },
                )
                services.forEach { service ->
                    DividerRow()
                    GenerationChoiceRow(
                        label = service.displayName,
                        detail = service.model.ifEmpty { service.resolvedBaseUrl },
                        icon = if (isImage) Lucide.Image else Lucide.Video,
                        selected = selectedId == service.id,
                        onClick = { select(service.id) },
                    )
                }
            }
        }
        if (services.isEmpty()) {
            item {
                Text(
                    text = stringResource(
                        if (isImage) R.string.assistant_generation_no_services
                        else R.string.assistant_generation_no_services_video,
                    ),
                    style = TextStyle(fontSize = 12.sp, color = cs.onSurfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 12.dp),
                )
            }
        } else if (selectedId == null) {
            item {
                Text(
                    text = stringResource(R.string.assistant_generation_disabled_hint),
                    style = TextStyle(fontSize = 12.sp, color = cs.onSurfaceVariant),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp, start = 12.dp),
                )
            }
        }

        if (selectedId != null) {
            item {
                Spacer(Modifier.height(12.dp))
                SectionHeader(stringResource(R.string.assistant_generation_overrides_section))
            }
            item {
                SectionCard {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                        OverrideHint()
                        Spacer(Modifier.height(10.dp))
                        OverrideField(
                            label = stringResource(R.string.generation_service_editor_model_label),
                            value = binding?.model.orEmpty(),
                            onValueChange = { value ->
                                update(binding?.copy(model = value.ifBlank { null }))
                            },
                            hint = "",
                        )
                        Spacer(Modifier.height(10.dp))
                        OverrideField(
                            label = stringResource(R.string.generation_service_editor_size_label),
                            value = binding?.size.orEmpty(),
                            onValueChange = { value ->
                                update(binding?.copy(size = value.ifBlank { null }))
                            },
                            hint = "",
                        )
                        Spacer(Modifier.height(12.dp))
                        if (isImage) {
                            StepperRow(
                                label = stringResource(R.string.generation_service_editor_count_label),
                                value = (binding?.count ?: 0).let { if (it <= 0) "—" else it.toString() },
                                onMinus = {
                                    val current = binding?.count ?: 0
                                    update(binding?.copy(count = (current - 1).takeIf { it >= 1 }))
                                },
                                onPlus = {
                                    val current = binding?.count ?: 0
                                    update(
                                        binding?.copy(
                                            count = (current + 1).coerceAtMost(GenerationService.MAX_IMAGE_COUNT),
                                        ),
                                    )
                                },
                            )
                        } else {
                            StepperRow(
                                label = stringResource(R.string.generation_service_editor_seconds_label),
                                value = (binding?.durationSeconds ?: 0).let {
                                    if (it <= 0) "—"
                                    else stringResource(
                                        R.string.generation_service_editor_seconds_value,
                                        it.toString(),
                                    )
                                },
                                onMinus = {
                                    val current = binding?.durationSeconds ?: 0
                                    update(binding?.copy(durationSeconds = (current - 2).takeIf { it > 0 }))
                                },
                                onPlus = {
                                    val current = binding?.durationSeconds ?: 0
                                    update(
                                        binding?.copy(
                                            durationSeconds = (current + 2)
                                                .coerceAtMost(GenerationService.MAX_VIDEO_SECONDS),
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 单选行：图标 + 名字 +（副标题）+ 选中打勾（与工作区 tab 的行同款）。 */
@Composable
private fun GenerationChoiceRow(
    label: String,
    detail: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (selected) cs.primary else cs.onSurfaceVariant,
        )
        Column(
            modifier = Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                style = TextStyle(
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (selected) cs.primary else cs.onSurface,
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    style = TextStyle(fontSize = 11.sp, color = cs.onSurfaceVariant),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun OverrideHint() {
    Text(
        text = stringResource(R.string.assistant_generation_override_hint),
        style = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant),
    )
}

@Composable
private fun OverrideField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.72f)),
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            placeholder = {
                Text(hint, style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.45f)))
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(MemoRadius.INNER_DP.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = semantic.surfaceFill,
                unfocusedContainerColor = semantic.surfaceFill,
                focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 与生成服务编辑页同款的 ± 行（这里直接复用共享的 StepperIcon）。 */
@Composable
private fun StepperRow(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = TextStyle(fontSize = 15.sp, color = cs.onSurface),
            modifier = Modifier.weight(1f),
        )
        StepperIcon(Lucide.Minus, onMinus)
        Spacer(Modifier.width(8.dp))
        Text(value, style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.8f)))
        Spacer(Modifier.width(8.dp))
        StepperIcon(Lucide.Plus, onPlus)
    }
}
