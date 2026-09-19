package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.provider.mcp.McpConnectionManager
import com.psyche.memo.ui.theme.LocalSemanticColors

/**
 * Port of assistant_settings_edit_mcp_tab.dart: the assistant's MCP server
 * bindings. Only connected servers are listed, with the enabled/total tools
 * tag, a per-row switch and the clear/select-all actions.
 */
@Composable
fun AssistantEditMcpTab(
    container: AppContainerImpl,
    assistant: Assistant,
    onEdit: ((Assistant) -> Assistant) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val states by container.mcpConnections.states.collectAsState()
    val selected = assistant.mcpServerIds.toSet()
    val servers = container.mcpConnections.servers()
        .filter { states[it.id]?.status == McpConnectionManager.Status.connected }

    fun updateSelected(ids: Set<String>) {
        onEdit { it.copy(mcpServerIds = ids.toList()) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 20.dp),
    ) {
        SettingsSectionCard {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                    Icon(Lucide.Hammer, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.mcp_assistant_sheet_title),
                        style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                    )
                    Text(
                        text = stringResource(R.string.mcp_assistant_sheet_subtitle),
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.62f)),
                    )
                }
                if (servers.isNotEmpty()) {
                    Box(
                        modifier = Modifier.size(34.dp).clickable { updateSelected(emptySet()) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.X, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(2.dp))
                    Box(
                        modifier = Modifier.size(34.dp).clickable { updateSelected(servers.map { it.id }.toSet()) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Lucide.Check, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(18.dp))
                    }
                }
            }
            if (servers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 14.dp, end = 12.dp, bottom = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.assistant_edit_mcp_no_servers_message),
                        style = TextStyle(color = cs.onSurface.copy(alpha = 0.6f)),
                    )
                }
            } else {
                servers.forEach { server ->
                    SettingsIosDivider()
                    McpServerRow(
                        name = server.name,
                        toolsTag = stringResource(
                            R.string.assistant_edit_mcp_tools_count_tag,
                            server.tools.count { it.enabled }.toString(),
                            server.tools.size.toString(),
                        ),
                        selected = server.id in selected,
                        onToggle = { enabled ->
                            val ids = selected.toMutableSet()
                            if (enabled) ids.add(server.id) else ids.remove(server.id)
                            updateSelected(ids)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun McpServerRow(
    name: String,
    toolsTag: String,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!selected) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
            Icon(Lucide.Hammer, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = toolsTag,
            style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
            modifier = Modifier
                .background(cs.primary.copy(alpha = 0.10f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        Spacer(Modifier.width(8.dp))
        IosSwitch(value = selected, onValueChanged = onToggle)
    }
}
