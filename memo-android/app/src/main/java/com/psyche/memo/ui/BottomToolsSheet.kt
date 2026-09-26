package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
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
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.HardDrive
import com.composables.icons.lucide.Layers
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Image
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Paperclip
import com.composables.icons.lucide.Video
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
    /** MCP 入口 —— 开的是与输入栏 Hammer 同一个面板（用户 2026-09-14 要求也放进「+」）。 */
    onOpenMcp: () -> Unit = {},
    /** 工作区入口 —— 开工作区选择面板（照 RikkaHub 的 `WorkspacePickerListItem`）。 */
    onOpenWorkspace: () -> Unit = {},
    /**
     * 「打开浏览器 / 查看页面」入口 —— 用户 2026-09-26「大模型可以使用这个浏览器，用户也可以使用呀，
     * 点击查看的时候也可以使用」：**入口常驻**，判据只剩「设置里那颗全局开关开着」。
     *
     * 之前这一行只在**本会话已有活动浏览器实例**时才出现，等于把浏览器做成"模型先动手、用户才能看"
     * 的附属品 —— 用户自己想打开一个页面看看，界面上根本没有门。现在两种状态各有各的名字：
     * [browserSessionLive] 为假 ⇒ 写「打开浏览器」（点了当场建一枚会话，空白标签 + 地址栏自己输网址）；
     * 为真 ⇒ 写「查看页面」（回到模型或用户正在操作的那一枚）。
     *
     * 入口仍**不在工具卡里**（spec §12.5：浏览器页面是整会话共享的，不属于某一条消息，工具卡还会滚走），
     * 统一重做输入区之前先落在这里（跟踪在 task #130）。
     */
    browserEntryAvailable: Boolean = false,
    browserSessionLive: Boolean = false,
    onOpenBrowserPage: () -> Unit = {},
    /** 生成图片 / 生成视频入口（自研功能）—— 开生成面板，结果直接进当前对话。 */
    onOpenImageGeneration: () -> Unit = {},
    onOpenVideoGeneration: () -> Unit = {},
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
        containerColor = cs.overlaySurfaceColor(),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
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
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
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
                    .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
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
                        .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
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
                    .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
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
            // MCP 行：与输入栏 Hammer 打开同一个助手 MCP 面板（用户 2026-09-14 要求
            // 「这个 MCP 这个功能也做到加号里面的 sheet 里面吧」）。
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .clickable {
                        Haptics.light(view)
                        onOpenMcp()
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Terminal, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.settings_page_mcp),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
            }
            // 工作区行 —— RikkaHub 的输入栏「+」面板里有 `WorkspacePickerListItem`
            // （`FilesPicker.kt:141`）：一行入口 + 一个选择面板（选工作区 / 管理）。
            // 用户 2026-09-14「这个输入框加号里面加一个工作区吧 你看看 rikkhub 都有」。
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .clickable {
                        Haptics.light(view)
                        onOpenWorkspace()
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.HardDrive, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.workspace_page_title),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
            }
            // 「打开浏览器 / 查看页面」行：全局开关开着就常驻（判据由调用方给，组合期只读偏好缓存）。
            // 点了先关面板再交给调用方 —— 与 MCP / 工作区那两行同一个写法。
            if (browserEntryAvailable) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                        .clickable {
                            Haptics.light(view)
                            onOpenBrowserPage()
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Lucide.Globe, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(
                            if (browserSessionLive) R.string.browser_view_page
                            else R.string.browser_open_browser,
                        ),
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
                }
            }
            // 生成图片 / 生成视频（自研功能）：两行入口，各开一个**模型选择**面板
            //（选服务 = 绑当前助手；用户 2026-09-17「点击是选择对应的模型，不是点击
            // 使用」）。真正出图/出片由模型调 generate_image / generate_video 完成。
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .clickable {
                        Haptics.light(view)
                        onOpenImageGeneration()
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Image, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.settings_page_image_generation),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.55f), modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .clickable {
                        Haptics.light(view)
                        onOpenVideoGeneration()
                    }
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Video, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.settings_page_video_generation),
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
                    .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
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
                        .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
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
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = TextStyle(fontSize = 13.sp, color = cs.onSurface))
    }
}
