package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import com.psyche.memo.ui.overlaySurfaceColor
import com.psyche.memo.ui.rememberMemoSheetState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Hammer
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.X
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.common.Haptics
import com.psyche.memo.provider.mcp.McpConnectionManager
import com.psyche.memo.ui.IosSwitch
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.R as UiR

/**
 * Port of mcp_assistant_sheet.dart: the input-bar Hammer button's sheet —
 * connected servers with the enabled/total tools tag, a per-row switch and
 * the clear/select-all actions, writing assistant.mcpServerIds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpAssistantSheet(
    container: AppContainerImpl,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    val states by container.mcpConnections.states.collectAsState()
    var reload by remember { mutableIntStateOf(0) }
    val assistant = remember(reload) { container.currentAssistant() }
    val selected = assistant?.mcpServerIds?.toSet().orEmpty()
    val servers = container.mcpConnections.servers()
        .filter { states[it.id]?.status == McpConnectionManager.Status.connected }

    fun persist(ids: List<String>) {
        val current = container.currentAssistant() ?: return
        com.psyche.memo.data.assistant.AssistantStore(container.database.writableDatabase)
            .update(current.copy(mcpServerIds = ids))
        reload++
    }

    ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                )
            }
            Spacer(Modifier.height(10.dp))
            // Title row with clear / select-all.
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(34.dp)) {
                Text(
                    text = stringResource(UiR.string.mcp_assistant_sheet_title),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.align(Alignment.Center),
                )
                if (servers.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .size(34.dp)
                            .clickable {
                                Haptics.light(view)
                                persist(emptyList())
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.X, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(34.dp)
                            .clickable {
                                Haptics.light(view)
                                persist(servers.map { it.id })
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.Check, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                if (servers.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(UiR.string.assistant_edit_mcp_no_servers_message),
                            style = TextStyle(color = cs.onSurface.copy(alpha = 0.6f)),
                        )
                    }
                } else {
                    servers.forEach { server ->
                        val isSelected = server.id in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (com.psyche.memo.ui.ThemeState.useLayeredSheetTiles) {
                                        semantic.surfaceCardFill
                                    } else {
                                        androidx.compose.ui.graphics.Color.Transparent
                                    },
                                    RoundedCornerShape(MemoRadius.INNER_DP.dp),
                                )
                                .clickable {
                                    Haptics.light(view)
                                    val ids = selected.toMutableSet()
                                    if (isSelected) ids.remove(server.id) else ids.add(server.id)
                                    persist(ids.toList())
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Lucide.Hammer, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = server.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            ToolsTag(
                                stringResource(
                                    UiR.string.assistant_edit_mcp_tools_count_tag,
                                    server.tools.count { it.enabled }.toString(),
                                    server.tools.size.toString(),
                                ),
                            )
                            Spacer(Modifier.width(8.dp))
                            IosSwitch(
                                value = isSelected,
                                onValueChanged = { value ->
                                    val ids = selected.toMutableSet()
                                    if (value) ids.add(server.id) else ids.remove(server.id)
                                    persist(ids.toList())
                                },
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolsTag(text: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .background(cs.primary.copy(alpha = 0.10f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .border(1.dp, cs.primary.copy(alpha = 0.35f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = text,
            style = TextStyle(fontSize = 11.sp, color = cs.primary, fontWeight = FontWeight.SemiBold),
        )
    }
}
