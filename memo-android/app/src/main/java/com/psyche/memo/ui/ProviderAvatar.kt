package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psyche.memo.ui.theme.LocalSemanticColors

/**
 * 供应商头像的取值 → 渲染来源（`provider_avatar.dart` L37-124 的纯逻辑部分）：
 * `emoji` / `url` / `file` / `icon`（内置图标文件名，白名单校验）/ `lobehub`（CDN
 * 图标名），其余全部回落到品牌图或首字母。
 */
internal sealed interface ProviderAvatarSource {
    data class Emoji(val text: String) : ProviderAvatarSource
    data class Url(val url: String) : ProviderAvatarSource
    data class File(val path: String) : ProviderAvatarSource
    data class Asset(val asset: String) : ProviderAvatarSource
    data class Lobehub(val iconName: String) : ProviderAvatarSource
    data object Brand : ProviderAvatarSource
}

internal fun providerAvatarSource(avatarType: String?, avatarValue: String?): ProviderAvatarSource {
    val type = avatarType?.trim().orEmpty()
    val value = avatarValue?.trim().orEmpty()
    if (value.isEmpty()) return ProviderAvatarSource.Brand
    return when (type) {
        "emoji" -> ProviderAvatarSource.Emoji(value)
        "url" -> ProviderAvatarSource.Url(value)
        "file" -> ProviderAvatarSource.File(value)
        // 白名单校验：非法值直接回落（provider_avatar.dart L102-112）。
        "icon" -> BrandIconCatalog.assetOrNull(value)
            ?.let { ProviderAvatarSource.Asset(it) }
            ?: ProviderAvatarSource.Brand
        "lobehub" -> ProviderAvatarSource.Lobehub(value)
        else -> ProviderAvatarSource.Brand
    }
}

/**
 * `ProviderAvatar`（provider_avatar.dart）—— 供应商头像：自定义（emoji / 链接 /
 * 本地文件 / 内置图标 / LobeHub 图标）优先，否则品牌图，再否则首字母；
 * 外圈 0.5dp 描边（onSurface 24% 暗 / 12% 亮）。
 *
 * 覆盖值从 `provider_rows.payload` 的 `avatarType`/`avatarValue` 读（各个调用点
 * 都只有 providerKey，所以这里自己读一次，避免三十多处都加参数）。
 */
@Composable
internal fun ProviderAvatar(
    providerKey: String,
    displayName: String,
    size: Dp,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val cfg = remember(providerKey) {
        com.psyche.memo.MemoApplication.instance?.container?.providerConfig(providerKey)
    }
    val source = providerAvatarSource(cfg?.avatarType, cfg?.avatarValue)
    val label = cfg?.name?.takeIf { it.isNotBlank() } ?: displayName

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .border(0.5.dp, cs.onSurface.copy(alpha = if (semantic.isDark) 0.24f else 0.12f), CircleShape)
            .let { if (onTap != null) it.clickable { onTap() } else it },
        contentAlignment = Alignment.Center,
    ) {
        when (source) {
            is ProviderAvatarSource.Emoji -> Box(
                modifier = Modifier
                    .size(size)
                    .background(cs.primary.copy(alpha = 0.15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = source.text.take(1),
                    fontSize = (size.value * 0.5f).sp,
                )
            }
            is ProviderAvatarSource.Url -> coil.compose.AsyncImage(
                model = source.url,
                contentDescription = null,
                modifier = Modifier.size(size).clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
            is ProviderAvatarSource.File -> {
                val file = java.io.File(source.path)
                if (file.exists()) {
                    coil.compose.AsyncImage(
                        model = file,
                        contentDescription = null,
                        modifier = Modifier.size(size).clip(CircleShape),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                } else {
                    ProviderAvatarSmall(providerKey, label, size)
                }
            }
            is ProviderAvatarSource.Asset -> ProviderAvatarSmall(
                providerKey = providerKey,
                displayName = label,
                size = size,
                assetOverride = BrandIconCatalog.coilModel(source.asset),
            )
            is ProviderAvatarSource.Lobehub -> coil.compose.AsyncImage(
                model = BrandIconCatalog.lobehubIconUrl(source.iconName),
                contentDescription = null,
                modifier = Modifier.size(size * 0.7f),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
            ProviderAvatarSource.Brand -> ProviderAvatarSmall(providerKey, label, size)
        }
    }
}

/**
 * 供应商自定义头像的**裸图标**渲染（无底圆、无描边）—— 输入栏模型按钮这种
 * 只给一个小图标位置的地方用。与 [ProviderAvatar] 取同一份
 * [providerAvatarSource]，保证「供应商界面看到什么图标、这里就是什么图标」。
 */
@Composable
internal fun ModelAvatarGlyph(
    source: ProviderAvatarSource,
    cs: androidx.compose.material3.ColorScheme,
    isDark: Boolean,
) {
    val size = 20.dp
    when (source) {
        is ProviderAvatarSource.Emoji -> Text(
            text = source.text.take(1),
            fontSize = 15.sp,
            maxLines = 1,
        )
        is ProviderAvatarSource.Url -> coil.compose.AsyncImage(
            model = source.url,
            contentDescription = null,
            modifier = Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        is ProviderAvatarSource.File -> coil.compose.AsyncImage(
            model = java.io.File(source.path),
            contentDescription = null,
            modifier = Modifier.size(size).clip(androidx.compose.foundation.shape.CircleShape),
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        )
        is ProviderAvatarSource.Asset -> {
            val asset = BrandIconCatalog.coilModel(source.asset)
            coil.compose.AsyncImage(
                model = asset,
                contentDescription = null,
                colorFilter = if (isDark && BrandAssets.assetNeedsDarkInvert(asset)) {
                    androidx.compose.ui.graphics.ColorFilter.tint(cs.onSurface)
                } else {
                    null
                },
                modifier = Modifier.size(size),
            )
        }
        is ProviderAvatarSource.Lobehub -> coil.compose.AsyncImage(
            model = BrandIconCatalog.lobehubIconUrl(source.iconName),
            contentDescription = null,
            modifier = Modifier.size(size),
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        )
        ProviderAvatarSource.Brand -> Unit
    }
}
