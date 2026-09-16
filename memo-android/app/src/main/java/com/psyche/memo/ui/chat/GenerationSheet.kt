package com.psyche.memo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Sparkles
import com.composables.icons.lucide.Video
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.GenerationKind
import com.psyche.memo.data.model.GenerationService
import com.psyche.memo.provider.generation.GenerationException
import com.psyche.memo.provider.generation.ImageGenerationClient
import com.psyche.memo.provider.generation.VideoGenerationClient
import com.psyche.memo.ui.SettingsTextField
import com.psyche.memo.ui.SectionCard
import com.psyche.memo.ui.StepperIcon
import kotlinx.coroutines.launch

/**
 * 对话 ➕ 面板里的「生成图片 / 生成视频」面板（自研功能）。
 *
 * 交互：选服务（这块只在有多个服务时才显示）→ 写提示词 → 生成 → 结果**直接进当前
 * 对话**（图片：助手图片气泡 + 查看器；视频：文件卡，点开交给系统播放器）。
 * 视频是异步长任务，所以这里先把「生成中…（进度）」显示出来，轮询到终态再下载。
 *
 * 视觉照 Memo 自己的底面板：一张 [SectionCard] 放服务选择、一张放提示词与参数，
 * 底部一个整宽的主色按钮。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun GenerationSheet(
    container: AppContainerImpl,
    kind: String,
    onInsert: (prompt: String, images: List<String>, video: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val isImage = kind == GenerationKind.IMAGE

    val version by container.generationServices.version.collectAsState()
    val services = remember(version, kind) { container.generationServices.list(kind) }
    var serviceId by remember(services) { mutableStateOf(services.firstOrNull()?.id) }
    val service = services.firstOrNull { it.id == serviceId }

    var prompt by remember { mutableStateOf("") }
    var count by remember { mutableIntStateOf(service?.count?.coerceAtLeast(1) ?: 1) }
    var seconds by remember { mutableIntStateOf(service?.durationSeconds ?: 0) }
    var size by remember { mutableStateOf(service?.size.orEmpty()) }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }

    val client = remember { ImageGenerationClient(container.httpClient, container.generatedMediaStore) }
    val videoClient = remember { VideoGenerationClient(container.httpClient, container.generatedMediaStore) }
    val failedFmt = stringResource(com.psyche.memo.ui.R.string.generation_sheet_failed)
    val insertedMsg = stringResource(com.psyche.memo.ui.R.string.generation_sheet_inserted)

    fun run() {
        val target = service ?: return
        if (prompt.isBlank() || running) return
        running = true
        failed = false
        status = null
        scope.launch {
            try {
                if (isImage) {
                    val media = client.generate(
                        service = target,
                        request = ImageGenerationClient.ImageRequest(
                            prompt = prompt,
                            model = target.model,
                            count = count,
                            size = size,
                        ),
                    )
                    onInsert(prompt, media.map { it.path }, null)
                } else {
                    val task = videoClient.create(
                        service = target,
                        request = VideoGenerationClient.VideoRequest(
                            prompt = prompt,
                            model = target.model,
                            seconds = seconds,
                            size = size,
                        ),
                    )
                    var latest = task
                    videoClient.watch(target, task.id).collect { update ->
                        latest = update
                        status = progressLabel(update.status.name, update.progress)
                    }
                    if (latest.status != com.psyche.memo.provider.generation.VideoStatus.SUCCEEDED) {
                        throw GenerationException(latest.error ?: latest.status.name)
                    }
                    val media = videoClient.download(target, latest)
                    onInsert(prompt, emptyList(), media.path)
                }
                status = insertedMsg
                onDismiss()
            } catch (e: Exception) {
                failed = true
                status = failedFmt.format(e.message ?: e.toString())
            } finally {
                running = false
            }
        }
    }

    androidx.compose.material3.ModalBottomSheet(
        sheetState = com.psyche.memo.ui.rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = cs.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = null,
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (isImage) Lucide.Image else Lucide.Video,
                contentDescription = null,
                tint = cs.onSurface,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(
                    if (isImage) com.psyche.memo.ui.R.string.generation_services_page_image_title
                    else com.psyche.memo.ui.R.string.generation_services_page_video_title,
                ),
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(12.dp))

        if (services.isEmpty()) {
            Text(
                text = stringResource(
                    if (isImage) com.psyche.memo.ui.R.string.generation_services_page_empty
                    else com.psyche.memo.ui.R.string.generation_services_page_empty_video,
                ),
                style = TextStyle(fontSize = 13.sp, color = cs.onSurfaceVariant),
            )
            return@Column
        }

        if (services.size > 1) {
            SectionCard {
                services.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { serviceId = item.id }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = item.displayName,
                            style = TextStyle(
                                fontSize = 14.sp,
                                color = if (item.id == serviceId) cs.primary else cs.onSurface,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (item.id == serviceId) {
                            Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(16.dp))
                        }
                    }
                    if (index != services.lastIndex) Spacer(Modifier.height(1.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        SettingsTextField(
            value = prompt,
            onValueChange = { prompt = it },
            hint = stringResource(
                if (isImage) com.psyche.memo.ui.R.string.generation_sheet_prompt_hint
                else com.psyche.memo.ui.R.string.generation_sheet_prompt_hint_video,
            ),
            minLines = 3,
            maxLines = 6,
        )
        Spacer(Modifier.height(12.dp))
        SectionCard {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(com.psyche.memo.ui.R.string.generation_service_editor_size_label),
                        style = TextStyle(fontSize = 14.sp),
                        modifier = Modifier.weight(1f),
                    )
                    SettingsTextField(
                        value = size,
                        onValueChange = { size = it },
                        hint = service?.size.orEmpty().ifEmpty { "1024x1024" },
                        modifier = Modifier.width(150.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (isImage) {
                    StepperLine(
                        label = stringResource(com.psyche.memo.ui.R.string.generation_service_editor_count_label),
                        value = count.toString(),
                        onMinus = { if (count > 1) count-- },
                        onPlus = { if (count < GenerationService.MAX_IMAGE_COUNT) count++ },
                    )
                } else {
                    StepperLine(
                        label = stringResource(com.psyche.memo.ui.R.string.generation_service_editor_seconds_label),
                        value = if (seconds <= 0) "—" else stringResource(
                            com.psyche.memo.ui.R.string.generation_service_editor_seconds_value,
                            seconds.toString(),
                        ),
                        onMinus = { if (seconds > 0) seconds = (seconds - 2).coerceAtLeast(0) },
                        onPlus = { if (seconds < GenerationService.MAX_VIDEO_SECONDS) seconds += 2 },
                    )
                }
            }
        }

        status?.let { text ->
            Spacer(Modifier.height(10.dp))
            Text(
                text = text,
                style = TextStyle(fontSize = 12.sp, color = if (failed) cs.error else cs.onSurface.copy(alpha = 0.7f)),
            )
        }

        Spacer(Modifier.height(14.dp))
        val enabled = prompt.isNotBlank() && !running
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp)
                .background(
                    if (enabled) cs.primary else cs.onSurface.copy(alpha = 0.12f),
                    RoundedCornerShape(12.dp),
                )
                .clickable(enabled = enabled) { run() },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (running) {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    color = cs.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
            } else {
                Icon(Lucide.Sparkles, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = stringResource(
                    if (running) com.psyche.memo.ui.R.string.generation_sheet_generating
                    else com.psyche.memo.ui.R.string.generation_sheet_generate,
                ),
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onPrimary),
            )
        }
    }
    }
}

@Composable
private fun StepperLine(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = TextStyle(fontSize = 14.sp), modifier = Modifier.weight(1f))
        StepperIcon(Lucide.Minus, onMinus)
        Spacer(Modifier.width(8.dp))
        Text(value, style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.8f)))
        Spacer(Modifier.width(8.dp))
        StepperIcon(Lucide.Plus, onPlus)
    }
}

/** 进度行文案：`RUNNING 42%`；状态名不可读时只回落到状态名。 */
private fun progressLabel(status: String, progress: Int?): String =
    if (progress != null) "$status $progress%" else status
