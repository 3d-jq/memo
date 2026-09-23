package com.psyche.memo.ui.chat

import com.psyche.memo.ui.theme.MemoRadius
import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import com.composables.icons.lucide.Brain
import com.composables.icons.lucide.Hammer
import com.composables.icons.lucide.Copy
import com.composables.icons.lucide.Zap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.ChatViewModel
import com.psyche.memo.ui.ChatStyleSpec

/**
 * 聊天页的**纯助手与常量**（第 3 步：从 `ui/HomeScreen.kt` 摘出，纯搬运）：
 * 快捷短语加载、顶栏助手 id、首屏骨架判定与绘制、`+` 三态、云端 ASR 选择、
 * 时间线常量（哨兵 key / 测试标签 / 历史触发距离 / 导航按钮三态）、
 * 以及输入栏三个按钮态的纯函数。
 *
 * 2026-09-16 摘出前它们和 `ChatContent`（1840 行）挤在一个 3570 行的文件里；
 * 现在与 `ChatContent.kt` / `ChatInputBar.kt` 分文件，改哪块看哪块。
 */

internal fun loadQuickPhrases(container: AppContainerImpl): List<com.psyche.memo.data.model.QuickPhrase> {
    val repo = com.psyche.memo.data.repo.QuickPhraseRepository(container.database.writableDatabase)
    val assistant = container.currentAssistant()
    return repo.globalPhrases() + if (assistant != null) repo.forAssistant(assistant.id) else emptyList()
}

/**
 * 消息头/顶栏用哪个助手（assistant_rows 主键）：会话行绑定的助手优先；**新建会话是
 * draft**（`ChatViewModel.ensureConversationRow` 要等首条消息落库才写行），行还不存在
 * 时回落到当前助手 —— 原版 draft 在内存里就带着 `assistantId`，等价于
 * `currentConversation.assistantId`（chat_message_widget 的 assistant 由
 * AssistantProvider.currentAssistant 提供）。
 */
internal fun headerAssistantId(
    conversationAssistantId: String?,
    currentAssistantId: String?,
): String? =
    conversationAssistantId?.takeIf { it.isNotEmpty() }
        ?: currentAssistantId?.takeIf { it.isNotEmpty() }

/**
 * 是否铺首屏骨架 —— 逐条照原版：
 * ① 判据 `rendered.isEmpty && isLoadingWindow`（`message_list_view.dart:1739`）；
 * ② `isLoadingWindow = _startupConversationPending || chatController.isLoadingWindow`
 *    （`home_page_controller.dart:316-317`），而**点会话那条路是 fetch-then-commit**：
 *    提交时新窗口已在手（`commitConversationSwitch`），列表不为空 ⇒ **骨架根本不出现**；
 *    原版注释还写明「cache hits resolve within one frame batch and never surface a skeleton」。
 *    所以骨架只服务**冷启动**那一次（[startupPending]）。
 *
 * 我们曾经在每次切换都铺骨架，属于偏离（用户 2026-09-15「原版这个骨架屏没有这个气泡
 * 显示吧 这个是点击对话加载骨架屏哦」）。
 */
internal fun showTimelineSkeleton(
    startupPending: Boolean,
    tailLoaded: Boolean,
    messagesEmpty: Boolean,
): Boolean = startupPending && !tailLoaded && messagesEmpty

/**
 * 首屏骨架 —— 1:1 照原版 `_WindowLoadingSkeleton`（`message_list_view.dart:2779-2850`）：
 * - 4 个气泡，**高度统一 44**，颜色 `onSurface@8%`（原版 `:2811`）；
 * - 宽度比依次 **0.62 / 0.48 / 0.70 / 0.55**，左右交替，行距 14（原版 `:2841-2847`）；
 * - 脉冲作用于**整列** `opacity 0.45→1.0`、900ms 往复（原版 `:2799/:2837`）——
 *   不是给颜色加 alpha；
 * - 内边距 `水平 + 12` / `上 + 24`（原版 `:2829-2834`）。
 */
@Composable
internal fun TimelineSkeleton(modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val bubbleColor = cs.onSurface.copy(alpha = 0.08f)
    val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "timelineSkeleton")
    val pulse by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(900),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "timelineSkeletonPulse",
    )
    val rows = listOf(
        false to 0.62f,
        true to 0.48f,
        false to 0.70f,
        true to 0.55f,
    )
    Column(
        modifier = modifier
            .padding(
                // 原版 `message_list_view.dart:2829-2835` 用 `horizontalPadding + 12`，而
                // `horizontalPadding`（`:1670-1672`）= `(宽 − maxContentWidth)/2` ——
                // **手机上恒为 0** ⇒ 实际左右各 **12dp**（不是我一开始误以为的 16+12）。
                // 顶部同理：`topContentPadding(8) + 24`。
                start = 12.dp,
                top = ChatStyleSpec.LIST_TOP_PADDING_DP.dp + 24.dp,
                end = 12.dp,
            )
            .alpha(pulse),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        rows.forEach { (alignEnd, widthFactor) ->
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(widthFactor)
                        .height(44.dp)
                        .background(
                            bubbleColor,
                            androidx.compose.foundation.shape.RoundedCornerShape(MemoRadius.INNER_DP.dp),
                        ),
                )
            }
        }
    }
}

/**
 * 顶栏 `+` 到底该显示「临时聊天开关」还是「新建会话」—— 判据：「当前会话是空的」。
 *
 * 原版（`home_mobile_layout` + `home_view_model`）由内存里的 `currentConversation` 消息数
 * 决定；我们这边原来在**组合期实参**里调 `messageDao.count(id)`（整表 COUNT，主线程），
 * 点开长会话时会卡一下（用户 2026-09-15「对话点击加载还是卡」）。现在改成
 * 「VM 说首屏已读（[ChatViewModel.tailLoaded]）**且**窗口为空」：
 *
 * - 首屏还没读回来时**不**当成空会话 —— 否则有消息的会话会先闪一下临时聊天图标；
 * - 临时会话（不落库）恒为真。
 */
internal fun newActionToggleable(
    isTemporary: Boolean,
    tailLoaded: Boolean,
    messagesEmpty: Boolean,
): Boolean = isTemporary || (tailLoaded && messagesEmpty)

/**
 * 落到列表底部的**越界量**：`requestScrollToItem(size + N)` 的下标越界后由 LazyColumn
 * 夹到末条、再夹到 maxScrollExtent，于是"把末条对齐到视口顶部"变成"真到底"。
 * 上游同款（`ChatPage.kt:179` 用 +5，`ChatList.kt:284` 用 +10）。
 */
/** 列表末尾哨兵项的 key（RikkaHub `ScrollBottomKey` 同款，见 `bottomAnchorIndex`）。 */
internal const val SCROLL_BOTTOM_ITEM_KEY = "scroll-bottom"

/**
 * 流式等待提示那一项的 key（扫光文字）—— RikkaHub `ChatList.kt:109 LoadingIndicatorKey`
 * 同款：它是**列表末尾的一个独立 item**，不参与消息内部的块布局（用户 2026-09-23
 * 「跟 rikkhub 效果不一样…在工具调用这个有点跳动」）。
 */
internal const val STREAMING_INDICATOR_ITEM_KEY = "streaming-indicator"

/** `LazyColumn` 的测试标签（`ChatRowRecompositionTest` 用它做手势）。 */
internal const val CHAT_TIMELINE_TAG = "chat_timeline"

/**
 * 选中的云端 ASR 服务（`asr_selected_service_id_v1` + `asr_services_v1`）：
 * 没选 / 不存在 / 未配置 → null（回系统识别）。已接的 kind 只有 MiMo 与 Step，
 * 其余云端 kind 交给 [com.psyche.memo.provider.CloudAsrService] 抛错——这里先按
 * 「已配置」返回，让 UI 能显示麦克风，失败时控制器自己回 Idle。
 */
internal fun selectedCloudAsrService(container: AppContainerImpl): com.psyche.memo.ui.AsrServiceOptions? {
    val store = container.asrServicesStore
    if (store.services.isEmpty()) store.load()
    val id = store.selectedServiceId ?: return null
    val service = store.services.firstOrNull { it.id == id } ?: return null
    return service.takeIf { it.isConfigured }
}

/** 距顶多少 dp 内触发往前加载历史（message_list_view.dart:1816 的 96 逻辑像素）。 */
internal const val HISTORY_LOAD_TRIGGER_DP = 96f

/** 消息导航按钮三态（settings_provider.dart:4991-5005 的默认值为 scroll）。 */
internal const val MOBILE_NAV_ALWAYS = "always"
internal const val MOBILE_NAV_SCROLL = "scroll"
internal const val MOBILE_NAV_NEVER = "never"


// ---------------------------------------------------------------- 输入栏按钮态

/**
 * chat_input_section.dart:302-308 `_hasQuickPhrases` —— 全局 + 本助手的快捷短语
 * 都为 0 时，输入栏那颗 Zap 按钮整颗不显示。
 */
internal fun quickPhraseButtonVisible(globalCount: Int, assistantCount: Int): Boolean =
    (globalCount + assistantCount) > 0

/**
 * chat_input_section.dart:295-300 `_isMcpActive` —— 助手选中且当前已连接的 MCP
 * 服务器存在时，Hammer 按钮走 active 色。
 */
internal fun mcpButtonActive(selectedIds: List<String>, connectedIds: Set<String>): Boolean =
    selectedIds.isNotEmpty() && connectedIds.any { it in selectedIds }

/**
 * generation_controller.dart:74-90 `isReasoningModel` —— 模型编辑页写了
 * abilities 覆盖就完全按覆盖判定（写了就不再看名称推断），否则按
 * ModelRegistry 的名称推断。CIS:187 supportsReasoning 用它门控 Brain 按钮。
 */
internal fun isReasoningModel(
    cfg: com.psyche.memo.data.model.ProviderConfig?,
    modelId: String,
): Boolean {
    if (modelId.isEmpty()) return false
    val ov = cfg?.modelOverrides?.get(modelId) as? kotlinx.serialization.json.JsonObject
    if (ov != null && ov.containsKey("abilities")) {
        val abilities = com.psyche.memo.ModelOverrideResolver.parseAbilities(ov["abilities"])
        // 上游：override 里的 abilities 键存在（哪怕解析为空列表）就完全信它。
        return abilities?.contains("reasoning") ?: false
    }
    return com.psyche.memo.ModelRegistry.infer(modelId).reasoning
}

/**
 * generation_controller.dart:92-106 `isToolModel` —— 同上，工具能力。
 * CIS:283-293 `_shouldShowMcpButton` 用它门控 Hammer 按钮。
 */
internal fun isToolModel(
    cfg: com.psyche.memo.data.model.ProviderConfig?,
    modelId: String,
): Boolean {
    if (modelId.isEmpty()) return false
    val ov = cfg?.modelOverrides?.get(modelId) as? kotlinx.serialization.json.JsonObject
    if (ov != null && ov.containsKey("abilities")) {
        val abilities = com.psyche.memo.ModelOverrideResolver.parseAbilities(ov["abilities"])
        return abilities?.contains("tool") ?: false
    }
    return com.psyche.memo.ModelRegistry.infer(modelId).tool
}
