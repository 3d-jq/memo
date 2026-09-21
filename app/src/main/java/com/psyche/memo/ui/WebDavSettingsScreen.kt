package com.psyche.memo.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.backup.WebDavConfig
import com.psyche.memo.ui.R as UiR

/**
 * WebDAV 服务器设置子页（backup_page.dart `_WebDavSettingsPage` L2461-2632）：
 * URL / 用户名 / 密码（可见切换）/ 路径（默认 `memo_backups`）/ User-Agent；
 * 顶栏 Check 与底部整宽 Save 都是 `_save` —— 写 `webdav_config_v1` 后返回。
 */
@Composable
fun WebDavSettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val initial = remember { container.backupService.webDavConfig() }

    var url by rememberSaveable { mutableStateOf(initial.url) }
    var username by rememberSaveable { mutableStateOf(initial.username) }
    var password by rememberSaveable { mutableStateOf(initial.password) }
    var path by rememberSaveable { mutableStateOf(initial.path) }
    var userAgent by rememberSaveable { mutableStateOf(initial.userAgent) }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    fun save() {
        container.backupService.saveWebDavConfig(
            WebDavConfig(
                url = url.trim(),
                username = username.trim(),
                password = password,
                path = path.trim().ifEmpty { WebDavConfig.DEFAULT_PATH },
                userAgent = userAgent.trim(),
            ),
        )
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .imePadding(),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.backup_page_web_dav_server_settings),
            onBack = onBack,
            actions = {
                IosIconButton(
                    icon = Lucide.Check,
                    onTap = { save() },
                    size = 22.dp,
                )
                Spacer(Modifier.width(12.dp))
            },
        )
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
        ) {
            item {
                SettingsSectionCard {
                    IosFormField(
                        label = stringResource(UiR.string.backup_page_web_dav_server_url),
                        value = url,
                        onValueChange = { url = it },
                        inline = false,
                        hint = "https://example.com/dav",
                    )
                    IosFormField(
                        label = stringResource(UiR.string.backup_page_username),
                        value = username,
                        onValueChange = { username = it },
                        inline = false,
                    )
                    IosFormField(
                        label = stringResource(UiR.string.backup_page_password),
                        value = password,
                        onValueChange = { password = it },
                        inline = false,
                        visualTransformation = if (showPassword) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailing = {
                            IosIconButton(
                                icon = if (showPassword) Lucide.Eye else Lucide.EyeOff,
                                onTap = { showPassword = !showPassword },
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                        },
                    )
                    IosFormField(
                        label = stringResource(UiR.string.backup_page_path),
                        value = path,
                        onValueChange = { path = it },
                        inline = false,
                        hint = WebDavConfig.DEFAULT_PATH,
                    )
                    IosFormField(
                        label = stringResource(UiR.string.backup_page_user_agent),
                        value = userAgent,
                        onValueChange = { userAgent = it },
                        inline = false,
                        hint = stringResource(UiR.string.backup_page_user_agent_hint),
                    )
                }
            }
        }
        IosButton(
            label = stringResource(UiR.string.backup_page_save),
            onTap = { save() },
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            filled = true,
        )
    }
}
