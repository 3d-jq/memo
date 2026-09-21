package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.CircleStop
import com.composables.icons.lucide.Clipboard
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Eraser
import com.composables.icons.lucide.Languages
import com.composables.icons.lucide.Lucide
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.DefaultModelPrefs
import com.psyche.memo.llm.client.LlmMessage
import com.psyche.memo.llm.client.LlmRequest
import com.psyche.memo.llm.stream.StreamChunk
import com.psyche.memo.ui.chat.LanguageSelectSheet
import com.psyche.memo.ui.chat.TranslateLanguage
import com.psyche.memo.ui.chat.supportedLanguages
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.withAlpha
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Port of translate_page.dart: a two-card translator (input / streaming
 * output) with paste / copy / clear actions, the target-language picker and
 * the translate/stop button. The model comes from `translate_model_v1`,
 * falling back to the current assistant's chat model and then the global
 * selected model; the prompt is `translate_prompt_v1`.
 */
@Composable
fun TranslateScreen(
    container: AppContainerImpl,
    onBack: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Compose 1.8+：LocalClipboardManager 已废弃——统一走 LocalClipboard + ClipEntry。
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    var src by remember { mutableStateOf("") }
    var dst by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf<TranslateLanguage?>(null) }
    var providerId by remember { mutableStateOf<String?>(null) }
    var modelId by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showLanguageSheet by remember { mutableStateOf(false) }
    var optionsVersion by remember { mutableIntStateOf(0) }

    fun readPrefString(key: String): String? = container.preferenceRepository.readJson(key)
        ?.let { raw -> runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrDefault(raw) }
        ?.takeIf { it.isNotBlank() }

    fun writePrefString(key: String, value: String) {
        container.preferenceRepository.writeJson(key, JsonPrimitive(value).toString())
    }

    // translate_page.dart _initDefaults L54-73.
    val configuration = LocalConfiguration.current
    LaunchedEffect(Unit) {
        val savedLang = readPrefString(TARGET_LANG_KEY)?.let { code ->
            supportedLanguages.firstOrNull { it.code == code }
        }
        val languageCode = configuration.locales[0]?.language?.lowercase()
        val localeLang = if (languageCode?.startsWith("zh") == true) {
            supportedLanguages.firstOrNull { it.code == "zh-CN" }
        } else {
            supportedLanguages.firstOrNull { it.code == "en" }
        }
        lang = savedLang ?: localeLang ?: supportedLanguages.first()

        val assistant = container.currentAssistant()
        val storedModel = readPrefString(DefaultModelPrefs.TRANSLATE_MODEL_V1)
            ?.let { DefaultModelPrefs.parseModelSelection(it) }
        val globalModel = readPrefString(DefaultModelPrefs.SELECTED_MODEL_V1)
            ?.let { DefaultModelPrefs.parseModelSelection(it) }
        val resolved = storedModel
            ?: assistant?.chatModelProvider?.let { p -> assistant.chatModelId?.let { m -> p to m } }
            ?: globalModel
        providerId = resolved?.first
        modelId = resolved?.second
    }

    // 离开页面时取消在途翻译（translate_page.dart dispose L46-52）。
    DisposableEffect(Unit) {
        onDispose { job?.cancel() }
    }

    // 同 HomeScreen：模型清单读库不进组合期（消费方只有用户点开的 sheet）。
    val modelOptions = rememberLoaded(emptyList(), container, optionsVersion) {
        loadModelOptions(container, providerId, modelId)
    }
    val brandAsset = remember(modelId) { modelId?.let { BrandAssets.assetForName(it) } }

    fun stop() {
        job?.cancel()
        job = null
        loading = false
    }

    fun translate() {
        val text = src.trim()
        if (text.isEmpty()) return
        val pk = providerId
        val mid = modelId
        if (pk == null || mid == null) {
            SnackbarManager.show(
                AppNotification(
                    container.appContext.getString(R.string.home_page_please_setup_translate_model),
                    NotificationType.WARNING,
                ),
            )
            return
        }
        val template = readPrefString(DefaultModelPrefs.TRANSLATE_PROMPT_V1)
            ?: DefaultModelPrefs.DEFAULT_TRANSLATE_PROMPT
        val prompt = template
            .replace("{source_text}", text)
            .replace(
                "{target_lang}",
                container.appContext.getString(languageDisplayNameRes((lang ?: supportedLanguages.first()).code)),
            )
        val thinking = container.preferenceRepository.readJson(DefaultModelPrefs.TRANSLATE_GENERATION_THINKING_ENABLED_V1)
            ?.let { raw -> runCatching { Json.parseToJsonElement(raw).jsonPrimitive.booleanOrNull }.getOrNull() }
            ?: false

        loading = true
        dst = ""
        job = scope.launch {
            try {
                val request = LlmRequest(
                    providerId = pk,
                    modelId = mid,
                    messages = listOf(LlmMessage(role = "user", content = prompt)),
                    thinkingBudget = if (thinking) -1 else 0,
                    apiKey = container.apiKeyFor(pk) ?: "",
                    baseUrl = container.baseUrlFor(pk),
                    chatPath = container.providerConfig(pk)?.chatPath,
                    useResponseApi = container.usesResponseApi(pk),
                )
                container.clientFor(pk).streamChat(request).collect { chunk ->
                    if (chunk is StreamChunk.TextDelta && chunk.text.isNotEmpty()) {
                        // 首个 chunk 去掉前导空白，避免顶部空一行（L151-156）。
                        dst = if (dst.isEmpty()) {
                            chunk.text.replaceFirst(Regex("^\\s+"), "")
                        } else {
                            dst + chunk.text
                        }
                    }
                }
                loading = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                loading = false
                SnackbarManager.show(
                    AppNotification(
                        container.appContext.getString(R.string.home_page_translate_failed, e.toString()),
                        NotificationType.ERROR,
                    ),
                )
            }
        }
    }

    // SafeArea bottom: keep the language/translate row above the gesture bar.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
                MemoTopBar(
                    title = stringResource(R.string.desktop_nav_translate_tooltip),
                    onBack = onBack,
                ) {
                    IosIconButton(
                        icon = Lucide.Clipboard,
                        onTap = {
                            scope.launch {
                                val text = clipboard.getClipEntry()
                                    ?.clipData
                                    ?.getItemAt(0)
                                    ?.text
                                    ?.toString()
                                    ?: ""
                                if (text.isNotEmpty()) src = text
                            }
                        },
                        color = cs.onSurface,
                        size = 20.dp,
                        contentPadding = 8.dp,
                        semanticLabel = stringResource(R.string.translate_page_paste_button),
                    )
                    Spacer(Modifier.width(4.dp))
                    IosIconButton(
                        icon = Lucide.Copy,
                        onTap = {
                            scope.launch {
                                val payload = dst
                                clipboard.setClipEntry(
                                    androidx.compose.ui.platform.ClipEntry(
                                        android.content.ClipData.newPlainText("", payload),
                                    ),
                                )
                            }
                            SnackbarManager.show(
                                AppNotification(
                                    container.appContext.getString(R.string.chat_message_widget_copied_to_clipboard),
                                    NotificationType.SUCCESS,
                                ),
                            )
                        },
                        color = cs.onSurface,
                        size = 20.dp,
                        contentPadding = 8.dp,
                        semanticLabel = stringResource(R.string.translate_page_copy_result),
                    )
                    Spacer(Modifier.width(4.dp))
                    IosIconButton(
                        icon = Lucide.Eraser,
                        onTap = {
                            stop()
                            src = ""
                            dst = ""
                        },
                        color = cs.onSurface,
                        size = 20.dp,
                        contentPadding = 8.dp,
                        semanticLabel = stringResource(R.string.translate_page_clear_all),
                    )
                    Spacer(Modifier.width(4.dp))
                    // 模型品牌图标（无品牌资源时回落到 Bot，L316-345）。
                    IosIconContentButton(
                        onTap = { if (!loading) showModelSheet = true },
                        color = cs.onSurface,
                        contentPadding = 8.dp,
                        semanticLabel = stringResource(R.string.default_model_page_translate_model_title),
                    ) { tint ->
                        val asset = brandAsset
                        if (asset != null) {
                            AsyncImage(
                                model = asset,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                colorFilter = if (
                                    cs.surface.luminance() < 0.5f && BrandAssets.assetNeedsDarkInvert(asset)
                                ) {
                                    ColorFilter.tint(tint)
                                } else {
                                    null
                                },
                            )
                        } else {
                            Icon(Lucide.Bot, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }

        // 输入卡（L352-374）：固定 200dp，卡片边框 outlineVariant 25%。
        Box(modifier = Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 6.dp)) {
            TranslateCard(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                BasicTextField(
                    value = src,
                    onValueChange = { src = it },
                    textStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = cs.onSurface),
                    cursorBrush = SolidColor(cs.primary),
                    modifier = Modifier.fillMaxSize().padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 12.dp),
                    decorationBox = { inner ->
                        Box {
                            if (src.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.translate_page_input_hint),
                                    style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = withAlpha(cs.onSurface, 0.4)),
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        }

        // 输出卡（L376-398）：占满剩余高度，只读。
        Box(modifier = Modifier.weight(1f).padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 6.dp)) {
            TranslateCard(modifier = Modifier.fillMaxSize()) {
                BasicTextField(
                    value = dst,
                    onValueChange = {},
                    readOnly = true,
                    textStyle = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = cs.onSurface),
                    cursorBrush = SolidColor(Color.Transparent),
                    modifier = Modifier.fillMaxSize().padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 12.dp),
                    decorationBox = { inner ->
                        Box {
                            if (dst.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.translate_page_output_hint),
                                    style = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, color = withAlpha(cs.onSurface, 0.4)),
                                )
                            }
                            inner()
                        }
                    },
                )
            }
        }

        // 底部语言卡 + 翻译/停止按钮（L400-507）。
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .background(cs.surface)
                    .clickable { if (!loading) showLanguageSheet = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = (lang ?: supportedLanguages.first()).flag, style = TextStyle(fontSize = 18.sp))
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(languageDisplayNameRes((lang ?: supportedLanguages.first()).code)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    Lucide.ChevronDown,
                    contentDescription = null,
                    tint = withAlpha(cs.onSurface, 0.7),
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .background(cs.primary)
                    .clickable { if (loading) stop() else translate() }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                AnimatedContent(
                    targetState = loading,
                    transitionSpec = {
                        (scaleIn(tween(200)) + fadeIn(tween(200))) togetherWith
                            (scaleOut(tween(200)) + fadeOut(tween(200)))
                    },
                    label = "translateButton",
                ) { isRunning ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isRunning) {
                            Icon(
                                Lucide.CircleStop,
                                contentDescription = null,
                                tint = cs.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.chat_message_widget_stop_tooltip),
                                style = TextStyle(color = cs.onPrimary, fontWeight = FontWeight.SemiBold),
                            )
                        } else {
                            Icon(
                                Lucide.Languages,
                                contentDescription = null,
                                tint = cs.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.chat_message_widget_translate_tooltip),
                                style = TextStyle(color = cs.onPrimary, fontWeight = FontWeight.SemiBold),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showModelSheet) {
        ModelSelectSheet(
            container = container,
            options = modelOptions,
            onSelect = { option ->
                providerId = option.providerId
                modelId = option.modelId
                showModelSheet = false
                // 记住翻译模型（setTranslateModel → translate_model_v1）。
                writePrefString(
                    DefaultModelPrefs.TRANSLATE_MODEL_V1,
                    DefaultModelPrefs.encodeModelSelection(option.providerId, option.modelId),
                )
            },
            onOptionsInvalidated = { optionsVersion++ },
            onDismiss = { showModelSheet = false },
        )
    }

    if (showLanguageSheet) {
        LanguageSelectSheet(
            onSelect = { picked ->
                showLanguageSheet = false
                if (picked.code == TranslateLanguage.CLEAR_TRANSLATION) {
                    dst = ""
                } else {
                    lang = picked
                    writePrefString(TARGET_LANG_KEY, picked.code)
                }
            },
            onDismiss = { showLanguageSheet = false },
        )
    }
}

/** translate_page.dart _Card L515-535：surface 底、r12、outlineVariant 25% 边框。 */
@Composable
private fun TranslateCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .background(cs.surface)
            .border(1.dp, withAlpha(cs.outlineVariant, 0.25), RoundedCornerShape(MemoRadius.INNER_DP.dp)),
    ) { content() }
}

/** _displayNameFor L203-226 — 本地化语言名。 */
private fun languageDisplayNameRes(code: String): Int = when (code) {
    "zh-CN" -> R.string.language_display_simplified_chinese
    "en" -> R.string.language_display_english
    "zh-TW" -> R.string.language_display_traditional_chinese
    "ja" -> R.string.language_display_japanese
    "ko" -> R.string.language_display_korean
    "fr" -> R.string.language_display_french
    "de" -> R.string.language_display_german
    "it" -> R.string.language_display_italian
    "es" -> R.string.language_display_spanish
    else -> R.string.language_display_english
}

private const val TARGET_LANG_KEY = "translate_target_lang_v1"
