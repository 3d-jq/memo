package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
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
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Key
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Terminal
import com.composables.icons.lucide.Timer
import com.composables.icons.lucide.Trash
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.McpServerConfig
import com.psyche.memo.data.model.McpToolConfig
import com.psyche.memo.data.model.deriveToolParams
import com.psyche.memo.provider.mcp.McpConnectionManager
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * MCP management — port of mcp_page.dart with its edit / JSON / timeout
 * sheets. STDIO transport is desktop-only upstream and stays out; OAuth
 * servers surface their handshake error instead of an authorize flow.
 */
@Composable
fun McpServersScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val states by container.mcpConnections.states.collectAsState()
    var reload by remember { mutableIntStateOf(0) }
    val servers = remember(reload) { container.mcpConnections.servers() }
    var editing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var showJson by remember { mutableStateOf(false) }
    var showTimeout by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<McpServerConfig?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        MemoTopBar(
            title = stringResource(R.string.mcp_assistant_sheet_title),
            onBack = onBack,
        ) {
            IconActionButton(Lucide.Timer, cs.onSurface, stringResource(R.string.mcp_timeout_dialog_title)) {
                showTimeout = true
            }
            IconActionButton(Lucide.Pencil, cs.onSurface, stringResource(R.string.mcp_json_edit_button_tooltip)) {
                showJson = true
            }
            IconActionButton(Lucide.Plus, cs.onSurface, stringResource(R.string.mcp_page_add_mcp_tooltip)) {
                adding = true
            }
            Spacer(Modifier.width(12.dp))
        }

        if (servers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.mcp_page_no_servers),
                    style = TextStyle(color = cs.onSurface.copy(alpha = 0.6f)),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
            ) {
                items(servers.size, key = { servers[it].id }) { index ->
                    val server = servers[index]
                    val state = states[server.id]
                    Spacer(Modifier.height(10.dp))
                    McpServerCard(
                        server = server,
                        status = state?.status ?: McpConnectionManager.Status.idle,
                        error = state?.error,
                        toolCount = server.tools.count { it.enabled },
                        totalTools = server.tools.size,
                        onTap = { editing = server.id },
                        onDelete = { deleteTarget = server },
                        onReconnect = { container.mcpConnections.reconnect(server.id) },
                    )
                }
            }
        }
    }

    if (adding || editing != null) {
        McpServerEditSheet(
            container = container,
            serverId = editing,
            onDismiss = {
                adding = false
                editing = null
            },
            onSaved = { reload++ },
        )
    }

    if (showJson) {
        McpJsonEditSheet(
            container = container,
            onDismiss = { showJson = false },
            onSaved = { reload++ },
        )
    }

    if (showTimeout) {
        McpTimeoutSheet(container = container, onDismiss = { showTimeout = false })
    }

    deleteTarget?.let { server ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.mcp_page_confirm_delete_title)) },
            text = { Text(stringResource(R.string.mcp_page_confirm_delete_content)) },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    container.mcpConnections.disconnect(server.id)
                    container.mcpRepository.delete(server.id)
                    SnackbarManager.show(
                        AppNotification(message = container.appContext.getString(com.psyche.memo.ui.R.string.mcp_page_server_deleted), type = NotificationType.SUCCESS),
                    )
                    reload++
                }) { Text(stringResource(R.string.mcp_page_delete), color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.mcp_page_cancel), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f))
                }
            },
        )
    }
}

@Composable
private fun McpServerCard(
    server: McpServerConfig,
    status: McpConnectionManager.Status,
    error: String?,
    toolCount: Int,
    totalTools: Int,
    onTap: () -> Unit,
    onDelete: () -> Unit,
    onReconnect: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val statusColor = when (status) {
        McpConnectionManager.Status.connected -> semantic.success
        McpConnectionManager.Status.connecting -> cs.primary
        McpConnectionManager.Status.error -> cs.error
        McpConnectionManager.Status.idle -> cs.onSurface.copy(alpha = 0.5f)
    }
    val statusText = when (status) {
        McpConnectionManager.Status.connected -> stringResource(R.string.mcp_page_status_connected)
        McpConnectionManager.Status.connecting -> stringResource(R.string.mcp_page_status_connecting)
        McpConnectionManager.Status.error -> stringResource(R.string.mcp_page_status_disconnected)
        McpConnectionManager.Status.idle -> stringResource(R.string.mcp_page_status_disconnected)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(0.6.dp, semantic.hairline, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.SMALL_DP.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Lucide.Terminal, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(12.dp)
                    .background(statusColor, CircleShape)
                    .border(2.dp, semantic.surfaceCard, CircleShape),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = server.name.ifEmpty { server.url },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(statusText, style = TextStyle(fontSize = 12.sp, color = statusColor))
                if (status == McpConnectionManager.Status.connecting) {
                    Spacer(Modifier.width(6.dp))
                    CircularProgressIndicator(strokeWidth = 2.dp, color = cs.primary, modifier = Modifier.size(12.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.mcp_page_tools_count, toolCount.toString(), totalTools.toString()),
                    style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
                    modifier = Modifier
                        .background(cs.primary.copy(alpha = 0.12f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (status == McpConnectionManager.Status.error && !error.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 11.sp, color = cs.error),
                )
            }
        }
        if (status == McpConnectionManager.Status.error) {
            IconActionButton(Lucide.RefreshCw, cs.primary, stringResource(R.string.mcp_page_reconnect)) { onReconnect() }
        }
        IconActionButton(Lucide.Trash2, cs.error, stringResource(R.string.mcp_page_delete)) { onDelete() }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun McpServerEditSheet(
    container: AppContainerImpl,
    serverId: String?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val existing = remember(serverId) { serverId?.let { container.mcpRepository.server(it) } }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var transport by remember { mutableStateOf(existing?.transport ?: "http") }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    val headers = remember {
        mutableStateListOf<Pair<String, String>>().apply {
            existing?.headers?.forEach { add(it.key to it.value) }
        }
    }
    val tools = remember {
        mutableStateListOf<McpToolConfig>().apply { addAll(existing?.tools ?: emptyList()) }
    }
    var tab by remember { mutableIntStateOf(0) }
    val urlRequiredMessage = stringResource(R.string.mcp_server_edit_sheet_url_required)
    val liveStates by container.mcpConnections.states.collectAsState()

    // tools/list 刷新是异步的：连接成功后再把最新工具并回列表（保留已有启用开关）。
    androidx.compose.runtime.LaunchedEffect(serverId, liveStates) {
        val sid = existing?.id ?: return@LaunchedEffect
        val live = container.mcpConnections.toolsFor(sid)
        if (live.isEmpty()) return@LaunchedEffect
        val merged = live.map { remote ->
            val prior = tools.firstOrNull { it.name == remote.name }
            McpToolConfig(
                enabled = prior?.enabled ?: true,
                name = remote.name,
                description = remote.description,
                // 参数规格由 inputSchema 派生（mcp_provider.dart L2337-2360），
                // 与 Dart 一样把 params 写进 payload —— 少了它工具卡就没有参数
                // chips，备份里的 MCP 工具也缺一段（原版会写）。
                params = deriveToolParams(remote.inputSchema),
                schema = remote.inputSchema,
            )
        }
        if (merged.map { it.name } != tools.map { it.name }) {
            tools.clear()
            tools.addAll(merged)
        }
    }

    fun currentServer(): McpServerConfig = McpServerConfig(
        id = existing?.id ?: java.util.UUID.randomUUID().toString().take(8),
        enabled = enabled,
        name = name.trim(),
        transport = transport,
        url = url.trim(),
        tools = tools.toList(),
        headers = headers.filter { it.first.isNotBlank() }.associate { it.first.trim() to it.second },
        oauth = existing?.oauth,
        oauthClient = existing?.oauthClient,
    )

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
                .imePadding()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                Text(
                    text = stringResource(
                        if (existing == null) R.string.mcp_server_edit_sheet_title_add
                        else R.string.mcp_server_edit_sheet_title_edit,
                    ),
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.align(Alignment.Center),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(34.dp)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.X, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                }
                // 同步工具（mcp_server_edit_sheet.dart L447-460）：**在顶栏右侧**，
                // 只在编辑已有服务器时出现（原版工具 tab 里没有计数/同步行）。
                // 原版 `refreshTools` 用库里的配置抓工具；这里先落盘当前表单再重连，
                // 否则改了 URL 点同步还是用旧地址抓（有意保留的差异，行为更直观）。
                if (existing != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(34.dp)
                            .clickable {
                                val saved = currentServer()
                                container.mcpRepository.save(saved)
                                container.mcpConnections.reconnect(saved.id)
                                onSaved()
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Lucide.RefreshCw,
                            contentDescription = stringResource(R.string.mcp_server_edit_sheet_sync_tools_tooltip),
                            tint = cs.primary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            // 原版：tab 条**只在编辑已有服务器**时出现（mcp_server_edit_sheet.dart L465
            // `if (isEdit) … _SegTabBar`）；新增时只有基础表单（L484）。
            if (existing != null) {
                Spacer(Modifier.height(12.dp))
                EditSegTabBar(
                    tabs = listOf(
                        stringResource(R.string.mcp_server_edit_sheet_tab_basic),
                        stringResource(R.string.mcp_server_edit_sheet_tab_tools),
                    ),
                    selected = tab,
                    onSelect = { tab = it },
                )
            }
            Spacer(Modifier.height(12.dp))

            Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                if (existing == null || tab == 0) {
                    // 基础表单 1:1 `_basicForm`（L185-275）：启用卡 → 名称 → 传输选择条
                    // →（SSE 提示）→ 服务器地址 → 自定义请求头（每行一张卡 + 添加按钮）。
                    SettingsSectionCard {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(R.string.mcp_server_edit_sheet_enabled_label),
                                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.onSurface),
                                modifier = Modifier.weight(1f),
                            )
                            IosSwitch(value = enabled, onValueChanged = { enabled = it })
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    McpField(
                        label = stringResource(R.string.mcp_server_edit_sheet_name_label),
                        value = name,
                        onValueChange = { name = it },
                        hint = "My MCP",
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.mcp_server_edit_sheet_transport_label),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onSurface),
                    )
                    Spacer(Modifier.height(6.dp))
                    EditSegTabBar(
                        tabs = listOf("Streamable HTTP", "SSE"),
                        selected = if (transport == "http") 0 else 1,
                        onSelect = { transport = if (it == 0) "http" else "sse" },
                    )
                    Spacer(Modifier.height(10.dp))
                    if (transport == "sse") {
                        Text(
                            text = stringResource(R.string.mcp_server_edit_sheet_sse_retry_hint),
                            style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    McpField(
                        label = stringResource(R.string.mcp_server_edit_sheet_url_label),
                        value = url,
                        onValueChange = { url = it },
                        hint = if (transport == "sse") "http://localhost:3000/sse" else "http://localhost:3000",
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.mcp_server_edit_sheet_custom_headers_title),
                        style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                    )
                    Spacer(Modifier.height(8.dp))
                    headers.forEachIndexed { index, (key, value) ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                                .border(1.dp, cs.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
                                .padding(12.dp),
                        ) {
                            McpField(
                                label = stringResource(R.string.mcp_server_edit_sheet_header_name_label),
                                value = key,
                                onValueChange = { headers[index] = it to headers[index].second },
                                hint = stringResource(R.string.mcp_server_edit_sheet_header_name_hint),
                            )
                            Spacer(Modifier.height(10.dp))
                            McpField(
                                label = stringResource(R.string.mcp_server_edit_sheet_header_value_label),
                                value = value,
                                onValueChange = { headers[index] = headers[index].first to it },
                                hint = stringResource(R.string.mcp_server_edit_sheet_header_value_hint),
                            )
                            Box(modifier = Modifier.align(Alignment.End)) {
                                Box(
                                    modifier = Modifier.size(34.dp).clickable { headers.removeAt(index) },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Lucide.Trash,
                                        contentDescription = stringResource(R.string.mcp_server_edit_sheet_remove_header_tooltip),
                                        tint = cs.error,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                    IosTileButton(
                        label = stringResource(R.string.mcp_server_edit_sheet_add_header),
                        icon = Lucide.Plus,
                        backgroundColor = cs.primary,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        onClick = { headers.add("" to "") },
                    )
                    // MCP-3 OAuth：Streamable HTTP/SSE 服务器可先在浏览器里完成
                    // 授权（系统浏览器 + 本地回环回调），令牌写进 server.oauth。
                    if (transport == "http" || transport == "sse") {
                        Spacer(Modifier.height(10.dp))
                        val oauthScope = rememberCoroutineScope()
                        var oauthBusy by remember { mutableStateOf(false) }
                        var oauthResult by remember { mutableStateOf<String?>(null) }
                        val oauthContext = androidx.compose.ui.platform.LocalContext.current
                        val authorizeDoneText = stringResource(R.string.mcp_server_edit_sheet_authorize_done)
                        val authorizeFailedText = stringResource(R.string.mcp_server_edit_sheet_authorize_failed)
                        IosTileButton(
                            label = if (oauthBusy) {
                                stringResource(R.string.message_export_sheet_exporting)
                            } else {
                                stringResource(R.string.mcp_server_edit_sheet_authorize)
                            },
                            icon = Lucide.Key,
                            backgroundColor = cs.primary.copy(alpha = 0.85f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            enabled = !oauthBusy && url.isNotBlank(),
                            onClick = {
                                oauthBusy = true
                                oauthScope.launch {
                                    val result = container.mcpConnections.authorizeOAuth(
                                        currentServer(),
                                        oauthContext,
                                    )
                                    oauthBusy = false
                                    oauthResult = result.fold(
                                        onSuccess = { authorizeDoneText },
                                        onFailure = { authorizeFailedText.format(it.message ?: it.toString()) },
                                    )
                                }
                            },
                        )
                        oauthResult?.let { message ->
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = message,
                                style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                            )
                        }
                    }
                } else {
                    // 工具卡（mcp_server_edit_sheet.dart L511-690）：原版在工具 tab 里
                    // 没有计数/同步行 —— 同步按钮在 sheet 顶栏右侧，列表就是
                    // 「每工具一张卡」。卡内：名称（bodyMedium+emphasis）+ 描述
                    // 12sp@0.7 + 参数 chips（必填高亮）+ 启用开关（与名称同排、顶端对齐）。
                    // 原「启用时才追加的卡内审批行」随审批体系一起删除（2026-09-25）。
                    if (tools.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.mcp_server_edit_sheet_no_tools_hint),
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = cs.onSurface.copy(alpha = 0.6f),
                                ),
                            )
                        }
                    }
                    tools.forEachIndexed { index, tool ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                                .border(1.dp, cs.outlineVariant.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
                                .padding(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    // 工具说明改走 ⓘ tooltip（用户 2026-09-12：「MCP 工具里面
                                    // 那个也改成 tooltip」）——原版是卡内裸排一行 12sp@0.7
                                    // （L547-560），按「不裸排提示」规则收敛成工具名旁的提示。
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        TipHuggingLabel(
                                            label = tool.name,
                                            tip = tool.description,
                                            labelStyle = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.SemiBold,
                                                color = cs.onSurface,
                                            ),
                                        )
                                    }
                                    if (tool.params.isNotEmpty()) {
                                        Spacer(Modifier.height(8.dp))
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            tool.params.forEach { param ->
                                                // 必填 = primary 字 + primary 12% 底；
                                                // 选填 = onSurface 50% 字 + onSurface 6% 底
                                                // （L571-620，边框都是各自色的 50%）。
                                                val color = if (param.required) {
                                                    cs.primary
                                                } else {
                                                    cs.onSurface.copy(alpha = 0.5f)
                                                }
                                                val bg = if (param.required) {
                                                    cs.primary.copy(alpha = 0.12f)
                                                } else {
                                                    cs.onSurface.copy(alpha = 0.06f)
                                                }
                                                Text(
                                                    text = param.name,
                                                    style = TextStyle(
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = color,
                                                    ),
                                                    modifier = Modifier
                                                        .background(bg, RoundedCornerShape(MemoRadius.PILL_DP.dp))
                                                        .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
                                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(Modifier.width(8.dp))
                                IosSwitch(
                                    value = tool.enabled,
                                    onValueChanged = { tools[index] = tool.copy(enabled = it) },
                                )
                            }
                        }
                        // 原版每张卡自带 `margin: EdgeInsets.only(bottom: 10)`。
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            IosTileButton(
                label = stringResource(R.string.mcp_server_edit_sheet_save),
                // 原版：新增用 Plus、编辑用 Check（mcp_server_edit_sheet.dart L705）。
                icon = if (existing == null) Lucide.Plus else Lucide.Check,
                backgroundColor = cs.primary,
                onClick = {
                    val server = currentServer()
                    if (server.url.isBlank()) {
                        SnackbarManager.show(
                            AppNotification(message = urlRequiredMessage, type = NotificationType.WARNING),
                        )
                        return@IosTileButton
                    }
                    container.mcpRepository.save(server)
                    if (server.enabled) container.mcpConnections.reconnect(server.id)
                    else container.mcpConnections.disconnect(server.id)
                    onSaved()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun McpField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    // 原版 `_inputRow`（L120-169）本身没有外边距：表单整体由 sheet 的 16dp 内边距与
    // 调用处的 Spacer 控制；请求头卡内同理（卡自己有 12dp padding）。
    Column(modifier = modifier) {
        Text(label, style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.8f)))
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            placeholder = hint?.let {
                { Text(it, style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.45f))) }
            },
            shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpJsonEditSheet(
    container: AppContainerImpl,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var text by remember { mutableStateOf(container.mcpConnections.servers().joinToString("\n") { "// ${it.name}" }) }
    var error by remember { mutableStateOf<String?>(null) }
    val parseFailed = stringResource(R.string.mcp_json_edit_parse_failed)
    val savedMessage = stringResource(R.string.mcp_json_edit_saved_applied)

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
                .imePadding()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                Text(
                    text = stringResource(R.string.mcp_json_edit_title),
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.align(Alignment.Center),
                )
                Box(
                    modifier = Modifier.align(Alignment.CenterStart).size(34.dp).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.X, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    error = null
                },
                minLines = 8,
                maxLines = 14,
                isError = error != null,
                textStyle = TextStyle(fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                supportingText = error?.let {
                    {
                        Text(it, style = TextStyle(fontSize = 12.sp, color = cs.error))
                    }
                },
                shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = semantic.surfaceFill,
                    unfocusedContainerColor = semantic.surfaceFill,
                    focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            IosTileButton(
                label = stringResource(R.string.mcp_server_edit_sheet_save),
                icon = Lucide.Check,
                backgroundColor = cs.primary,
                onClick = {
                    val parsed = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                    if (parsed == null) {
                        error = parseFailed
                        return@IosTileButton
                    }
                    val imported = importMcpJson(parsed, container.mcpConnections.servers())
                    imported.forEach { container.mcpRepository.save(it) }
                    onSaved()
                    onDismiss()
                    SnackbarManager.show(AppNotification(message = savedMessage, type = NotificationType.SUCCESS))
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Accepts {"mcpServers":{...}}, a bare name→config map, or a list of configs. */
internal fun importMcpJson(
    parsed: kotlinx.serialization.json.JsonElement,
    existing: List<McpServerConfig>,
): List<McpServerConfig> {
    val byId = existing.associateBy { it.id }
    val out = mutableListOf<McpServerConfig>()
    fun fromObject(name: String?, obj: JsonObject) {
        val json = Json { ignoreUnknownKeys = true }
        val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
            ?: name?.takeIf { it.isNotBlank() }?.let { byId.values.firstOrNull { s -> s.name == it }?.id }
            ?: java.util.UUID.randomUUID().toString().take(8)
        val decoded = runCatching { json.decodeFromJsonElement(McpServerConfig.serializer(), obj) }
            .getOrNull() ?: return
        out.add(decoded.copy(id = id, name = name ?: decoded.name))
    }
    when (parsed) {
        is JsonObject -> {
            val inner = parsed["mcpServers"] as? JsonObject
            if (inner != null) {
                inner.forEach { (name, value) -> (value as? JsonObject)?.let { fromObject(name, it) } }
            } else {
                parsed.forEach { (name, value) -> (value as? JsonObject)?.let { fromObject(name, it) } }
            }
        }
        is kotlinx.serialization.json.JsonArray -> parsed.forEach { element ->
            (element as? JsonObject)?.let { fromObject(null, it) }
        }
        else -> Unit
    }
    return out
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun McpTimeoutSheet(container: AppContainerImpl, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val current = remember {
        (container.preferenceRepository.readJson(TIMEOUT_KEY)?.trim()?.removeSurrounding("\"")?.toLongOrNull() ?: 60_000L) / 1000
    }
    var text by remember { mutableStateOf(current.toString()) }
    val invalid = stringResource(R.string.mcp_timeout_invalid)

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
                .imePadding()
                .padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                )
            }
            Spacer(Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                Text(
                    text = stringResource(R.string.mcp_timeout_dialog_title),
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.align(Alignment.Center),
                )
                Box(
                    modifier = Modifier.align(Alignment.CenterStart).size(34.dp).clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.X, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.mcp_timeout_seconds_label),
                style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.8f)),
            )
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { c -> c.isDigit() } },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
                shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = semantic.surfaceFill,
                    unfocusedContainerColor = semantic.surfaceFill,
                    focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            IosTileButton(
                label = stringResource(R.string.mcp_server_edit_sheet_save),
                icon = Lucide.Check,
                backgroundColor = cs.primary,
                onClick = {
                    val seconds = text.toIntOrNull()
                    if (seconds == null || seconds <= 0) {
                        SnackbarManager.show(AppNotification(message = invalid, type = NotificationType.WARNING))
                        return@IosTileButton
                    }
                    container.preferenceRepository.writeJson(
                        TIMEOUT_KEY,
                        JsonPrimitive(seconds * 1000L).toString(),
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

internal const val TIMEOUT_KEY = "mcp_request_timeout_ms_v1"
