package com.psyche.memo.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide

/**
 * The one top bar every page uses — the Flutter `AppBar` the whole app shares
 * (theme_factory.dart L247-266: surface background, elevation 0, 18sp semibold
 * onSurface title, onSurface icons) with AppBar's own geometry: 56dp toolbar,
 * 56dp leading slot holding a 44dp back button (22dp ArrowLeft), title 16dp
 * after that slot (NavigationToolbar.kMiddleSpacing), 44dp action slots.
 *
 * The status-bar inset stays with the caller (page column or [modifier]) so
 * pages that already pad for it keep working unchanged.
 */
@Composable
fun MemoTopBar(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    MemoTopBarContent(onBack = onBack, modifier = modifier, actions = actions) {
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = TextStyle(
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * [MemoTopBar] with a caller-drawn title — the provider detail page puts a
 * 24dp brand avatar before the name, like the Flutter AppBar's custom title.
 */
@Composable
fun MemoTopBarContent(
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    title: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(56.dp), contentAlignment = Alignment.Center) {
            if (onBack != null) {
                IosIconButton(
                    icon = Lucide.ArrowLeft,
                    onTap = onBack,
                    color = cs.onSurface,
                    size = 22.dp,
                    contentPadding = 0.dp,
                    minSize = 44.dp,
                    semanticLabel = stringResource(R.string.settings_page_back_button),
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Box(modifier = Modifier.weight(1f)) { title() }
        actions()
        Spacer(Modifier.width(4.dp))
    }
}

/** Top-bar action slot: 44dp target with a 22dp icon. `onClick` null = disabled. */
@Composable
fun TopBarAction(
    icon: ImageVector,
    label: String,
    onClick: (() -> Unit)?,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(modifier = Modifier.width(44.dp).height(44.dp), contentAlignment = Alignment.Center) {
        IosIconButton(
            icon = icon,
            onTap = onClick,
            color = color,
            size = 22.dp,
            contentPadding = 0.dp,
            minSize = 44.dp,
            semanticLabel = label,
        )
    }
}
