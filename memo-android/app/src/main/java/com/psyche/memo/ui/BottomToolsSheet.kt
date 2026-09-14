package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.BookOpen
import com.composables.icons.lucide.Camera
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Workflow
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.theme.LocalSemanticColors

/**
 * Port of bottom_tools_sheet.dart (mobile): the three attachment actions as
 * 72dp rounded cards plus the instruction-injection / world-book / OCR rows.
 * The context-management row still has no ported engine, so it is omitted
 * rather than wired to a dead end.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BottomToolsSheet(
    onCamera: () -> Unit,
    onPhotos: () -> Unit,
    onUpload: () -> Unit,
    onDismiss: () -> Unit,
    ocrAvailable: Boolean = false,
    ocrEnabled: Boolean = false,
    onToggleOcr: () -> Unit = {},
    onOpenOcrPrompt: () -> Unit = {},
    onOpenInstructionInjection: () -> Unit = {},
    /** 技能入口 —— 点进技能管理页（用户 2026-09-14「输入框里面加上 skill 管理这个」）。 */
    onOpenSkills: () -> Unit = {},
    worldBooksAvailable: Boolean = false,
    onOpenWorldBook: () -> Unit = {},
    onOpenWorldBookPage: () -> Unit = {},
    onOpenContextManagement: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    val maxHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
        androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() * 0.8f
    }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = cs.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(999.dp)),
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                ToolAction(
                    icon = Lucide.Camera,
                    label = stringResource(R.string.bottom_tools_sheet_camera),
                    modifier = Modifier.weight(1f),
                ) {
                    Haptics.light(view)
                    onCamera()
                }
                ToolAction(
                    icon = Lucide.Image,
                    label = stringResource(R.string.bottom_tools_sheet_photos),
                    modifier = Modifier.weight(1f),
                ) {
                    Haptics.light(view)
                    onPhotos()
                }
                ToolAction(
                    icon = Lucide.Paperclip,
                    label = stringResource(R.string.bottom_tools_sheet_upload),
                    modifier = Modifier.weight(1f),
                ) {
                    Haptics.light(view)
                    onUpload()
                }
            }
            // 指令注入行（bottom_tools_sheet.dart：点击打开选择 sheet）。
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(semantic.surfaceCard, RoundedCornerShape(14.dp))
                    .clickable {
                        Haptics.light(view)
                        onOpenInstructionInjection()
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Layers, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.instruction_injection_title),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
            }
            // 世界书行（bottom_tools_sheet.dart：有世界书才显示；长按进管理页）。
            if (worldBooksAvailable) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(semantic.surfaceCard, RoundedCornerShape(14.dp))
                        .combinedClickable(
                            onClick = {
                                Haptics.light(view)
                                onOpenWorldBook()
                            },
                            onLongClick = {
                                Haptics.light(view)
                                onOpenWorldBookPage()
                            },
                        )
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.BookOpen, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.world_book_title),
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
                }
            }
            // 技能行：点进技能管理页。上游 RikkaHub 的输入栏「扩展」入口点开是技能/
            // 快捷短语/注入的选择面板（`FilesPicker` + `ExtensionSelector`），Memo 的
            // 技能管理本身就是一个页面，所以直接进页面（与上面「指令注入」行同款）。
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(semantic.surfaceCard, RoundedCornerShape(14.dp))
                    .clickable {
                        Haptics.light(view)
                        onOpenSkills()
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Puzzle, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.settings_page_skills),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
            }
            // 上下文管理行（bottom_tools_sheet.dart L316-328）。
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(semantic.surfaceCard, RoundedCornerShape(14.dp))
                    .clickable {
                        Haptics.light(view)
                        onOpenContextManagement()
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Workflow, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.context_management),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
            }
            // OCR 行（bottom_tools_sheet.dart：配置了 OCR 模型才显示；长按改提示词）。
            if (ocrAvailable) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(semantic.surfaceCard, RoundedCornerShape(14.dp))
                        .combinedClickable(
                            onClick = {
                                Haptics.light(view)
                                onToggleOcr()
                            },
                            onLongClick = {
                                Haptics.light(view)
                                onOpenOcrPrompt()
                            },
                        )
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Lucide.Eye,
                        contentDescription = null,
                        tint = if (ocrEnabled) cs.primary else cs.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.bottom_tools_sheet_ocr),
                        style = TextStyle(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (ocrEnabled) cs.primary else cs.onSurface,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    IosSwitch(value = ocrEnabled, onValueChanged = { onToggleOcr() })
                }
            }
        }
    }
}

@Composable
private fun ToolAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Column(
        modifier = modifier
            .height(72.dp)
            .background(semantic.surfaceCard, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = TextStyle(fontSize = 13.sp, color = cs.onSurface))
    }
}
