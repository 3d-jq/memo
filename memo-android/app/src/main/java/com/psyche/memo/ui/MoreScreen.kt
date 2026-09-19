package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Trophy

/**
 * 1:1 port of more_page.dart — "LLM排行榜" section with two leaderboard
 * cards (LMArena / LiveBench) that open externally. The page title and
 * section title are hardcoded upstream (no l10n keys exist), so they stay
 * hardcoded here; the network Favicon widget is replaced with a static
 * globe icon (the favicon fetcher is a desktop/web nicety, and the card
 * name + host text are kept 1:1).
 */
@Composable
fun MoreScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        // Page intentionally has no title upstream (title: null).
        MemoTopBar(title = "", onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Text(
                text = "LLM排行榜",
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = cs.primary),
                modifier = Modifier.padding(vertical = 16.dp),
            )
            Row {
                LeaderBoardItem(
                    url = "https://lmarena.ai/leaderboard",
                    name = "LMArena",
                    modifier = Modifier.weight(1f),
                    onOpen = { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        }
                    },
                )
                Spacer(Modifier.width(8.dp))
                LeaderBoardItem(
                    url = "https://livebench.ai/#/",
                    name = "LiveBench",
                    modifier = Modifier.weight(1f),
                    onOpen = { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                        }
                    },
                )
            }
        }
    }
}

/** more_page.dart LeaderBoardItem — outlined rounded card: icon + name + host. */
@Composable
private fun LeaderBoardItem(
    url: String,
    name: String,
    modifier: Modifier = Modifier,
    onOpen: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val host = url.substringAfter("://").substringBefore('/')
    Column(
        modifier = modifier
            .border(0.6.dp, cs.outline.copy(alpha = 0.12f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .clickable { onOpen(url) }
            .padding(8.dp),
    ) {
        Icon(
            Lucide.Trophy,
            contentDescription = null,
            tint = cs.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = name,
            style = TextStyle(fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = cs.onSurface),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = host,
            style = TextStyle(fontSize = 11.sp, color = cs.onSurface.copy(alpha = 0.75f)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
