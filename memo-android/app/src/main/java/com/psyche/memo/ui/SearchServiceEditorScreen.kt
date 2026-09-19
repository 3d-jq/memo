package com.psyche.memo.ui

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.ArrowLeft
import com.composables.icons.lucide.Check
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.KeyRound
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.RefreshCw
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.Wallet
import com.composables.icons.lucide.Trash2
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.data.model.AnySearchOptions
import com.psyche.memo.data.model.BingLocalOptions
import com.psyche.memo.data.model.BochaOptions
import com.psyche.memo.data.model.BraveOptions
import com.psyche.memo.data.model.DoubaoOptions
import com.psyche.memo.data.model.DuckDuckGoOptions
import com.psyche.memo.data.model.ExaOptions
import com.psyche.memo.data.model.FirecrawlOptions
import com.psyche.memo.data.model.GrokOptions
import com.psyche.memo.data.model.JinaOptions
import com.psyche.memo.data.model.KelivoOptions
import com.psyche.memo.data.model.LinkUpOptions
import com.psyche.memo.data.model.MetasoOptions
import com.psyche.memo.data.model.OllamaOptions
import com.psyche.memo.data.model.ParallelOptions
import com.psyche.memo.data.model.PerplexityOptions
import com.psyche.memo.data.model.QueritOptions
import com.psyche.memo.data.model.SearchCommonOptions
import com.psyche.memo.data.model.SearchServiceOptions
import com.psyche.memo.data.model.SearXNGOptions
import com.psyche.memo.data.model.SerperOptions
import com.psyche.memo.data.model.StepFunOptions
import com.psyche.memo.data.model.TavilyOptions
import com.psyche.memo.data.model.TinyFishOptions
import com.psyche.memo.data.model.YouSearchOptions
import com.psyche.memo.data.model.ZhipuOptions
import com.psyche.memo.provider.search.SearchApiKeyRotator
import com.psyche.memo.provider.search.SearchToolService
import com.psyche.memo.provider.search.SearchUsageService
import com.psyche.memo.ui.theme.LocalSemanticColors
import kotlinx.coroutines.launch

/**
 * Search service UI shared bits: brand names/assets and the per-type form
 * descriptors (search_service_editor_page.dart `_configurationFields`).
 */
internal object SearchServiceUi {

    /** Type picker order — `_providerTypes` (kelivo is unlocked separately). */
    val PROVIDER_TYPES = listOf(
        "bing_local", "duckduckgo", "tavily", "exa", "zhipu", "searxng", "linkup",
        "brave", "metaso", "jina", "ollama", "perplexity", "bocha", "doubao",
        "serper", "querit", "grok", "stepfun", "firecrawl", "tinyfish",
        "anysearch", "parallel", "you",
    )

    fun typeOf(options: SearchServiceOptions): String =
        (options.toJson()["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "bing_local"

    /** Brand asset key — `_BrandBadge._nameForService`. */
    fun brandOf(options: SearchServiceOptions): String = when (options) {
        is BingLocalOptions -> "bing"
        is DuckDuckGoOptions -> "duckduckgo"
        is TavilyOptions -> "tavily"
        is ExaOptions -> "exa"
        is ZhipuOptions -> "zhipu"
        is SearXNGOptions -> "searxng"
        is LinkUpOptions -> "linkup"
        is BraveOptions -> "brave"
        is MetasoOptions -> "metaso"
        is OllamaOptions -> "ollama"
        is JinaOptions -> "jina"
        is PerplexityOptions -> "perplexity"
        is BochaOptions -> "bocha"
        is DoubaoOptions -> "doubao"
        is SerperOptions -> "serper"
        is GrokOptions -> "grok"
        is StepFunOptions -> "stepfun"
        is FirecrawlOptions -> "firecrawl"
        is TinyFishOptions -> "tinyfish"
        is AnySearchOptions -> "anysearch"
        is ParallelOptions -> "parallel"
        is YouSearchOptions -> "you"
        is KelivoOptions -> "kelivo"
        else -> "search"
    }

    /** Display-name string resource — `_serviceTypeName` / SearchService.name. */
    fun nameRes(options: SearchServiceOptions): Int = typeNameRes(typeOf(options))

    fun typeNameRes(type: String): Int = when (type) {
        "bing_local" -> R.string.search_service_name_bing_local
        "duckduckgo" -> R.string.search_service_name_duck_duck_go
        "tavily" -> R.string.search_service_name_tavily
        "exa" -> R.string.search_service_name_exa
        "zhipu" -> R.string.search_service_name_zhipu
        "searxng" -> R.string.search_service_name_sear_x_n_g
        "linkup" -> R.string.search_service_name_link_up
        "brave" -> R.string.search_service_name_brave
        "metaso" -> R.string.search_service_name_metaso
        "jina" -> R.string.search_service_name_jina
        "ollama" -> R.string.search_service_name_ollama
        "perplexity" -> R.string.search_service_name_perplexity
        "bocha" -> R.string.search_service_name_bocha
        "doubao" -> R.string.search_service_name_doubao
        "serper" -> R.string.search_service_name_serper
        "querit" -> R.string.search_service_name_querit
        "grok" -> R.string.search_service_name_grok
        "stepfun" -> R.string.search_service_name_step_fun
        "firecrawl" -> R.string.search_service_name_firecrawl
        "tinyfish" -> R.string.search_service_name_tiny_fish
        "anysearch" -> R.string.search_service_name_any_search
        "parallel" -> R.string.search_service_name_parallel
        "you" -> R.string.search_service_name_you
        "kelivo" -> R.string.search_service_name_memo
        else -> R.string.search_service_name_bing_local
    }

    fun defaultService(type: String, id: String): SearchServiceOptions = when (type) {
        "duckduckgo" -> DuckDuckGoOptions(id = id)
        "tavily" -> TavilyOptions(id = id, apiKey = "")
        "exa" -> ExaOptions(id = id, apiKey = "")
        "zhipu" -> ZhipuOptions(id = id, apiKey = "")
        "searxng" -> SearXNGOptions(id = id, url = "")
        "linkup" -> LinkUpOptions(id = id, apiKey = "")
        "brave" -> BraveOptions(id = id, apiKey = "")
        "metaso" -> MetasoOptions(id = id, apiKey = "")
        "ollama" -> OllamaOptions(id = id, apiKey = "")
        "jina" -> JinaOptions(id = id, apiKey = "")
        "perplexity" -> PerplexityOptions(id = id, apiKey = "")
        "bocha" -> BochaOptions(id = id, apiKey = "")
        "doubao" -> DoubaoOptions(id = id, apiKey = "")
        "serper" -> SerperOptions(id = id, apiKey = "")
        "querit" -> QueritOptions(id = id, apiKey = "")
        "grok" -> GrokOptions(id = id, apiKey = "")
        "stepfun" -> StepFunOptions(id = id, apiKey = "")
        "firecrawl" -> FirecrawlOptions(id = id, apiKey = "")
        "tinyfish" -> TinyFishOptions(id = id, apiKey = "")
        "anysearch" -> AnySearchOptions(id = id, apiKey = "")
        "parallel" -> ParallelOptions(id = id, apiKey = "")
        "you" -> YouSearchOptions(id = id, apiKey = "")
        "kelivo" -> KelivoOptions(id = id)
        else -> BingLocalOptions(id = id)
    }
}

/** Brand badge (search_settings_sheet._BrandBadge / services page). */
@Composable
internal fun SearchBrandBadge(options: SearchServiceOptions, size: Dp) {
    ProviderAvatarSmall(
        providerKey = options.id,
        displayName = SearchServiceUi.brandOf(options),
        size = size,
    )
}

/** One configuration field — mirrors the `_configurationFields` entries. */
internal data class SearchFieldSpec(
    val key: String,
    val labelRes: Int,
    val hint: String? = null,
    val obscure: Boolean = false,
    val number: Boolean = false,
    val required: Boolean = false,
    val minLines: Int = 1,
    val maxLines: Int = 1,
    /** Renders the multi-key entry directly below this field. */
    val multiKeyAfter: Boolean = false,
    /** Dropdown options (value to label-res); null = plain text field. */
    val dropdown: List<Pair<String, Int>>? = null,
)

private fun apiKeyField(required: Boolean = true, multiKey: Boolean = true) = SearchFieldSpec(
    key = "apiKey",
    labelRes = R.string.search_services_dialog_api_key,
    obscure = true,
    required = required,
    multiKeyAfter = multiKey,
)

/** `_configurationFields` — per-type field lists in upstream order. */
internal fun searchConfigFields(type: String): List<SearchFieldSpec> = when (type) {
    "bing_local", "kelivo" -> emptyList()
    "duckduckgo" -> listOf(
        SearchFieldSpec("region", R.string.search_services_edit_dialog_region_optional, hint = "us-en"),
    )
    "tavily" -> listOf(
        apiKeyField(),
        SearchFieldSpec("url", R.string.search_services_field_custom_url_optional, hint = TavilyOptions.DEFAULT_URL),
    )
    "exa" -> listOf(
        apiKeyField(),
        SearchFieldSpec("url", R.string.search_services_field_custom_url_optional, hint = ExaOptions.DEFAULT_URL),
    )
    "zhipu", "linkup", "metaso", "ollama", "jina", "doubao" -> listOf(apiKeyField())
    "searxng" -> listOf(
        SearchFieldSpec("url", R.string.search_services_edit_dialog_instance_url, required = true),
        SearchFieldSpec("engines", R.string.search_services_edit_dialog_engines_optional, hint = "google,duckduckgo"),
        SearchFieldSpec("language", R.string.search_services_edit_dialog_language_optional, hint = "en-US"),
        SearchFieldSpec("username", R.string.search_services_edit_dialog_username_optional),
        SearchFieldSpec("password", R.string.search_services_edit_dialog_password_optional, obscure = true),
    )
    "serper" -> listOf(
        apiKeyField(),
        SearchFieldSpec("gl", R.string.search_services_dialog_country_optional, hint = "cn"),
        SearchFieldSpec("hl", R.string.search_services_dialog_language_optional, hint = "zh-cn"),
        SearchFieldSpec("tbs", R.string.search_services_dialog_time_filter_optional, hint = "qdr:d"),
        SearchFieldSpec("page", R.string.search_services_dialog_page_optional, hint = "1", number = true),
    )
    "querit" -> listOf(
        apiKeyField(),
        SearchFieldSpec("sitesInclude", R.string.search_services_dialog_sites_include_optional, hint = "@string/search_services_dialog_sites_hint"),
        SearchFieldSpec("sitesExclude", R.string.search_services_dialog_sites_exclude_optional, hint = "@string/search_services_dialog_sites_hint"),
        SearchFieldSpec("timeRange", R.string.search_services_dialog_time_range_optional, hint = "@string/search_services_dialog_time_range_hint"),
        SearchFieldSpec("countries", R.string.search_services_dialog_countries_optional, hint = "@string/search_services_dialog_countries_hint"),
        SearchFieldSpec("languages", R.string.search_services_dialog_languages_optional, hint = "@string/search_services_dialog_languages_hint"),
    )
    "grok" -> listOf(
        apiKeyField(),
        SearchFieldSpec("model", R.string.search_services_dialog_model, hint = GrokOptions.DEFAULT_MODEL),
        SearchFieldSpec("reasoningEffort", R.string.reasoning_budget_sheet_title, hint = "none / low / medium / high / xhigh"),
        SearchFieldSpec("customUrl", R.string.search_services_field_custom_url_optional, hint = GrokOptions.DEFAULT_URL),
        SearchFieldSpec("systemPrompt", R.string.search_services_dialog_system_prompt, minLines = 3, maxLines = 6),
    )
    "stepfun" -> listOf(
        apiKeyField(),
        SearchFieldSpec("url", R.string.search_services_field_custom_url_optional, hint = StepFunOptions.DEFAULT_URL),
        SearchFieldSpec("category", R.string.search_services_dialog_model, hint = "programming / research / gov / business"),
    )
    "firecrawl" -> listOf(
        apiKeyField(required = false),
        SearchFieldSpec("url", R.string.search_services_field_custom_url_optional, hint = FirecrawlOptions.DEFAULT_URL),
        SearchFieldSpec("country", R.string.search_services_dialog_country_optional, hint = "US"),
        SearchFieldSpec("location", R.string.search_services_dialog_language_optional),
    )
    "tinyfish" -> listOf(
        apiKeyField(),
        SearchFieldSpec("url", R.string.search_services_field_custom_url_optional, hint = TinyFishOptions.DEFAULT_URL),
        SearchFieldSpec("location", R.string.search_services_dialog_country_optional, hint = "US"),
        SearchFieldSpec("language", R.string.search_services_dialog_language_optional, hint = "en"),
        SearchFieldSpec("includeDomains", R.string.search_services_dialog_sites_include_optional),
        SearchFieldSpec("excludeDomains", R.string.search_services_dialog_sites_exclude_optional),
    )
    "anysearch" -> listOf(
        apiKeyField(required = false),
        SearchFieldSpec("url", R.string.search_services_field_custom_url_optional, hint = AnySearchOptions.DEFAULT_URL),
    )
    "parallel" -> listOf(
        apiKeyField(),
        SearchFieldSpec(
            "mode",
            R.string.search_services_dialog_search_mode,
            dropdown = ParallelOptions.MODES.map { it to parallelModeLabelRes(it) },
        ),
    )
    "you" -> listOf(
        apiKeyField(),
        SearchFieldSpec(
            "contentMode",
            R.string.search_services_dialog_content_mode,
            dropdown = listOf(
                YouSearchOptions.HIGHLIGHTS_MODE to R.string.search_services_dialog_highlights,
                YouSearchOptions.SNIPPETS_MODE to R.string.search_services_dialog_snippets,
            ),
        ),
    )
    "brave" -> listOf(
        apiKeyField(),
        SearchFieldSpec(
            "mode",
            R.string.search_services_dialog_search_mode,
            dropdown = listOf(
                BraveOptions.WEB_MODE to R.string.search_services_dialog_web_search,
                BraveOptions.LLM_CONTEXT_MODE to R.string.search_services_dialog_llm_context,
            ),
        ),
        SearchFieldSpec(
            "maximumNumberOfTokens",
            R.string.search_services_dialog_maximum_tokens,
            hint = BraveOptions.DEFAULT_MAXIMUM_NUMBER_OF_TOKENS.toString(),
            number = true,
        ),
    )
    else -> listOf(apiKeyField())
}

private fun parallelModeLabelRes(mode: String): Int = R.string.search_services_dialog_search_mode

/**
 * Search service editor — port of search_service_editor_page.dart: type chips
 * (add mode), per-type configuration card, multi-key entry, connection test.
 * Saves through [container.searchSettingsRepository]; [onClose] reports whether
 * the list needs a reload.
 */
@Composable
fun SearchServiceEditorScreen(
    container: AppContainerImpl,
    initial: SearchServiceOptions?,
    canDelete: Boolean,
    onClose: (saved: Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val isAdding = initial == null
    val serviceId = remember(initial) { initial?.id ?: java.util.UUID.randomUUID().toString().take(8) }
    val repo = container.searchSettingsRepository

    var type by remember { mutableStateOf(SearchServiceUi.typeOf(initial ?: BingLocalOptions(id = serviceId))) }
    val values = remember(initial) { mutableStateMapOf<String, String>() }
    val errors = remember(initial) { mutableStateMapOf<String, String>() }
    var extraKeys by remember(initial) { mutableStateOf(initial?.extraApiKeys ?: emptyList()) }
    var showApiKeys by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<com.psyche.memo.data.model.SearchResult?>(null) }
    var testError by remember { mutableStateOf<String?>(null) }
    var testQuery by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf<SearchUsageService.UsageInfo?>(null) }
    var usageError by remember { mutableStateOf<String?>(null) }
    var usageLoading by remember { mutableStateOf(false) }

    // Seed the form from the initial service (controllers keyed like upstream).
    remember(initial) {
        val seed = initial ?: SearchServiceUi.defaultService(type, serviceId)
        val obj = seed.toJson()
        fun put(key: String) {
            val v = (obj[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (v != null && v.isNotEmpty()) values[key] = v
        }
        listOf(
            "region", "apiKey", "url", "engines", "language", "username", "password",
            "gl", "hl", "tbs", "page", "sitesInclude", "sitesExclude", "timeRange",
            "countries", "languages", "model", "reasoningEffort", "customUrl",
            "systemPrompt", "category", "country", "location", "includeDomains",
            "excludeDomains", "mode", "contentMode", "maximumNumberOfTokens",
        ).forEach { put(it) }
        true
    }

    val apiKeyRequired = stringResource(R.string.search_services_edit_dialog_api_key_required)
    val urlRequired = stringResource(R.string.search_services_edit_dialog_url_required)
    val pageInvalid = stringResource(R.string.search_services_dialog_page_invalid)
    val tokensInvalid = stringResource(R.string.search_services_dialog_maximum_tokens_invalid)

    fun currentService(): SearchServiceOptions {
        fun t(key: String) = values[key]?.trim() ?: ""
        fun raw(key: String) = values[key] ?: ""
        return when (type) {
            "bing_local" -> BingLocalOptions(id = serviceId)
            "kelivo" -> KelivoOptions(id = serviceId)
            "duckduckgo" -> DuckDuckGoOptions(id = serviceId, region = t("region").ifEmpty { "us-en" })
            "tavily" -> TavilyOptions(id = serviceId, apiKey = t("apiKey"), url = t("url"), extraApiKeys = extraKeys)
            "exa" -> ExaOptions(id = serviceId, apiKey = t("apiKey"), url = t("url"), extraApiKeys = extraKeys)
            "zhipu" -> ZhipuOptions(id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys)
            "searxng" -> SearXNGOptions(
                id = serviceId, url = t("url"), engines = t("engines"), language = t("language"),
                username = t("username"), password = raw("password"),
            )
            "linkup" -> LinkUpOptions(id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys)
            "brave" -> BraveOptions(
                id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys,
                mode = BraveOptions.normalizeMode(t("mode")),
                maximumNumberOfTokens = BraveOptions.normalizeMaximumNumberOfTokens(t("maximumNumberOfTokens")),
            )
            "metaso" -> MetasoOptions(id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys)
            "ollama" -> OllamaOptions(id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys)
            "jina" -> JinaOptions(id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys)
            "perplexity" -> PerplexityOptions(
                id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys,
                country = (initial as? PerplexityOptions)?.country,
                searchDomainFilter = (initial as? PerplexityOptions)?.searchDomainFilter,
                maxTokensPerPage = (initial as? PerplexityOptions)?.maxTokensPerPage,
            )
            "bocha" -> BochaOptions(
                id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys,
                freshness = (initial as? BochaOptions)?.freshness,
                summary = (initial as? BochaOptions)?.summary ?: true,
                include = (initial as? BochaOptions)?.include,
                exclude = (initial as? BochaOptions)?.exclude,
            )
            "doubao" -> DoubaoOptions(id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys)
            "serper" -> SerperOptions(
                id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys,
                gl = t("gl"), hl = t("hl"), tbs = t("tbs"),
                page = t("page").toIntOrNull() ?: 1,
            )
            "querit" -> QueritOptions(
                id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys,
                sitesInclude = t("sitesInclude"), sitesExclude = t("sitesExclude"),
                timeRange = t("timeRange"), countries = t("countries"), languages = t("languages"),
            )
            "grok" -> GrokOptions(
                id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys,
                model = t("model"), reasoningEffort = t("reasoningEffort"),
                customUrl = t("customUrl"), systemPrompt = raw("systemPrompt"),
            )
            "stepfun" -> StepFunOptions(
                id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys,
                url = t("url"), category = t("category"),
            )
            "firecrawl" -> FirecrawlOptions(
                id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys,
                url = t("url"),
                sources = (initial as? FirecrawlOptions)?.sources ?: listOf("web"),
                categories = (initial as? FirecrawlOptions)?.categories ?: emptyList(),
                country = t("country"), location = t("location"),
            )
            "tinyfish" -> TinyFishOptions(
                id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys,
                url = t("url"), location = t("location"), language = t("language"),
                includeDomains = t("includeDomains"), excludeDomains = t("excludeDomains"),
            )
            "anysearch" -> AnySearchOptions(id = serviceId, apiKey = t("apiKey"), url = t("url"), extraApiKeys = extraKeys)
            "parallel" -> ParallelOptions(
                id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys,
                mode = ParallelOptions.normalizeMode(t("mode")),
            )
            "you" -> YouSearchOptions(
                id = serviceId, apiKey = t("apiKey"), extraApiKeys = extraKeys,
                contentMode = YouSearchOptions.normalizeContentMode(t("contentMode")),
            )
            else -> BingLocalOptions(id = serviceId)
        }
    }

    fun validate(): Boolean {
        errors.clear()
        for (spec in searchConfigFields(type)) {
            val value = values[spec.key]?.trim() ?: ""
            if (spec.required && value.isEmpty()) {
                errors[spec.key] = if (spec.key == "url") urlRequired else apiKeyRequired
            }
            if (spec.key == "page" && value.isNotEmpty() && (value.toIntOrNull() ?: 0) < 1) {
                errors[spec.key] = pageInvalid
            }
            if (spec.key == "maximumNumberOfTokens" && !BraveOptions.isValidMaximumNumberOfTokensInput(value)) {
                errors[spec.key] = tokensInvalid
            }
        }
        return errors.isEmpty()
    }

    fun save() {
        if (!validate()) return
        val service = currentService()
        if (isAdding) repo.addService(service) else repo.updateService(service)
        onClose(true)
    }

    /** 用量请求的身份：换了它就把旧结果丢掉（Dart `_usageCacheKey`）。 */
    fun usageIdentity(service: SearchServiceOptions): String = when (service) {
        is TavilyOptions -> "tavily|${service.id}|${service.apiKey.trim()}|${service.resolvedUrl}"
        is LinkUpOptions -> "linkup|${service.id}|${service.apiKey.trim()}|"
        else -> ""
    }

    fun queryUsage() {
        val service = currentService()
        if (!SearchUsageService.supports(service) || usageLoading) return
        // Dart `_queryUsage` 先验表单：key 没填就不发请求（否则只是白跑一趟拿 401）。
        if (!validate()) return
        val requested = usageIdentity(service)
        usageLoading = true
        usageError = null
        scope.launch {
            try {
                // 超时取公共选项（search_service_editor_page.dart L1024-1026 的
                // clamp(1000, 30000)）；fetch 自己切 IO，这里仍在主线程的
                // scope 上，别把阻塞调用直接写进来（见 SearchUsageService 注释）。
                val timeout = repo.commonOptions().timeout.coerceIn(1000, 30000)
                val info = SearchUsageService.fetch(service, container.httpClient, timeoutMs = timeout)
                // 期间改了 key/地址/类型 → 这次结果作废（Dart 的 requestGeneration +
                // cacheKey 比对）。
                if (usageIdentity(currentService()) != requested) return@launch
                usage = info
            } catch (e: Exception) {
                if (usageIdentity(currentService()) != requested) return@launch
                usageError = e.message ?: e.toString()
            } finally {
                usageLoading = false
            }
        }
    }

    // autoQueryUsage：编辑已有服务且填了 key 时自动查一次。
    androidx.compose.runtime.LaunchedEffect(initial) {
        val service = initial
        val hasCredential = when (service) {
            is TavilyOptions -> service.apiKey.trim().isNotEmpty()
            is LinkUpOptions -> service.apiKey.trim().isNotEmpty()
            else -> false
        }
        if (hasCredential) queryUsage()
    }

    val displayName = stringResource(SearchServiceUi.nameRes(currentService()))
    val title = if (isAdding) stringResource(R.string.search_services_add_dialog_title) else displayName

    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        // ---- AppBar: back / delete / save ----
        MemoTopBar(
            title = title,
            onBack = { onClose(false) },
        ) {
            if (!isAdding && canDelete) {
                IconActionButton(Lucide.Trash2, cs.error, stringResource(R.string.search_service_editor_delete_tooltip)) {
                    showDeleteConfirm = true
                }
                Spacer(Modifier.width(4.dp))
            }
            IconActionButton(
                Lucide.Check,
                cs.onSurface,
                if (isAdding) stringResource(R.string.search_services_add_dialog_add)
                else stringResource(R.string.search_services_edit_dialog_save),
            ) { save() }
            Spacer(Modifier.width(12.dp))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            if (isAdding) {
                item { SectionHeaderText(stringResource(R.string.search_service_editor_provider_type_title), first = true) }
                item { TypeChips(selected = type, onSelect = {
                    type = it
                    values.clear()
                    errors.clear()
                    // 换类型 = 换身份：旧用量/旧错误/在途请求全部作废（_changeType）。
                    usage = null
                    usageError = null
                    usageLoading = false
                } ) }
            }
            item {
                SectionHeaderText(
                    stringResource(R.string.search_service_editor_configuration_title),
                    first = !isAdding,
                )
            }
            item {
                ConfigCard(
                    displayName = displayName,
                    service = currentService(),
                    fields = searchConfigFields(type),
                    values = values,
                    errors = errors,
                    onValue = { k, v ->
                        values[k] = v
                        errors.remove(k)
                        // 改了任何字段（尤其是 key/地址）= 旧用量不再对应当前配置，
                        // 清掉而不是继续显示（_markDirty）。
                        usage = null
                        usageError = null
                    },
                    extraKeyCount = extraKeys.size,
                    onOpenApiKeys = { showApiKeys = true },
                )
            }
            if (SearchUsageService.supports(currentService())) {
                item { SectionHeaderText(stringResource(R.string.search_service_editor_usage_title)) }
                item {
                    UsageCard(
                        service = currentService(),
                        usage = usage,
                        error = usageError,
                        loading = usageLoading,
                        onQuery = { queryUsage() },
                    )
                }
            }
            item { SectionHeaderText(stringResource(R.string.search_service_editor_test_title)) }
            item {
                TestCard(
                    query = testQuery,
                    onQuery = { testQuery = it },
                    testing = testing,
                    result = testResult,
                    error = testError,
                    onRun = {
                        if (!testing) {
                        testing = true
                        testResult = null
                        testError = null
                        scope.launch {
                            try {
                                testResult = container.searchEngine.search(
                                    query = testQuery.trim().ifEmpty { "connectivity test" },
                                    options = currentService(),
                                    common = repo.commonOptions(),
                                )
                            } catch (e: Exception) {
                                testError = e.message ?: e.toString()
                            } finally {
                                testing = false
                            }
                        }
                        }
                    },
                )
            }
        }
    }

    if (showApiKeys) {
        SearchApiKeysScreen(
            service = currentService(),
            onClose = { pool ->
                showApiKeys = false
                if (pool != null) {
                    values["apiKey"] = pool.firstOrNull() ?: ""
                    extraKeys = pool.drop(1)
                }
            },
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.overlaySurfaceColor(),
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
        onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.search_service_editor_delete_title)) },
            text = { Text(stringResource(R.string.search_service_editor_delete_message, displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    repo.deleteService(serviceId)
                    onClose(true)
                }) { Text(stringResource(R.string.search_service_editor_delete_confirm), color = cs.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.search_services_add_dialog_cancel), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.74f))
                }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TypeChips(selected: String, onSelect: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    SectionCard {
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (type in SearchServiceUi.PROVIDER_TYPES) {
                val isSelected = type == selected
                Row(
                    modifier = Modifier
                        .background(
                            if (isSelected) cs.primary.copy(alpha = 0.14f) else semantic.surfaceFill,
                            RoundedCornerShape(MemoRadius.PILL_DP.dp),
                        )
                        .border(
                            1.dp,
                            if (isSelected) cs.primary.copy(alpha = 0.4f) else cs.outlineVariant.copy(alpha = 0.3f),
                            RoundedCornerShape(MemoRadius.PILL_DP.dp),
                        )
                        .clickable { onSelect(type) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SearchBrandBadge(SearchServiceUi.defaultService(type, "preview"), 16.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(SearchServiceUi.typeNameRes(type)),
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isSelected) cs.primary else cs.onSurface,
                        ),
                    )
                }
            }
        }
    }
}


@Composable
private fun UsageCard(
    service: SearchServiceOptions,
    usage: SearchUsageService.UsageInfo?,
    error: String?,
    loading: Boolean,
    onQuery: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    fun fmt(value: Double): String =
        java.text.NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).apply {
            minimumFractionDigits = 0
            maximumFractionDigits = 2
        }.format(value)

    SectionCard {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(cs.primary.copy(alpha = 0.1f), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Lucide.Wallet, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.search_service_editor_usage_title),
                    style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                if (loading) {
                    Box(modifier = Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(strokeWidth = 2.dp, color = cs.primary, modifier = Modifier.size(18.dp))
                    }
                } else {
                    IconActionButton(Lucide.RefreshCw, cs.primary, stringResource(R.string.search_service_editor_usage_query)) {
                        onQuery()
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            when {
                loading && usage == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(strokeWidth = 2.dp, color = cs.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        stringResource(R.string.search_service_editor_usage_querying),
                        style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                    )
                }
                error != null && usage == null -> UsageErrorRow(
                    stringResource(R.string.search_service_editor_usage_failed, error),
                )
                usage == null -> Text(
                    stringResource(R.string.search_service_editor_usage_not_queried),
                    style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.64f)),
                )
                service is TavilyOptions && usage.used != null && usage.limit != null -> {
                    val limit = usage.limit
                    val progress = if (limit <= 0) 0f else ((usage.used / limit).coerceIn(0.0, 1.0)).toFloat()
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.search_service_editor_usage_remaining, fmt(usage.remaining)),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = stringResource(
                                    R.string.search_service_editor_usage_used,
                                    fmt(usage.used),
                                    fmt(limit),
                                ),
                                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                                style = TextStyle(fontSize = 12.5.sp, color = cs.onSurface.copy(alpha = 0.68f)),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(7.dp),
                            color = cs.primary,
                            trackColor = cs.primary.copy(alpha = 0.13f),
                        )
                    }
                }
                service is LinkUpOptions -> Text(
                    text = stringResource(R.string.search_service_editor_usage_balance, fmt(usage.remaining)),
                    style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
                )
                else -> Text(
                    text = stringResource(R.string.search_service_editor_usage_remaining, fmt(usage.remaining)),
                    style = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = cs.primary),
                )
            }
            if (usage != null && error != null) {
                Spacer(Modifier.height(10.dp))
                UsageErrorRow(stringResource(R.string.search_service_editor_usage_failed, error))
            }
        }
    }
}

@Composable
private fun UsageErrorRow(message: String) {
    val cs = MaterialTheme.colorScheme
    Row {
        Icon(Lucide.TriangleAlert, contentDescription = null, tint = cs.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            text = message,
            style = TextStyle(fontSize = 13.sp, lineHeight = 17.5.sp, color = cs.error),
        )
    }
}

@Composable
private fun SectionHeaderText(text: String, first: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    Text(
        text = text,
        style = TextStyle(
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface.copy(alpha = 0.8f),
        ),
        modifier = Modifier.padding(start = 12.dp, top = if (first) 2.dp else 18.dp, end = 12.dp, bottom = 6.dp),
    )
}

@Composable
private fun ConfigCard(
    displayName: String,
    service: SearchServiceOptions,
    fields: List<SearchFieldSpec>,
    values: Map<String, String>,
    errors: Map<String, String>,
    onValue: (String, String) -> Unit,
    extraKeyCount: Int,
    onOpenApiKeys: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    SectionCard {
        Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SearchBrandBadge(service, 38.dp)
                Spacer(Modifier.width(12.dp))
                Text(
                    text = displayName,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                )
            }
            if (fields.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.search_service_editor_no_configuration),
                    style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.68f)),
                )
            } else {
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(thickness = 0.6.dp, color = cs.outlineVariant.copy(alpha = 0.16f))
                fields.forEachIndexed { index, spec ->
                    Spacer(Modifier.height(14.dp))
                    FieldBlock(spec, values, errors, onValue)
                    if (spec.multiKeyAfter) {
                        Spacer(Modifier.height(14.dp))
                        MultiKeyEntry(extraKeyCount, onOpenApiKeys)
                    }
                    if (index != fields.lastIndex) Spacer(Modifier.height(0.dp))
                }
            }
        }
    }
}

@Composable
private fun FieldBlock(
    spec: SearchFieldSpec,
    values: Map<String, String>,
    errors: Map<String, String>,
    onValue: (String, String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    val label = stringResource(spec.labelRes)
    val hint = spec.hint?.let { if (it.startsWith("@string/")) null else it }
    Column {
        Text(
            text = label,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.72f)),
        )
        Spacer(Modifier.height(7.dp))
        if (spec.dropdown != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                spec.dropdown.forEach { (value, labelRes) ->
                    val selected = (values[spec.key] ?: spec.dropdown.first().first) == value
                    Text(
                        text = stringResource(labelRes),
                        style = TextStyle(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (selected) cs.primary else cs.onSurface,
                        ),
                        modifier = Modifier
                            .background(
                                if (selected) cs.primary.copy(alpha = 0.12f) else semantic.surfaceFill,
                                RoundedCornerShape(MemoRadius.SMALL_DP.dp),
                            )
                            .clickable { onValue(spec.key, value) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        } else {
            val error = errors[spec.key]
            val placeholder: (@Composable () -> Unit)? = if (hint != null) {
                ({ Text(hint, style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.45f))) })
            } else null
            val supporting: (@Composable () -> Unit)? = if (error != null) {
                ({ Text(error, style = TextStyle(fontSize = 12.sp, color = cs.error)) })
            } else null
            OutlinedTextField(
                value = values[spec.key] ?: "",
                onValueChange = { onValue(spec.key, it) },
                singleLine = spec.maxLines == 1,
                minLines = spec.minLines,
                maxLines = spec.maxLines,
                isError = error != null,
                visualTransformation = if (spec.obscure) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (spec.number) KeyboardType.Number else KeyboardType.Text,
                ),
                placeholder = placeholder,
                supportingText = supporting,
                shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = semantic.surfaceFill,
                    unfocusedContainerColor = semantic.surfaceFill,
                    focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MultiKeyEntry(count: Int, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    Column {
        Text(
            text = stringResource(R.string.search_service_editor_multi_key_title),
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface.copy(alpha = 0.72f)),
        )
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(semantic.surfaceFill, RoundedCornerShape(MemoRadius.INNER_DP.dp))
                .clickable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.KeyRound, contentDescription = null, tint = cs.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (count == 0) {
                    stringResource(R.string.search_service_editor_multi_key_none)
                } else {
                    stringResource(R.string.search_service_editor_multi_key_count, count.toString())
                },
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurface.copy(alpha = if (count == 0) 0.58f else 0.92f),
                ),
                modifier = Modifier.weight(1f),
            )
            Icon(Lucide.ChevronRight, contentDescription = null, tint = cs.onSurface.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun TestCard(
    query: String,
    onQuery: (String) -> Unit,
    testing: Boolean,
    result: com.psyche.memo.data.model.SearchResult?,
    error: String?,
    onRun: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    SectionCard {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                placeholder = {
                    Text(
                        stringResource(R.string.search_service_editor_test_query_hint),
                        style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.45f)),
                    )
                },
                shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = semantic.surfaceFill,
                    unfocusedContainerColor = semantic.surfaceFill,
                    focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                    unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            IosTileButton(
                label = stringResource(
                    if (testing) R.string.search_service_editor_test_running else R.string.search_service_editor_test_run,
                ),
                icon = Lucide.Search,
                enabled = !testing,
                backgroundColor = cs.primary,
                onClick = onRun,
                modifier = Modifier.fillMaxWidth(),
            )
            if (testing) {
                Spacer(Modifier.height(10.dp))
                CircularProgressIndicator(strokeWidth = 2.dp, color = cs.primary, modifier = Modifier.size(20.dp))
            }
            error?.let {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.search_service_editor_test_failed, it), style = TextStyle(fontSize = 13.sp, color = cs.error))
            }
            result?.let { r ->
                Spacer(Modifier.height(10.dp))
                if (r.items.isEmpty()) {
                    Text(
                        stringResource(R.string.search_service_editor_test_no_results),
                        style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                    )
                }
                r.items.take(5).forEach { item ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Text(
                            text = item.title.ifEmpty { item.url },
                            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = item.url,
                            style = TextStyle(fontSize = 12.sp, color = cs.primary),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.text.isNotEmpty()) {
                            Text(
                                text = item.text,
                                style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * API key rotation pool — port of search_api_keys_page.dart: list order is the
 * rotation order (first = primary), batch paste splits with
 * [SearchApiKeyRotator.parseBatch], popping returns the pool.
 */
@Composable
fun SearchApiKeysScreen(
    service: SearchServiceOptions,
    onClose: (List<String>?) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var keys by remember {
        mutableStateOf(SearchApiKeyRotator.rotationPool(service.primaryApiKey, service.extraApiKeys))
    }
    var batch by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    Column(modifier = Modifier.fillMaxSize().background(cs.surface).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconActionButton(Lucide.ArrowLeft, cs.onSurface, stringResource(R.string.search_services_page_back_tooltip)) {
                onClose(keys)
            }
            Text(
                text = stringResource(R.string.search_service_editor_multi_key_title),
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
        ) {
            item {
                SectionCard {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(
                            stringResource(R.string.search_api_keys_page_description),
                            style = TextStyle(fontSize = 13.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                        )
                        Spacer(Modifier.height(10.dp))
                        if (keys.isEmpty()) {
                            Text(
                                stringResource(R.string.search_api_keys_page_empty),
                                style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.6f)),
                            )
                        } else {
                            keys.forEachIndexed { index, key ->
                                if (index > 0) HorizontalDivider(thickness = 0.6.dp, color = cs.outlineVariant.copy(alpha = 0.18f))
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = SearchApiKeyRotator.mask(key),
                                        style = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (index == 0) {
                                        Text(
                                            text = stringResource(R.string.search_api_keys_page_primary_badge),
                                            style = TextStyle(fontSize = 11.sp, color = cs.primary),
                                            modifier = Modifier
                                                .background(cs.primary.copy(alpha = 0.12f), RoundedCornerShape(MemoRadius.PILL_DP.dp))
                                                .padding(horizontal = 8.dp, vertical = 2.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    IconActionButton(Lucide.Trash2, cs.error, "delete") {
                                        keys = keys.filterIndexed { i, _ -> i != index }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
            item {
                SectionCard {
                    Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                        OutlinedTextField(
                            value = batch,
                            onValueChange = {
                                batch = it
                                feedback = null
                            },
                            minLines = 2,
                            maxLines = 4,
                            placeholder = {
                                Text(
                                    stringResource(R.string.search_api_keys_page_batch_hint),
                                    style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.45f)),
                                )
                            },
                            shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = semantic.surfaceFill,
                                unfocusedContainerColor = semantic.surfaceFill,
                                focusedBorderColor = cs.primary.copy(alpha = 0.5f),
                                unfocusedBorderColor = cs.outlineVariant.copy(alpha = 0.4f),
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        feedback?.let { (added, skipped) ->
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(R.string.search_api_keys_page_batch_result, added.toString(), skipped.toString()),
                                style = TextStyle(fontSize = 12.sp, color = cs.onSurface.copy(alpha = 0.7f)),
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        IosTileButton(
                            label = stringResource(R.string.search_api_keys_page_add),
                            icon = Lucide.Check,
                            backgroundColor = cs.primary,
                            onClick = {
                                val parsed = SearchApiKeyRotator.parseBatch(batch)
                                if (parsed.isEmpty()) return@IosTileButton
                                val existing = keys.toSet()
                                val fresh = parsed.filter { it !in existing }
                                keys = keys + fresh
                                batch = ""
                                feedback = fresh.size to (parsed.size - fresh.size)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
