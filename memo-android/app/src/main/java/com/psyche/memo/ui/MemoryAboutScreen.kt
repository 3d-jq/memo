package com.psyche.memo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.psyche.memo.ui.R as UiR

/**
 * Port of memory_about_page.dart: the full-screen "about memory" reference —
 * six sections, the FAQ one carrying an extra heading.
 */
@Composable
fun MemoryAboutScreen(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        MemoTopBar(
            title = stringResource(UiR.string.memory_settings_about_title),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item {
                AboutSection(
                    title = stringResource(UiR.string.memory_about_quickstart_title),
                    body = stringResource(UiR.string.memory_about_quickstart_body),
                )
            }
            item {
                AboutSection(
                    title = stringResource(UiR.string.memory_about_types_title),
                    body = stringResource(UiR.string.memory_about_types_body),
                )
            }
            item {
                AboutSection(
                    title = stringResource(UiR.string.memory_about_scope_title),
                    body = stringResource(UiR.string.memory_about_scope_body),
                )
            }
            item {
                AboutSection(
                    title = stringResource(UiR.string.memory_about_injection_title),
                    body = stringResource(UiR.string.memory_about_injection_body),
                )
            }
            item {
                AboutSection(
                    title = stringResource(UiR.string.memory_about_pipeline_title),
                    body = stringResource(UiR.string.memory_about_pipeline_body),
                )
            }
            item {
                AboutSection(
                    title = stringResource(UiR.string.memory_about_cache_title),
                    body = stringResource(UiR.string.memory_about_cache_body),
                )
            }
            item {
                AboutSection(
                    title = stringResource(UiR.string.memory_about_faq_title),
                    heading = stringResource(UiR.string.memory_about_faq_why_not_remembered_title),
                    body = stringResource(UiR.string.memory_about_faq_why_not_remembered_body),
                )
            }
        }
    }
}

/** _AboutSection L92-132 — 15sp semibold title, optional 14sp heading, 14sp body. */
@Composable
private fun AboutSection(title: String, body: String, heading: String? = null) {
    val cs = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(bottom = 22.dp)) {
        Text(
            text = title,
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.92f)),
        )
        Spacer(Modifier.height(8.dp))
        if (heading != null) {
            Text(
                text = heading,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.88f)),
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = body,
            style = TextStyle(fontSize = 14.sp, lineHeight = 21.7.sp, color = cs.onSurface.copy(alpha = 0.85f)),
        )
    }
}
