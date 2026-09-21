package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Globe
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Settings
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.SearchServiceOptions
import com.psyche.memo.ui.theme.LocalSemanticColors

/**
 * Port of search_settings_sheet.dart (mobile): web-search toggle bound to the
 * current assistant's `searchEnabled`, the manage-services entry and the
 * service picker (48dp rows, brand badge, check on the selected one).
 *
 * The built-in-search section only renders for models that support provider
 * hosted search; that capability table (builtin_tools.dart) is not ported yet,
 * so this sheet shows the client-side search switch only — same rows as
 * upstream when built-in search is unsupported.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSettingsSheet(
    container: AppContainerImpl,
    onDismiss: () -> Unit,
    onOpenServices: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val repo = container.searchSettingsRepository
    var reload by remember { mutableIntStateOf(0) }
    val services = remember(reload) { repo.services() }
    val selected = remember(reload, services.size) {
        repo.selectedIndex().coerceIn(0, (services.size - 1).coerceAtLeast(0))
    }
    val assistant = container.currentAssistant()
    var enabled by remember { mutableStateOf(assistant?.searchEnabled == true) }

    val sheetHeight = with(androidx.compose.ui.platform.LocalDensity.current) {
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
                .heightIn(max = sheetHeight)
                .verticalScroll(rememberScrollState())
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
            Spacer(Modifier.height(10.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.search_settings_sheet_title),
                    style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                )
            }
            Spacer(Modifier.height(12.dp))

            // Web search toggle card (Globe + manage + switch).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Lucide.Globe, contentDescription = null, tint = cs.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.search_settings_sheet_web_search_title),
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(R.string.search_settings_sheet_web_search_description),
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clickable { onOpenServices() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.Settings,
                        contentDescription = stringResource(R.string.search_settings_sheet_open_search_services_tooltip),
                        tint = cs.onSurface,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(4.dp))
                IosSwitch(
                    value = enabled,
                    onValueChanged = { v ->
                        enabled = v
                        container.setAssistantSearchEnabled(v)
                    },
                )
            }
            Spacer(Modifier.height(14.dp))

            // Service list (48dp rows, brand badge, check on selected).
            if (services.isNotEmpty()) {
                services.forEachIndexed { index, service ->
                    ServicePickerRow(
                        service = service,
                        selected = index == selected,
                        onTap = {
                            repo.setSelectedIndex(index)
                            onDismiss()
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
            } else {
                Text(
                    text = stringResource(R.string.search_settings_sheet_no_services_message),
                    style = TextStyle(color = cs.onSurface.copy(alpha = 0.7f)),
                )
            }
        }
    }
}

@Composable
private fun ServicePickerRow(
    service: SearchServiceOptions,
    selected: Boolean,
    onTap: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .height(48.dp)
            .background(
                if (selected) cs.primary.copy(alpha = 0.08f) else semantic.surfaceCard,
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            )
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SearchBrandBadge(service, 22.dp)
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(SearchServiceUi.nameRes(service)),
            style = TextStyle(
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (selected) cs.primary else cs.onSurface,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
        } else {
            Spacer(Modifier.width(18.dp))
        }
    }
}
