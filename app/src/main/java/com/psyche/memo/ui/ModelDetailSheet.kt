package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Trash2
import com.composables.icons.lucide.X
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ModelRegistry
import com.psyche.memo.common.Haptics
import com.psyche.memo.ui.R
import com.psyche.memo.data.db.PayloadEntityDao
import com.psyche.memo.data.model.ProviderConfig
import com.psyche.memo.ui.snackbar.AppNotification
import com.psyche.memo.ui.snackbar.NotificationType
import com.psyche.memo.ui.snackbar.SnackbarManager
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val modelDetailJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// DraggableScrollableSheet sizes (build L268-272): initial 0.8, min 0.4,
// max 0.95, snap off. Pulling the content down past the min extent closes
// the sheet immediately (shouldCloseOnMinExtent — material bottom_sheet
// extentChanged L325-330).
private const val SHEET_INITIAL_FRACTION = 0.80f
private const val SHEET_MIN_FRACTION = 0.40f
private const val SHEET_MAX_FRACTION = 0.95f

/**
 * Port of lib/features/model/widgets/model_detail_sheet.dart — the model
 * edit/create sheet (basic / advanced / built-in-tools tabs) reached via
 * long press in the model select sheet and the provider detail page's add
 * button. The Flutter sheet is a DraggableScrollableSheet (0.4-0.95,
 * initial 0.8); the resize effect is ported with the Compose-native
 * equivalent: a NestedScrollConnection lets the content list drive the
 * sheet height (list at top + drag down shrinks it, hitting 0.4 closes;
 * list at top + drag up grows it up to 0.95, then the list scrolls).
 */

// ---- BuiltInToolNames (lib/core/services/api/builtin_tools.dart L15-127) ----

internal object BuiltInToolNames {
    const val SEARCH = "search"
    const val WEB_FETCH = "web_fetch"
    const val SHELL = "shell"
    const val URL_CONTEXT = "url_context"
    const val CODE_EXECUTION = "code_execution"
    const val YOUTUBE = "youtube"
    const val CODE_INTERPRETER = "code_interpreter"
    const val IMAGE_GENERATION = "image_generation"

    private val PREFERRED_ORDER = listOf(
        SEARCH,
        URL_CONTEXT,
        CODE_EXECUTION,
        YOUTUBE,
        CODE_INTERPRETER,
        IMAGE_GENERATION,
        WEB_FETCH,
        SHELL,
    )

    fun normalize(name: String): String = when (name.trim().lowercase()) {
        "urlcontext" -> URL_CONTEXT
        "codeexecution" -> CODE_EXECUTION
        "codeinterpreter" -> CODE_INTERPRETER
        "imagegeneration" -> IMAGE_GENERATION
        "webfetch" -> WEB_FETCH
        else -> name.trim().lowercase()
    }

    fun parseAndNormalize(raw: JsonElement?): Set<String> {
        val arr = raw as? JsonArray ?: return emptySet()
        val out = mutableSetOf<String>()
        for (el in arr) {
            val text = (el as? JsonPrimitive)?.content ?: continue
            val v = normalize(text)
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }

    fun parseFromOverride(rawOverride: JsonElement?): Set<String> {
        val ov = rawOverride as? JsonObject ?: return emptySet()
        val out = parseAndNormalize(ov["builtInTools"] ?: ov["built_in_tools"]).toMutableSet()
        val legacy = ov["tools"] as? JsonObject
        legacy?.forEach { (k, v) ->
            if ((v as? JsonPrimitive)?.content == "true") {
                val n = normalize(k)
                if (n.isNotEmpty()) out.add(n)
            }
        }
        return out
    }

    fun orderedForStorage(tools: Set<String>): List<String> {
        val remaining = tools.toMutableSet()
        val ordered = PREFERRED_ORDER.filter { remaining.remove(it) }
        return ordered + remaining.toList()
    }
}

// ---- Provider classification (settings_provider.dart classify L6398) ----
// Kind values follow ProviderConfig.classifiedKind()'s Android vocabulary
// ("gemini" / "anthropic" / "openai") so clientFor() keeps working.

internal fun classifyProviderKind(id: String, providerType: String?): String {
    if (providerType != null) {
        return when (providerType) {
            "claude", "anthropic" -> "anthropic"
            "google", "gemini" -> "gemini"
            else -> "openai"
        }
    }
    val k = id.lowercase()
    return when {
        k.contains("gemini") || k.contains("google") -> "gemini"
        k.contains("claude") || k.contains("anthropic") -> "anthropic"
        else -> "openai"
    }
}

private fun uriHost(raw: String): String = runCatching {
    java.net.URI(raw.trim()).host?.lowercase() ?: ""
}.getOrDefault("")

internal fun isDeepSeekProviderConfig(cfg: ProviderConfig?): Boolean {
    if (cfg == null) return false
    return uriHost(cfg.baseUrl).contains("deepseek.com") ||
        cfg.id.trim().lowercase().contains("deepseek") ||
        cfg.name.trim().lowercase().contains("deepseek")
}

internal fun isOpenRouterProviderConfig(cfg: ProviderConfig?): Boolean {
    if (cfg == null) return false
    return uriHost(cfg.baseUrl).contains("openrouter.ai") ||
        cfg.id.lowercase().contains("openrouter")
}

internal fun isOfficialAnthropicEndpoint(cfg: ProviderConfig?): Boolean {
    if (cfg == null) return false
    val raw = cfg.baseUrl.trim()
    if (raw.isEmpty()) return true
    return uriHost(raw) == "api.anthropic.com"
}

// ---- modelSettingsToolNames / replaceModelSettingsTools (builtin_tools.dart L937-990) ----

internal fun modelSettingsToolNames(cfg: ProviderConfig): Set<String> = when (
    classifyProviderKind(cfg.id, cfg.providerType)
) {
    "gemini" -> setOf(
        BuiltInToolNames.URL_CONTEXT,
        BuiltInToolNames.CODE_EXECUTION,
        BuiltInToolNames.YOUTUBE,
    )
    "anthropic" -> if (isDeepSeekProviderConfig(cfg)) {
        emptySet()
    } else {
        setOf(BuiltInToolNames.WEB_FETCH, BuiltInToolNames.CODE_EXECUTION)
    }
    "openai" -> {
        if (isOpenRouterProviderConfig(cfg)) {
            buildSet {
                add(BuiltInToolNames.WEB_FETCH)
                add(BuiltInToolNames.IMAGE_GENERATION)
                if (cfg.useResponseApi == true) {
                    add(BuiltInToolNames.CODE_INTERPRETER)
                    add(BuiltInToolNames.SHELL)
                }
            }
        } else {
            setOf(BuiltInToolNames.CODE_INTERPRETER, BuiltInToolNames.IMAGE_GENERATION)
        }
    }
    else -> emptySet()
}

internal fun replaceModelSettingsTools(
    cfg: ProviderConfig,
    current: Set<String>,
    selected: Set<String>,
): Set<String> {
    val editable = modelSettingsToolNames(cfg)
    val result = BuiltInToolNames.parseAndNormalize(
        JsonArray(current.map { JsonPrimitive(it) }),
    ).toMutableSet()
    result.removeAll(editable)
    result.addAll(selected.map(BuiltInToolNames::normalize).filter { it in editable })
    return result
}

// ---- ModelBuiltInToolTiles.forConfig (model_edit_state_helper.dart L147-240) ----

internal data class BuiltinToolTileDef(
    val name: String,
    val titleRes: Int,
    val descRes: Int,
    val available: Boolean = true,
)

internal fun builtinToolTilesFor(cfg: ProviderConfig): List<BuiltinToolTileDef> {
    val responses = cfg.useResponseApi == true
    return when (classifyProviderKind(cfg.id, cfg.providerType)) {
        "gemini" -> listOf(
            BuiltinToolTileDef(
                BuiltInToolNames.URL_CONTEXT,
                R.string.model_detail_sheet_url_context_tool,
                R.string.model_detail_sheet_url_context_tool_description,
            ),
            BuiltinToolTileDef(
                BuiltInToolNames.CODE_EXECUTION,
                R.string.model_detail_sheet_code_execution_tool,
                R.string.model_detail_sheet_code_execution_tool_description,
            ),
            BuiltinToolTileDef(
                BuiltInToolNames.YOUTUBE,
                R.string.model_detail_sheet_youtube_tool,
                R.string.model_detail_sheet_youtube_tool_description,
            ),
        )
        "anthropic" -> {
            val official = isOfficialAnthropicEndpoint(cfg)
            listOf(
                BuiltinToolTileDef(
                    BuiltInToolNames.WEB_FETCH,
                    R.string.model_detail_sheet_web_fetch_tool,
                    R.string.model_detail_sheet_claude_web_fetch_tool_description,
                    official,
                ),
                BuiltinToolTileDef(
                    BuiltInToolNames.CODE_EXECUTION,
                    R.string.model_detail_sheet_code_execution_tool,
                    R.string.model_detail_sheet_claude_code_execution_tool_description,
                    official,
                ),
            )
        }
        "openai" -> {
            if (isOpenRouterProviderConfig(cfg)) {
                listOf(
                    BuiltinToolTileDef(
                        BuiltInToolNames.CODE_INTERPRETER,
                        R.string.model_detail_sheet_openai_code_interpreter_tool,
                        R.string.model_detail_sheet_openai_code_interpreter_tool_description,
                        responses,
                    ),
                    BuiltinToolTileDef(
                        BuiltInToolNames.WEB_FETCH,
                        R.string.model_detail_sheet_web_fetch_tool,
                        R.string.model_detail_sheet_openrouter_web_fetch_tool_description,
                    ),
                    BuiltinToolTileDef(
                        BuiltInToolNames.IMAGE_GENERATION,
                        R.string.model_detail_sheet_openai_image_generation_tool,
                        R.string.model_detail_sheet_openai_image_generation_tool_description,
                    ),
                    BuiltinToolTileDef(
                        BuiltInToolNames.SHELL,
                        R.string.model_detail_sheet_openrouter_shell_tool,
                        R.string.model_detail_sheet_openrouter_shell_tool_description,
                        responses,
                    ),
                )
            } else {
                listOf(
                    BuiltinToolTileDef(
                        BuiltInToolNames.CODE_INTERPRETER,
                        R.string.model_detail_sheet_openai_code_interpreter_tool,
                        R.string.model_detail_sheet_openai_code_interpreter_tool_description,
                        responses,
                    ),
                    BuiltinToolTileDef(
                        BuiltInToolNames.IMAGE_GENERATION,
                        R.string.model_detail_sheet_openai_image_generation_tool,
                        R.string.model_detail_sheet_openai_image_generation_tool_description,
                        responses,
                    ),
                )
            }
        }
        else -> emptyList()
    }
}

// ---- ModelEditTypeSwitch.apply (model_edit_state_helper.dart L26-140) ----

internal data class ModelEditState(
    val input: Set<String>,
    val output: Set<String>,
    val abilities: Set<String>,
    val cachedChatInput: Set<String>?,
    val cachedChatOutput: Set<String>?,
    val cachedChatAbilities: Set<String>?,
    val cachedEmbeddingInput: Set<String>?,
)

internal fun applyModelEditTypeSwitch(prev: String, next: String, s: ModelEditState): ModelEditState {
    if (prev == next) return s
    var cachedChatInput = s.cachedChatInput
    var cachedChatOutput = s.cachedChatOutput
    var cachedChatAbilities = s.cachedChatAbilities
    var cachedEmbeddingInput = s.cachedEmbeddingInput
    var input = s.input
    var output = s.output
    var abilities = s.abilities

    if (prev == "chat" && next == "embedding") {
        cachedChatInput = s.input
        cachedChatOutput = s.output
        cachedChatAbilities = s.abilities
    }
    if (prev == "embedding" && next == "chat") {
        cachedEmbeddingInput = s.input
    }
    if (next == "embedding") {
        val resolvedInput = (cachedEmbeddingInput ?: setOf("text")) + "text"
        return ModelEditState(
            input = resolvedInput,
            output = setOf("text"),
            abilities = emptySet(),
            cachedChatInput = cachedChatInput,
            cachedChatOutput = cachedChatOutput,
            cachedChatAbilities = cachedChatAbilities,
            cachedEmbeddingInput = cachedEmbeddingInput,
        )
    }
    if (prev == "embedding" && next == "chat") {
        input = (cachedChatInput ?: setOf("text")) + "text"
        output = (cachedChatOutput ?: setOf("text")) + "text"
        abilities = cachedChatAbilities ?: emptySet()
    }
    return ModelEditState(
        input = input,
        output = output,
        abilities = abilities,
        cachedChatInput = cachedChatInput,
        cachedChatOutput = cachedChatOutput,
        cachedChatAbilities = cachedChatAbilities,
        cachedEmbeddingInput = cachedEmbeddingInput,
    )
}

// ---- row-edit state holders + override parsing ----

internal class HeaderKV {
    val name = mutableStateOf("")
    val value = mutableStateOf("")
}

internal class BodyKV {
    val key = mutableStateOf("")
    val value = mutableStateOf("")
}

private fun parseModalities(raw: JsonElement?): Set<String>? {
    val arr = raw as? JsonArray ?: return null
    val out = arr.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { v -> v == "text" || v == "image" } }.toSet()
    return out.ifEmpty { null }
}

private fun parseAbilities(raw: JsonElement?): Set<String>? {
    val arr = raw as? JsonArray ?: return null
    val out = arr.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf { v -> v == "tool" || v == "reasoning" } }.toSet()
    return out.ifEmpty { null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelDetailSheet(
    container: AppContainerImpl,
    providerKey: String,
    modelId: String,
    isNew: Boolean = false,
    onDismiss: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    // Compose 1.8+：LocalClipboardManager 已废弃——统一走 LocalClipboard + ClipEntry。
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()

    // --- initState (model_detail_sheet.dart L115-215) ---
    val cfg = remember(providerKey) { container.providerConfig(providerKey) }
    val showBuiltinToolsTab = remember(providerKey) {
        cfg != null && modelSettingsToolNames(cfg).isNotEmpty()
    }
    var tab by remember { mutableIntStateOf(0) }

    val initialOv = remember(providerKey, modelId, isNew) {
        if (!isNew) cfg?.modelOverrides?.get(modelId) as? JsonObject else null
    }
    var displayModelId = modelId
    val apiIdOvText = (initialOv?.get("apiModelId") ?: initialOv?.get("api_model_id"))
        ?.let { it as? JsonPrimitive }?.content?.trim().orEmpty()
    if (apiIdOvText.isNotEmpty()) displayModelId = apiIdOvText

    var idText by remember { mutableStateOf(displayModelId) }
    var nameEdited by remember { mutableStateOf(false) }

    // applyModelOverride(applyDisplayName: true) projected on the inferFull
    // base (model_override_resolver.dart L103-146): type/name/input/output/
    // abilities each take the override when present; mods stay as-is when
    // non-empty (_nonEmptyMods).
    val base = remember(displayModelId) {
        ModelRegistry.inferFull(displayModelId.ifEmpty { "custom" })
    }
    val initial = run {
        val typeOv = (initialOv?.get("type") as? JsonPrimitive)?.content
            ?.takeIf { it == "chat" || it == "embedding" }
        val nameOv = (initialOv?.get("name") as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        val inputOv = parseModalities(initialOv?.get("input"))
        val effectiveType = typeOv ?: base.type
        val outputOv = if (effectiveType == "embedding") null else parseModalities(initialOv?.get("output"))
        val abilitiesOv = if (effectiveType == "embedding") null else parseAbilities(initialOv?.get("abilities"))
        val effInput = inputOv ?: base.input
        val effOutput = if (effectiveType == "embedding") setOf("text") else (outputOv ?: base.output)
        val effAbilities = if (effectiveType == "embedding") emptySet() else (abilitiesOv ?: base.abilities)
        Quadruple(
            effectiveType,
            nameOv ?: displayModelId,
            effInput,
            effOutput,
            effAbilities,
            if (effectiveType == "embedding") effInput else null,
        )
    }

    var type by remember { mutableStateOf(initial.first) }
    var nameText by remember { mutableStateOf(initial.second) }
    var input by remember { mutableStateOf(initial.third) }
    var output by remember { mutableStateOf(initial.fourth) }
    var abilities by remember { mutableStateOf(initial.fifth) }
    var cachedChatInput by remember { mutableStateOf<Set<String>?>(null) }
    var cachedChatOutput by remember { mutableStateOf<Set<String>?>(null) }
    var cachedChatAbilities by remember { mutableStateOf<Set<String>?>(null) }
    var cachedEmbeddingInput by remember { mutableStateOf(initial.sixth) }

    var headers by remember { mutableStateOf(listOf<HeaderKV>()) }
    var bodies by remember { mutableStateOf(listOf<BodyKV>()) }
    LaunchedEffect(initialOv) {
        (initialOv?.get("headers") as? JsonArray)?.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val kv = HeaderKV()
            kv.name.value = (obj["name"] as? JsonPrimitive)?.content ?: ""
            kv.value.value = (obj["value"] as? JsonPrimitive)?.content ?: ""
            headers = headers + kv
        }
        (initialOv?.get("body") as? JsonArray)?.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val kv = BodyKV()
            kv.key.value = (obj["key"] as? JsonPrimitive)?.content ?: ""
            kv.value.value = (obj["value"] as? JsonPrimitive)?.content ?: ""
            bodies = bodies + kv
        }
    }
    var builtInTools by remember { mutableStateOf(BuiltInToolNames.parseFromOverride(initialOv)) }
    // 上下文窗口（模型级 override 的 contextWindow）—— 上下文压缩的阈值基准。
    var contextWindowText by remember {
        mutableStateOf(
            listOf("contextWindow", "context_window", "maxContextTokens", "contextLength")
                .firstNotNullOfOrNull { key ->
                    (initialOv?.get(key) as? JsonPrimitive)?.content?.toIntOrNull()?.takeIf { it > 0 }
                }?.toString().orEmpty(),
        )
    }

    fun setType(next: String) {
        // _setType (L218-248) via ModelEditTypeSwitch.apply.
        val result = applyModelEditTypeSwitch(
            prev = type,
            next = next,
            s = ModelEditState(
                input, output, abilities,
                cachedChatInput, cachedChatOutput, cachedChatAbilities, cachedEmbeddingInput,
            ),
        )
        type = next
        input = result.input
        output = result.output
        abilities = result.abilities
        cachedChatInput = result.cachedChatInput
        cachedChatOutput = result.cachedChatOutput
        cachedChatAbilities = result.cachedChatAbilities
        cachedEmbeddingInput = result.cachedEmbeddingInput
    }

    val invalidIdMessage = stringResource(R.string.model_detail_sheet_invalid_id_error)
    val saveFailedMessage = stringResource(R.string.model_detail_sheet_save_failed_message)
    val copiedMessage = stringResource(R.string.share_provider_sheet_copied_message)

    fun save() {
        // _save (L756-853)
        val apiModelId = idText.trim()
        if (apiModelId.isEmpty() || apiModelId.length < 2) {
            SnackbarManager.show(AppNotification(message = invalidIdMessage, type = NotificationType.ERROR))
            return
        }
        val current = container.providerConfig(providerKey)
            ?: ProviderConfig(id = providerKey, name = providerKey)
        val ovMap = current.modelOverrides.toMutableMap()
        val headersJson = JsonArray(
            headers.mapNotNull { kv ->
                val name = kv.name.value.trim()
                if (name.isEmpty()) {
                    null
                } else {
                    buildJsonObject {
                        put("name", name)
                        put("value", kv.value.value)
                    }
                }
            },
        )
        val bodiesJson = JsonArray(
            bodies.mapNotNull { kv ->
                val key = kv.key.value.trim()
                if (key.isEmpty()) {
                    null
                } else {
                    buildJsonObject {
                        put("key", key)
                        put("value", kv.value.value)
                    }
                }
            },
        )
        val prevOv = if (modelId.isNotEmpty()) ovMap[modelId] as? JsonObject else null
        val builtInSet = replaceModelSettingsTools(
            cfg = current,
            current = BuiltInToolNames.parseFromOverride(prevOv),
            selected = builtInTools,
        )
        val builtInOrdered = BuiltInToolNames.orderedForStorage(builtInSet)
        val isEmbedding = type == "embedding"
        val key = if (modelId.isEmpty() || isNew) {
            // _nextModelKey (L746-755)
            val existing = current.models.toSet() + ovMap.keys
            var candidate = apiModelId
            var i = 2
            while (candidate in existing) {
                candidate = "$apiModelId#$i"
                i++
            }
            candidate
        } else {
            modelId
        }
        val entry = buildJsonObject {
            put("apiModelId", apiModelId)
            put("name", nameText.trim())
            put("type", type)
            put("input", JsonArray(input.toList().map(::JsonPrimitive)))
            if (!isEmbedding) put("output", JsonArray(output.toList().map(::JsonPrimitive)))
            if (!isEmbedding) put("abilities", JsonArray(abilities.toList().map(::JsonPrimitive)))
            put("headers", headersJson)
            put("body", bodiesJson)
            // 上下文压缩阈值基准（未填则设置页的全局默认值生效）。
            contextWindowText.trim().toIntOrNull()?.takeIf { it > 0 }?.let { put("contextWindow", it) }
            if (!isEmbedding && builtInOrdered.isNotEmpty()) {
                put("builtInTools", JsonArray(builtInOrdered.map(::JsonPrimitive)))
            }
        }
        ovMap[key] = entry
        val nextModels = if (modelId.isEmpty() || isNew) current.models + key else current.models
        val nextCfg = current.copy(modelOverrides = ovMap, models = nextModels.distinct())
        scope.launch(Dispatchers.IO) {
            runCatching {
                val dao = PayloadEntityDao(
                    container.database.writableDatabase,
                    "provider_rows",
                    primaryKey = "provider_key",
                )
                dao.upsert(
                    nextCfg.id,
                    modelDetailJson.encodeToString(ProviderConfig.serializer(), nextCfg),
                    dao.get(nextCfg.id)?.sortOrder ?: 0,
                )
            }.onSuccess {
                withContext(Dispatchers.Main) { onDismiss(true) }
            }.onFailure { e ->
                // model_detail_sheet.dart:837-841 —— ModelDetailSheet save failed。
                com.psyche.memo.common.logging.FlutterLogger.log(
                    "[ModelDetailSheet] save failed: $e\n${e.stackTraceToString()}",
                    tag = "Model",
                )
                withContext(Dispatchers.Main) {
                    SnackbarManager.show(
                        AppNotification(message = saveFailedMessage, type = NotificationType.ERROR),
                    )
                }
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { onDismiss(false) },
        sheetState = sheetState,
        containerColor = semantic.overlaySurface(cs),
        shape = RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, topEnd = MemoRadius.CARD_DP.dp),
        dragHandle = null,
    ) {
        // DraggableScrollableSheet port: the content list drives the sheet
        // height through nested scroll (the Compose-native equivalent of
        // Flutter's linked scroll). List at top + drag down shrinks the
        // sheet, hitting the min extent closes it; list at top + drag up
        // grows it up to the max, then the list scrolls.
        val screenHpx = LocalWindowInfo.current.containerSize.height.toFloat()
        var sheetFraction by remember { mutableFloatStateOf(SHEET_INITIAL_FRACTION) }
        var sheetClosing by remember { mutableStateOf(false) }
        val listScrollState = rememberScrollState()
        val sheetResizeConnection = object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                // Finger up + list at top + sheet below max: grow the sheet
                // first; once at max the list takes over.
                if (dy < 0 && listScrollState.value <= 0 && sheetFraction < SHEET_MAX_FRACTION) {
                    val grow = (-dy / screenHpx).coerceAtMost(SHEET_MAX_FRACTION - sheetFraction)
                    sheetFraction += grow
                    return Offset(0f, -grow * screenHpx)
                }
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                // Finger down with the list already at its top edge: shrink
                // the sheet; reaching the min extent closes it at once
                // (hide() slides the sheet out, onDismissRequest fires after).
                if (dy > 0 && sheetFraction > SHEET_MIN_FRACTION) {
                    val remaining = sheetFraction - SHEET_MIN_FRACTION
                    val shrink = (dy / screenHpx).coerceAtMost(remaining)
                    sheetFraction -= shrink
                    if (shrink >= remaining && !sheetClosing) {
                        sheetClosing = true
                        scope.launch { sheetState.hide() }
                    }
                    return Offset(0f, shrink * screenHpx)
                }
                return Offset.Zero
            }
        }
        val sheetHeight = with(LocalDensity.current) { screenHpx.toDp() } * sheetFraction
        Column(
            Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .nestedScroll(sheetResizeConnection),
        ) {
            Spacer(Modifier.height(8.dp))
            // Drag indicator 40x4 r999 (build L271-278).
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .background(cs.onSurface.copy(alpha = 0.2f), RoundedCornerShape(MemoRadius.PILL_DP.dp)),
            )
            Spacer(Modifier.height(8.dp))
            // Header 44dp: X close + centered title (build L306-342).
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clickable { onDismiss(false) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.X, contentDescription = null, tint = cs.onSurface, modifier = Modifier.size(22.dp))
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(if (isNew) R.string.model_detail_sheet_add_model else R.string.model_detail_sheet_edit_model),
                        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(36.dp))
            }
            // SegTabBar (build L343-354, widget L1389-1488).
            SegTabBar(
                tabs = buildList {
                    add(stringResource(R.string.model_detail_sheet_basic_tab))
                    add(stringResource(R.string.model_detail_sheet_advanced_tab))
                    if (showBuiltinToolsTab) add(stringResource(R.string.model_detail_sheet_builtin_tools_tab))
                },
                selected = tab,
                onSelect = { tab = it },
            )
            // Scrollable tab content (build L289-299).
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(listScrollState),
            ) {
                when (tab) {
                    0 -> BasicTab(
                        isNew = isNew,
                        idText = idText,
                        onIdChange = { v ->
                            idText = v
                            // existing model ID is read-only; new ids sync
                            // the name until edited (L383-391)
                            if (isNew && !nameEdited) nameText = v
                        },
                        nameText = nameText,
                        onNameChange = { v ->
                            nameText = v
                            if (!nameEdited) nameEdited = true
                        },
                        type = type,
                        onType = ::setType,
                        input = input,
                        onToggleInput = { mod ->
                            input = if (mod in input) {
                                val next = input - mod
                                if (next.isEmpty()) setOf("text") else next
                            } else {
                                input + mod
                            }
                        },
                        output = output,
                        onToggleOutput = { mod ->
                            output = if (mod in output) {
                                val next = output - mod
                                if (next.isEmpty()) setOf("text") else next
                            } else {
                                output + mod
                            }
                        },
                        abilities = abilities,
                        onToggleAbility = { ab ->
                            abilities = if (ab in abilities) abilities - ab else abilities + ab
                        },
                        onCopyId = {
                            val text = idText.trim()
                            if (text.isNotEmpty()) {
                                clipboardScope.launch {
                                    clipboard.setClipEntry(
                                        androidx.compose.ui.platform.ClipEntry(
                                            android.content.ClipData.newPlainText("", text),
                                        ),
                                    )
                                }
                                SnackbarManager.show(
                                    AppNotification(message = copiedMessage, type = NotificationType.SUCCESS),
                                )
                            }
                        },
                        view = view,
                    )
                    1 -> AdvancedTab(
                        headers = headers,
                        onAddHeader = { headers = headers + HeaderKV() },
                        onDeleteHeader = { idx -> headers = headers.filterIndexed { i, _ -> i != idx } },
                        bodies = bodies,
                        onAddBody = { bodies = bodies + BodyKV() },
                        onDeleteBody = { idx -> bodies = bodies.filterIndexed { i, _ -> i != idx } },
                        contextWindowText = contextWindowText,
                        onContextWindowChange = { contextWindowText = it },
                    )
                    else -> if (showBuiltinToolsTab && cfg != null) {
                        ToolsTab(
                            cfg = cfg,
                            type = type,
                            builtInTools = builtInTools,
                            onToggle = { name, on ->
                                builtInTools = if (on) builtInTools + name else builtInTools - name
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Spacer(Modifier.height(WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()))
            }
            // Footer (build L712-727).
            IosTileButton(
                label = stringResource(if (isNew) R.string.model_detail_sheet_add_button else R.string.model_detail_sheet_confirm_button),
                icon = if (isNew) Lucide.Plus else Lucide.Check,
                onClick = { save() },
                backgroundColor = cs.primary,
                modifier = Modifier.fillMaxWidth().padding(
                    start = 16.dp,
                    top = 8.dp,
                    end = 16.dp,
                    bottom = 10.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            )
        }
    }
}

private data class Quadruple<A, B, C, D, E, F>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D,
    val fifth: E,
    val sixth: F,
)

/** _label (L729-735): 13sp onSurface 80%. */
@Composable
private fun FieldLabel(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = text,
        style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.8f)),
    )
}

/**
 * InputDecoration: filled surfaceCard, r14, border outlineVariant 40%
 * turning primary 50% when focused (shared by all sheet fields).
 */
@Composable
private fun DetailField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    minLines: Int = 1,
    maxLines: Int = 1,
    suffix: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(semantic.surfaceCard, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(
                1.dp,
                if (focused) cs.primary.copy(alpha = 0.5f) else cs.outlineVariant.copy(alpha = 0.4f),
                RoundedCornerShape(MemoRadius.INNER_DP.dp),
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            textStyle = TextStyle(
                fontSize = 14.sp,
                color = if (readOnly) cs.onSurface.copy(alpha = 0.6f) else cs.onSurface,
            ),
            cursorBrush = SolidColor(cs.primary),
            readOnly = readOnly,
            minLines = minLines,
            maxLines = maxLines,
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = hint,
                            style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.4f)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
            interactionSource = interaction,
        )
        if (suffix != null) {
            Box(Modifier.padding(end = 4.dp)) { suffix() }
        }
    }
}

/** _SegmentedSingle (L856-928): check icon rides the selected segment. */
@Composable
private fun SegmentedSingle(options: List<String>, value: Int, onChanged: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.35f), RoundedCornerShape(MemoRadius.INNER_DP.dp)),
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == value
            Row(
                Modifier
                    .weight(1f)
                    .background(
                        if (sel) cs.primary.copy(alpha = 0.14f) else Color.Transparent,
                        RoundedCornerShape(MemoRadius.INNER_DP.dp),
                    )
                    .clickable { onChanged(i) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (sel) {
                    Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = label,
                    style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
            }
        }
    }
}

/**
 * _SegmentedMulti (L928-1046): all-selected paints the whole shell with the
 * selection color; per-segment rounding when only one is selected.
 */
@Composable
private fun SegmentedMulti(options: List<String>, isSelected: List<Boolean>, onChanged: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val allSelected = isSelected.isNotEmpty() && isSelected.all { it }
    val selectedCount = isSelected.count { it }
    val shape = RoundedCornerShape(MemoRadius.INNER_DP.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .background(
                if (allSelected) cs.primary.copy(alpha = 0.14f) else semantic.surfaceFill,
                shape,
            )
            .border(1.dp, cs.outlineVariant.copy(alpha = 0.35f), shape),
    ) {
        Row {
            options.forEachIndexed { i, label ->
                val sel = isSelected[i]
                val segShape = when {
                    allSelected -> shape
                    selectedCount == 1 && sel -> shape
                    i == 0 -> RoundedCornerShape(topStart = MemoRadius.CARD_DP.dp, bottomStart = MemoRadius.CARD_DP.dp)
                    i == options.size - 1 -> RoundedCornerShape(topEnd = MemoRadius.CARD_DP.dp, bottomEnd = MemoRadius.CARD_DP.dp)
                    else -> RoundedCornerShape(0.dp)
                }
                Row(
                    Modifier
                        .weight(1f)
                        .background(
                            if (allSelected) Color.Transparent else if (sel) cs.primary.copy(alpha = 0.14f) else Color.Transparent,
                            segShape,
                        )
                        .clickable { onChanged(i) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (sel) {
                        Icon(Lucide.Check, contentDescription = null, tint = cs.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = label,
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                    )
                }
            }
        }
    }
}

/** _HeaderRow (L1056-1143): key field + Trash2, then the value field. */
@Composable
private fun HeaderRow(kv: HeaderKV, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DetailField(
                value = kv.name.value,
                onValueChange = { kv.name.value = it },
                hint = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_header_key_hint),
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(40.dp)
                    .clickable {
                        Haptics.light(view)
                        onDelete()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Trash2,
                    contentDescription = null,
                    tint = cs.onSurface.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        DetailField(
            value = kv.value.value,
            onValueChange = { kv.value.value = it },
            hint = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_header_value_hint),
        )
    }
}

/** _BodyRow (L1143-1242): key field + Trash2, then the 3-6 line value. */
@Composable
private fun BodyRow(kv: BodyKV, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    Column(Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DetailField(
                value = kv.key.value,
                onValueChange = { kv.key.value = it },
                hint = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_body_key_hint),
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(40.dp)
                    .clickable {
                        Haptics.light(view)
                        onDelete()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Trash2,
                    contentDescription = null,
                    tint = cs.onSurface.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        DetailField(
            value = kv.value.value,
            onValueChange = { kv.value.value = it },
            hint = stringResource(com.psyche.memo.ui.R.string.model_detail_sheet_body_json_hint),
            minLines = 3,
            maxLines = 6,
        )
    }
}

/** _OutlinedAddButton (L1297-1328): primary 50% border + Plus + label. */
@Composable
private fun OutlinedAddButton(label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val view = LocalView.current
    Row(
        Modifier
            .clickable {
                Haptics.light(view)
                onClick()
            }
            .border(1.dp, cs.primary.copy(alpha = 0.5f), RoundedCornerShape(MemoRadius.INNER_DP.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Lucide.Plus, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = label, style = TextStyle(fontSize = 14.sp, color = cs.primary))
    }
}

/** Basic tab (L367-583). */
@Composable
private fun BasicTab(
    isNew: Boolean,
    idText: String,
    onIdChange: (String) -> Unit,
    nameText: String,
    onNameChange: (String) -> Unit,
    type: String,
    onType: (String) -> Unit,
    input: Set<String>,
    onToggleInput: (String) -> Unit,
    output: Set<String>,
    onToggleOutput: (String) -> Unit,
    abilities: Set<String>,
    onToggleAbility: (String) -> Unit,
    onCopyId: () -> Unit,
    view: android.view.View,
) {
    Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)) {
        FieldLabel(stringResource(R.string.model_detail_sheet_model_id_label))
        Spacer(Modifier.height(6.dp))
        DetailField(
            value = idText,
            onValueChange = onIdChange,
            hint = stringResource(R.string.model_detail_sheet_model_id_hint),
            readOnly = !isNew,
            suffix = if (!isNew) {
                {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clickable { onCopyId() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Lucide.Copy,
                            contentDescription = stringResource(R.string.share_provider_sheet_copy_button),
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            } else {
                null
            },
        )
        Spacer(Modifier.height(12.dp))
        FieldLabel(stringResource(R.string.model_detail_sheet_model_name_label))
        Spacer(Modifier.height(6.dp))
        DetailField(value = nameText, onValueChange = onNameChange, hint = "")
        Spacer(Modifier.height(12.dp))
        FieldLabel(stringResource(R.string.model_detail_sheet_model_type_label))
        Spacer(Modifier.height(6.dp))
        SegmentedSingle(
            options = listOf(
                stringResource(R.string.model_detail_sheet_chat_type),
                stringResource(R.string.model_detail_sheet_embedding_type),
            ),
            value = if (type == "chat") 0 else 1,
            onChanged = { onType(if (it == 0) "chat" else "embedding") },
        )
    }
    Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)) {
        FieldLabel(stringResource(R.string.model_detail_sheet_input_modes_label))
        Spacer(Modifier.height(6.dp))
        SegmentedMulti(
            options = listOf(
                stringResource(R.string.model_detail_sheet_text_mode),
                stringResource(R.string.model_detail_sheet_image_mode),
            ),
            isSelected = listOf("text" in input, "image" in input),
            onChanged = { idx -> onToggleInput(if (idx == 0) "text" else "image") },
        )
        if (type == "chat") {
            Spacer(Modifier.height(12.dp))
            FieldLabel(stringResource(R.string.model_detail_sheet_output_modes_label))
            Spacer(Modifier.height(6.dp))
            SegmentedMulti(
                options = listOf(
                    stringResource(R.string.model_detail_sheet_text_mode),
                    stringResource(R.string.model_detail_sheet_image_mode),
                ),
                isSelected = listOf("text" in output, "image" in output),
                onChanged = { idx -> onToggleOutput(if (idx == 0) "text" else "image") },
            )
            Spacer(Modifier.height(12.dp))
            FieldLabel(stringResource(R.string.model_detail_sheet_abilities_label))
            Spacer(Modifier.height(6.dp))
            SegmentedMulti(
                options = listOf(
                    stringResource(R.string.model_detail_sheet_tools_ability),
                    stringResource(R.string.model_detail_sheet_reasoning_ability),
                ),
                isSelected = listOf("tool" in abilities, "reasoning" in abilities),
                onChanged = { idx -> onToggleAbility(if (idx == 0) "tool" else "reasoning") },
            )
        }
    }
}

/** Advanced tab (L583-668): description, no-op override button, headers, body. */
@Composable
private fun AdvancedTab(
    headers: List<HeaderKV>,
    onAddHeader: () -> Unit,
    onDeleteHeader: (Int) -> Unit,
    bodies: List<BodyKV>,
    onAddBody: () -> Unit,
    onDeleteBody: (Int) -> Unit,
    contextWindowText: String,
    onContextWindowChange: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)) {
        Text(
            text = stringResource(R.string.model_detail_sheet_provider_override_description),
            style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.8f)),
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            // The Flutter button's onTap is an empty closure (L607-610).
            OutlinedAddButton(label = stringResource(R.string.model_detail_sheet_add_provider_override), onClick = {})
        }
    }
    // 上下文窗口（本工程新增）：上下文压缩的阈值基准 —— 模型没填就用设置里的默认值。
    Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
        Text(
            text = stringResource(R.string.model_detail_sheet_context_window_title),
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
        )
        Spacer(Modifier.height(8.dp))
        DetailField(
            value = contextWindowText,
            onValueChange = { onContextWindowChange(it.filter(Char::isDigit)) },
            hint = stringResource(R.string.model_detail_sheet_context_window_hint),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.model_detail_sheet_context_window_description),
            style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, color = cs.onSurface.copy(alpha = 0.7f)),
        )
    }
    Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
        Text(
            text = stringResource(R.string.model_detail_sheet_custom_headers_title),
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
        )
    }
    Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
        headers.forEachIndexed { i, kv -> HeaderRow(kv = kv, onDelete = { onDeleteHeader(i) }) }
        Spacer(Modifier.height(8.dp))
        OutlinedAddButton(label = stringResource(R.string.model_detail_sheet_add_header), onClick = onAddHeader)
    }
    Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
        Text(
            text = stringResource(R.string.model_detail_sheet_custom_body_title),
            style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
        )
    }
    Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
        bodies.forEachIndexed { i, kv -> BodyRow(kv = kv, onDelete = { onDeleteBody(i) }) }
        Spacer(Modifier.height(8.dp))
        OutlinedAddButton(label = stringResource(R.string.model_detail_sheet_add_body), onClick = onAddBody)
    }
}

/** Tools tab (L678-712): description + per-tool tiles with IosSwitch. */
@Composable
private fun ToolsTab(
    cfg: ProviderConfig,
    type: String,
    builtInTools: Set<String>,
    onToggle: (String, Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val disableTools = type == "embedding"
    Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp)) {
        Text(
            text = stringResource(R.string.model_detail_sheet_builtin_tools_description),
            style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.8f)),
        )
    }
    builtinToolTilesFor(cfg).forEachIndexed { index, tool ->
        val value = tool.available && tool.name in builtInTools
        val enabled = !disableTools && tool.available
        Column(Modifier.padding(start = 16.dp, top = if (index == 0) 10.dp else 8.dp, end = 16.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .alpha(if (enabled) 1f else 0.45f)
                    .background(LocalSemanticColors.current.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(tool.titleRes),
                        style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(tool.descRes),
                        style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                    )
                }
                IosSwitch(
                    value = value,
                    onValueChanged = if (enabled) {
                        { on -> onToggle(tool.name, on) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}
