package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.BotMessageSquare
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Zap
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.QuickPhrase
import com.psyche.memo.data.repo.QuickPhraseRepository
import com.psyche.memo.ui.reorder.ReorderableColumn
import com.psyche.memo.ui.theme.LocalSemanticColors
import java.util.UUID

/**
 * Port of assistant_settings_edit_quick_phrase_tab.dart: the per-assistant
 * quick phrases — empty state with a description, drag-reorder + swipe-delete
 * cards and a floating glass add button.
 */
@Composable
fun AssistantEditQuickPhraseTab(
    container: AppContainerImpl,
    assistant: Assistant,
    onEdit: ((Assistant) -> Assistant) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    val repo = remember(container) { QuickPhraseRepository(container.database.writableDatabase) }
    var reload by remember { mutableIntStateOf(0) }
    val phrases = remember(reload, assistant.id) { repo.forAssistant(assistant.id) }
    var editing by remember { mutableStateOf<QuickPhrase?>(null) }
    var adding by remember { mutableStateOf(false) }

    if (phrases.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(24.dp),
            ) {
                Icon(
                    Lucide.Zap,
                    contentDescription = null,
                    tint = cs.primary.copy(alpha = 0.6f),
                    modifier = Modifier.size(64.dp),
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.assistant_edit_quick_phrase_description),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                )
                Spacer(Modifier.height(24.dp))
                IosButton(
                    label = stringResource(R.string.assistant_edit_add_quick_phrase_button),
                    icon = Lucide.Plus,
                    filled = true,
                    neutral = false,
                    onTap = {
                        Haptics.light(view)
                        adding = true
                    },
                )
            }
        }
        if (adding) {
            QuickPhraseEditSheet(
                phrase = null,
                onDismiss = { adding = false },
                onSave = { title, content ->
                    repo.add(
                        QuickPhrase(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            content = content,
                            isGlobal = false,
                            assistantId = assistant.id,
                        ),
                    )
                    adding = false
                    reload++
                },
            )
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ReorderableColumn(
            items = phrases,
            keyOf = { it.id },
            onMove = { from, to ->
                val newIndex = if (to > from) to + 1 else to
                repo.reorder(from, newIndex, assistant.id)
                reload++
            },
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 80.dp),
        ) { phrase, _ ->
            SwipeDeleteRow(onDelete = {
                Haptics.light(view)
                repo.delete(phrase.id)
                reload++
            }) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                        .border(
                            0.6.dp,
                            cs.outlineVariant.copy(alpha = if (semantic.isDark) 0.08f else 0.06f),
                            RoundedCornerShape(MemoRadius.INNER_DP.dp),
                        )
                        .clickable {
                            Haptics.light(view)
                            editing = phrase
                        }
                        .padding(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Lucide.BotMessageSquare,
                                contentDescription = null,
                                tint = cs.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = phrase.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                                modifier = Modifier.weight(1f),
                            )
                            Icon(
                                Lucide.ChevronRight,
                                contentDescription = null,
                                tint = cs.onSurface.copy(alpha = 0.4f),
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = phrase.content,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        // Glass circle add button (icon-only) — assistant_settings_edit_
        // quick_phrase_tab.dart _GlassCircleButtonQP L449-514.
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 60.dp), contentAlignment = Alignment.BottomCenter) {
            GlassCircleButton(
                icon = Lucide.Plus,
                color = cs.primary,
                onClick = {
                    Haptics.light(view)
                    adding = true
                },
            )
        }
    }

    if (adding || editing != null) {
        QuickPhraseEditSheet(
            phrase = editing,
            onDismiss = {
                adding = false
                editing = null
            },
            onSave = { title, content ->
                val existing = editing
                if (existing == null) {
                    repo.add(
                        QuickPhrase(
                            id = UUID.randomUUID().toString(),
                            title = title,
                            content = content,
                            isGlobal = false,
                            assistantId = assistant.id,
                        ),
                    )
                } else {
                    repo.update(existing.copy(title = title, content = content))
                }
                adding = false
                editing = null
                reload++
            },
        )
    }
}

/** _GlassCircleButtonQP — 48dp frosted circle (surface 6% + 10% outline ring). */
@Composable
internal fun GlassCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(
                androidx.compose.ui.graphics.lerp(
                    cs.surface.copy(alpha = 0.06f),
                    cs.surface.copy(alpha = 0.06f),
                    1f,
                ),
                CircleShape,
            )
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.10f), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
    }
}
