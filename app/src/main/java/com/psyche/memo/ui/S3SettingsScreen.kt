package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Eye
import com.composables.icons.lucide.EyeOff
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.backup.S3Config
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.R as UiR

/**
 * S3 服务器设置子页（backup_page.dart `_S3SettingsPage` L2626-2868）：
 * Endpoint / Region / Bucket / Access Key ID / Secret Access Key（可见切换）/
 * Session Token（可见切换）/ Prefix / User-Agent 八个输入行 + Path-style 开关行
 * （surfaceFill r12 + 0.18 边框，行内 IosSwitch）；顶栏 Check 与底部整宽 Save
 * 都是 `_save` —— 写 `s3_config_v1` 后返回。
 */
@Composable
fun S3SettingsScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val initial = remember { container.backupService.s3Config() }

    var endpoint by rememberSaveable { mutableStateOf(initial.endpoint) }
    var region by rememberSaveable { mutableStateOf(initial.region) }
    var bucket by rememberSaveable { mutableStateOf(initial.bucket) }
    var accessKeyId by rememberSaveable { mutableStateOf(initial.accessKeyId) }
    var secretAccessKey by rememberSaveable { mutableStateOf(initial.secretAccessKey) }
    var sessionToken by rememberSaveable { mutableStateOf(initial.sessionToken) }
    var prefix by rememberSaveable { mutableStateOf(initial.prefix) }
    var userAgent by rememberSaveable { mutableStateOf(initial.userAgent) }
    var pathStyle by rememberSaveable { mutableStateOf(initial.pathStyle) }
    var showSecret by rememberSaveable { mutableStateOf(false) }
    var showToken by rememberSaveable { mutableStateOf(false) }

    fun save() {
        container.backupService.saveS3Config(
            initial.copy(
                endpoint = endpoint.trim(),
                region = region.trim().ifEmpty { "us-east-1" },
                bucket = bucket.trim(),
                accessKeyId = accessKeyId.trim(),
                // Secrets are taken verbatim: trailing spaces may be real.
                secretAccessKey = secretAccessKey,
                sessionToken = sessionToken,
                prefix = prefix.trim().ifEmpty { S3Config.DEFAULT_PREFIX },
                pathStyle = pathStyle,
                userAgent = userAgent.trim(),
            ),
        )
        onBack()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(
            title = stringResource(UiR.string.backup_page_s3_server_settings),
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
                        label = stringResource(UiR.string.backup_page_s3_endpoint),
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        inline = false,
                        hint = "https://s3.amazonaws.com",
                    )
                    IosFormField(
                        label = stringResource(UiR.string.backup_page_s3_region),
                        value = region,
                        onValueChange = { region = it },
                        inline = false,
                        hint = "us-east-1 / auto",
                    )
                    IosFormField(
                        label = stringResource(UiR.string.backup_page_s3_bucket),
                        value = bucket,
                        onValueChange = { bucket = it },
                        inline = false,
                    )
                    IosFormField(
                        label = stringResource(UiR.string.backup_page_s3_access_key_id),
                        value = accessKeyId,
                        onValueChange = { accessKeyId = it },
                        inline = false,
                    )
                    IosFormField(
                        label = stringResource(UiR.string.backup_page_s3_secret_access_key),
                        value = secretAccessKey,
                        onValueChange = { secretAccessKey = it },
                        inline = false,
                        visualTransformation = if (showSecret) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailing = {
                            IosIconButton(
                                icon = if (showSecret) Lucide.Eye else Lucide.EyeOff,
                                onTap = { showSecret = !showSecret },
                                color = cs.onSurface.copy(alpha = 0.55f),
                            )
                        },
                    )
                    IosFormField(
                        label = stringResource(UiR.string.backup_page_s3_session_token),
                        value = sessionToken,
                        onValueChange = { sessionToken = it },
                        inline = false,
                        visualTransformation = if (showToken) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        trailing = {
                            IosIconButton(
                                icon = if (showToken) Lucide.Eye else Lucide.EyeOff,
                                onTap = { showToken = !showToken },
                                color = cs.onSurface.copy(alpha = 0.55f),
                            )
                        },
                    )
                    IosFormField(
                        label = stringResource(UiR.string.backup_page_s3_prefix),
                        value = prefix,
                        onValueChange = { prefix = it },
                        inline = false,
                        hint = S3Config.DEFAULT_PREFIX,
                    )
                    IosFormField(
                        label = stringResource(UiR.string.backup_page_user_agent),
                        value = userAgent,
                        onValueChange = { userAgent = it },
                        inline = false,
                        hint = stringResource(UiR.string.backup_page_user_agent_hint),
                    )
                    // Path-style row: a bordered strip with the switch inline,
                    // matching the original's container + IosSwitch.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                            .border(
                                1.dp,
                                cs.outlineVariant.copy(alpha = 0.18f),
                                RoundedCornerShape(MemoRadius.INNER_DP.dp),
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(UiR.string.backup_page_s3_path_style),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = 14.sp,
                                color = cs.onSurface.copy(alpha = 0.85f),
                            ),
                        )
                        IosSwitch(
                            value = pathStyle,
                            onValueChanged = { pathStyle = it },
                        )
                    }
                    Spacer(Modifier.height(4.dp))
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
