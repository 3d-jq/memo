package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.composables.icons.lucide.Activity
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.HeartPulse
import com.composables.icons.lucide.History
import com.composables.icons.lucide.ListOrdered
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.BingLocalOptions
import com.psyche.memo.data.model.KelivoOptions
import com.psyche.memo.data.model.SearchCommonOptions
import com.psyche.memo.data.model.SearchServiceOptions
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.launch

/**
 * Port of search_services_page.dart: configured provider rows with connection
 * state, general options (auto test / max results / timeout), long-press
 * actions (test / delete) and the full-screen editor.
 */
@Composable
fun SearchServicesScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val scope = rememberCoroutineScope()
    val repo = container.searchSettingsRepository

    var reload by remember { mutableIntStateOf(0) }
    val services = remember(reload) { repo.services() }
    val selectedIndex = remember(reload) { repo.selectedIndex() }
    val common = remember(reload) { repo.commonOptions() }
    val autoTest = remember(reload) { repo.autoTestOnLaunch() }
    val testing = remember { mutableStateMapOf<String, Boolean>() }
    // 连接状态住在容器里（原版 SettingsProvider._searchConnection）：启动时自动
    // 测试的结果与手动测试的结果共用这一份，离开页面再回来还在。
    val connectivity = container.searchConnectivity
    val connection by connectivity.states.collectAsState()
    var editing by remember { mutableStateOf<SearchServiceOptions?>(null) }
    var adding by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<SearchServiceOptions?>(null) }

    val atLeastOneMsg = stringResource(R.string.search_services_page_at_least_one_service_required)

    fun testConnection(service: SearchServiceOptions) {
        testing[service.id] = true
        scope.launch {
            // 原版手动测试用 resultSize = 1（search_services_page.dart:121-124）。
            connectivity.probe(
                service,
                SearchCommonOptions(resultSize = 1, timeout = common.timeout),
            )
            testing[service.id] = false
        }
    }

    fun deleteService(service: SearchServiceOptions) {
        if (services.size <= 1) {
            SnackbarManager.show(AppNotification(message = atLeastOneMsg, type = NotificationType.WARNING))
            return
        }
        repo.deleteService(service.id)
        reload++
    }

    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        // ---- AppBar ----
        MemoTopBar(
            title = stringResource(R.string.search_services_page_title),
            onBack = onBack,
        ) {
            IconActionButton(Lucide.Plus, cs.onSurface, stringResource(R.string.search_services_page_add_provider)) {
                adding = true
            }
            Spacer(Modifier.width(12.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item { SearchSectionHeader(stringResource(R.string.search_services_page_search_providers), first = true) }
            item {
                SectionCard {
                    services.forEachIndexed { index, service ->
                        ServiceRow(
                            service = service,
                            selected = index == selectedIndex,
                            testing = testing[service.id] == true,
                            connection = connection[service.id],
                            onTap = { editing = service },
                            onLongPress = { actionsFor = service },
                        )
                        if (index != services.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.6.dp,
                                color = cs.outlineVariant.copy(alpha = 0.18f),
                                modifier = Modifier.padding(start = 54.dp, end = 12.dp),
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
            item { SearchSectionHeader(stringResource(R.string.search_services_page_general_options)) }
            item {
                SectionCard {
                    // Auto test on launch
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                repo.setAutoTestOnLaunch(!autoTest)
                                reload++
                            }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                            Icon(Lucide.HeartPulse, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.9f), modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.search_services_page_auto_test_title),
                            style = TextStyle(fontSize = 15.sp),
                            modifier = Modifier.weight(1f),
                        )
                        IosSwitch(value = autoTest, onValueChanged = { v ->
                            repo.setAutoTestOnLaunch(v)
                            reload++
                        })
                    }
                    HorizontalDivider(
                        thickness = 0.6.dp,
                        color = cs.outlineVariant.copy(alpha = 0.18f),
                        modifier = Modifier.padding(start = 54.dp, end = 12.dp),
                    )
                    StepperRow(
                        icon = Lucide.ListOrdered,
                        label = stringResource(R.string.search_services_page_max_results),
                        valueText = common.resultSize.toString(),
                        onMinus = {
                            if (common.resultSize > 1) {
                                repo.setCommonOptions(common.copy(resultSize = common.resultSize - 1))
                                reload++
                            }
                        },
                        onPlus = {
                            if (common.resultSize < 50) {
                                repo.setCommonOptions(common.copy(resultSize = common.resultSize + 1))
                                reload++
                            }
                        },
                    )
                    HorizontalDivider(
                        thickness = 0.6.dp,
                        color = cs.outlineVariant.copy(alpha = 0.18f),
                        modifier = Modifier.padding(start = 54.dp, end = 12.dp),
                    )
                    StepperRow(
                        icon = Lucide.History,
                        label = stringResource(R.string.search_services_page_timeout_seconds),
                        valueText = (common.timeout / 1000).toString(),
                        onMinus = {
                            if (common.timeout > 1000) {
                                repo.setCommonOptions(common.copy(timeout = common.timeout - 1000))
                                reload++
                            }
                        },
                        onPlus = {
                            if (common.timeout < 30000) {
                                repo.setCommonOptions(common.copy(timeout = common.timeout + 1000))
                                reload++
                            }
                        },
                    )
                }
            }
        }
    }

    // ---- Long-press actions (test connection / delete) ----
    actionsFor?.let { service ->
        ModalActionSheet(
            onDismiss = { actionsFor = null },
            actions = listOf(
                Triple(stringResource(R.string.search_services_page_test_connection_tooltip), Lucide.Activity) {
                    actionsFor = null
                    testConnection(service)
                },
                Triple(stringResource(R.string.provider_detail_page_delete_button), Lucide.Trash2) {
                    actionsFor = null
                    deleteService(service)
                },
            ),
        )
    }

    // ---- Editor overlay ----
    if (adding) {
        SearchServiceEditorScreen(
            container = container,
            initial = null,
            canDelete = false,
            onClose = { saved ->
                adding = false
                if (saved) reload++
            },
        )
    }
    editing?.let { service ->
        SearchServiceEditorScreen(
            container = container,
            initial = service,
            canDelete = services.size > 1,
            onClose = { saved ->
                editing = null
                if (saved) reload++
            },
        )
    }
}

@Composable
private fun ServiceRow(
    service: SearchServiceOptions,
    selected: Boolean,
    testing: Boolean,
    connection: Boolean?,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val statusText: String
    val statusBg: androidx.compose.ui.graphics.Color
    val statusFg: androidx.compose.ui.graphics.Color
    when {
        testing -> {
            statusText = stringResource(R.string.search_services_page_testing_status)
            statusBg = cs.primary.copy(alpha = 0.12f)
            statusFg = cs.primary
        }
        connection == true -> {
            statusText = stringResource(R.string.search_services_page_connected_status)
            statusBg = semantic.success.copy(alpha = 0.12f)
            statusFg = semantic.success
        }
        connection == false -> {
            statusText = stringResource(R.string.search_services_page_failed_status)
            statusBg = semantic.warning.copy(alpha = 0.12f)
            statusFg = semantic.warning
        }
        else -> {
            statusText = stringResource(R.string.search_services_page_not_tested_status)
            statusBg = cs.onSurface.copy(alpha = 0.06f)
            statusFg = cs.onSurface.copy(alpha = 0.7f)
        }
    }
    val showStatus = service !is BingLocalOptions && service !is KelivoOptions

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.Center) {
            SearchBrandBadge(service, 22.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(SearchServiceUi.nameRes(service)),
            style = TextStyle(
                fontSize = 15.sp,
                color = if (selected) cs.primary else cs.onSurface,
                fontWeight = FontWeight.SemiBold,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (showStatus) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = statusText,
                style = TextStyle(fontSize = 11.sp, color = statusFg),
                maxLines = 1,
                modifier = Modifier
                    .background(statusBg, RoundedCornerShape(MemoRadius.PILL_DP.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun StepperRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    valueText: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
            Icon(icon, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.9f), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = TextStyle(fontSize = 15.sp), modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperIcon(Lucide.Minus, onMinus)
            Spacer(Modifier.width(8.dp))
            Text(valueText, style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.8f)))
            Spacer(Modifier.width(8.dp))
            StepperIcon(Lucide.Plus, onPlus)
        }
    }
}

@Composable
private fun SearchSectionHeader(text: String, first: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = text,
        style = TextStyle(
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = settingsSectionHeaderColor(cs),
        ),
        modifier = Modifier.padding(start = 12.dp, top = if (first) 2.dp else 18.dp, end = 12.dp, bottom = 6.dp),
    )
}

/** Small bottom sheet with icon+label actions (services long-press menu). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ModalActionSheet(
    onDismiss: () -> Unit,
    actions: List<Triple<String, androidx.compose.ui.graphics.vector.ImageVector, () -> Unit>>,
) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.ModalBottomSheet(
        sheetState = rememberMemoSheetState(),
        onDismissRequest = onDismiss,
        containerColor = cs.overlaySurfaceColor(),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp)) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
                )
            }
            Spacer(Modifier.height(12.dp))
            actions.forEach { (label, icon, action) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { action() }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(label, style = TextStyle(fontSize = 15.sp))
                }
            }
        }
    }
}
