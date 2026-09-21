package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Heart
import com.composables.icons.lucide.Lucide
import com.psyche.memo.ui.R as UiR

/**
 * UI shell for `lib/features/settings/pages/sponsor_page.dart` SponsorPage.
 * Renders the original's two sections (Sponsorship Methods + Sponsors) with
 * placeholder rows — affiliate/QR code content is owned by the upstream
 * app and not in the ARB bundle, so the shell just shows the layout.
 */
@Composable
fun SponsorScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        MemoTopBar(title = stringResource(UiR.string.settings_page_sponsor), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        ) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(UiR.string.sponsor_page_methods_section_title),
                style = TextStyle(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.8f)),
                modifier = Modifier.padding(start = 12.dp, top = 0.dp, bottom = 6.dp),
            )
            SectionCard {
                EditNavRow(
                    Lucide.Heart,
                    stringResource(UiR.string.sponsor_page_afdian_title),
                    stringResource(UiR.string.sponsor_page_afdian_subtitle),
                    onTap = {},
                )
                ShellDivider()
                EditNavRow(
                    Lucide.Heart,
                    stringResource(UiR.string.sponsor_page_we_chat_title),
                    stringResource(UiR.string.sponsor_page_we_chat_subtitle),
                    onTap = {},
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(UiR.string.sponsor_page_sponsors_section_title),
                style = TextStyle(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.8f)),
                modifier = Modifier.padding(start = 12.dp, top = 0.dp, bottom = 6.dp),
            )
            SectionCard {
                Text(
                    text = stringResource(UiR.string.sponsor_page_empty),
                    style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                )
                // placeholder row so the empty state still shows the empty-list shape
                // EditNavRow(Lucide.Circle, "No sponsors yet", "")
            }
        }
    }
}
