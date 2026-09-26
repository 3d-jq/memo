package com.psyche.memo.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.X
import com.psyche.memo.provider.browser.BrowserJsKind
import com.psyche.memo.provider.browser.BrowserSession
import com.psyche.memo.provider.browser.BrowserTabInfo
import com.psyche.memo.provider.browser.addressInputToUrl
import com.psyche.memo.provider.browser.isAllowedUrl
import com.psyche.memo.ui.MemoTopBar
import com.psyche.memo.ui.overlaySurfaceColor
import com.psyche.memo.ui.theme.LocalSemanticColors
import com.psyche.memo.ui.theme.MemoRadius
import com.psyche.memo.ui.OverlayBackHandler
import com.psyche.memo.ui.R as UiR
import kotlinx.coroutines.launch

/**
 * 用户接管：打开期间 [BrowserSession.takeOver] 让工具侧一律回 `USER_CONTROLS_PAGE`（spec §6），
 * 关闭时成对 `detach()` + `release()`。
 *
 * 形状照 [HtmlPreviewScreen]：**同屏二级页**（`OverlayBackHandler` + 不透明整屏 Column +
 * [MemoTopBar]），**不是 `Dialog`** —— PORTING §5.31 那次「一点就闪退」的根因就是 `Dialog`
 * 拿不到 window token（`MainActivity` 是纯 `ComponentActivity`，ViewTree owner 只装在
 * activity-compose 自己那棵树上）。挂进来的是会话**那一枚标签**的 WebView：另建一枚就是另一个
 * 页面，用户看到的和模型操作的就成了两回事。
 *
 * 三件套（spec §12.2/§12.3）：**标签条 + 地址栏 + 页面**。
 * - 标签条读 [BrowserSession.tabsSnapshot]（StateFlow，写入点全在主线程），所以用户点第二个标签
 *   界面必然跟着换 —— 而模型那边的「活动标签」是**同一份状态**，不存在「界面在 A、模型在 B」。
 * - 地址栏**只吃 https**：没写协议先补 `https://`（[addressInputToUrl]，主流浏览器都这么干），
 *   补完仍要过 [isAllowedUrl]。补协议不等于放行：`http://` / `javascript:` 照样拒，而且拒了要
 *   **在界面上说**，否则用户只看到「输了地址没反应」。
 * - JS 弹窗画成**页面上面的一层**（不是 `Dialog`，理由同上一段）；文件选择借系统
 *   `OpenMultipleDocuments` —— 只有界面手里有 ActivityResultRegistry。
 *
 * 两颗顶栏按钮都只走 `onBack`，真正的交还由 `DisposableEffect.onDispose` 统一做 —— 于是销毁
 * 路径只有一条，不会出现「按钮自己 release 了一遍、onDispose 又 release 一遍、中间还忘了
 * detach」这种两本账。`交还给助手` 不清数据：用户可能只想自己看一眼再让助手接着做，
 * 页面与登录态都得留着。
 *
 * @param session 调用方**同步**从 `container.browserSessions.peek(conversationId)` 拿到的实例。
 *   [BrowserSession.attachTo] 自带 `closed` 闸（挂了就 return），但**这不等于可以跨挂起点持有
 *   引用再去挂**：那一拍里 `destroy()` 已经落地，视图挂不上、`DisposableEffect` 却已经对新实例
 *   takeOver() —— 用户看空白、模型被挡住，且失败静默。所以取值必须与判据同一条式子、同一个时刻。
 * @param onClearAndClose 「清空并关闭」委托给调用方派发：它必须落在**容器级** `appScope` 上
 *   （`ChatContent` 给的是 `container.appScope.launch { browserSessions.closeAll() }`）。
 *   页面作用域的 `rememberCoroutineScope()` 会随遮罩离开组合而取消 `close()` 的中段，
 *   留下未 destroy 的 WebView + 未清的 cookie jar（半清且无声）—— Task 7 已经为此修过一次，
 *   见 PORTING §5.69。store 才是 holder 的主人，遮罩自己不许去关实例。
 */
@Composable
fun BrowserOverlay(session: BrowserSession, onBack: () -> Unit, onClearAndClose: () -> Unit) {
    OverlayBackHandler(onBack)
    val cs = MaterialTheme.colorScheme

    DisposableEffect(session) {
        session.takeOver()
        onDispose {
            // 顺序有讲究：先把「等用户应答」的两笔了结（弹窗 cancel / 文件回调 null），再摘视图，
            // 最后交还。反过来会留几十毫秒的窗口：WebView 已经离手，回调还挂着。
            session.dropPendingInteractions()
            session.detach()
            session.release()
        }
    }

    val tabs by session.tabsSnapshot.collectAsState()
    val active = tabs.firstOrNull { it.active }
    val jsDialog by session.jsDialog.collectAsState()
    val fileRequest by session.fileRequest.collectAsState()
    val scope = rememberCoroutineScope()

    // 系统文件选择器：结果（含「用户取消」= null）都要回到会话那枚回调上，
    // 否则页面上那个 `<input type=file>` 永远按不动（spec §12.4）。
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> session.submitFiles(uris.orEmpty()) }
    LaunchedEffect(fileRequest) {
        fileRequest?.let { picker.launch(it.mimeTypes) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .statusBarsPadding()
            // **必须有这一发**：`MainActivity` 走 `enableEdgeToEdge()`，窗口不会随键盘缩小，
            // 于是系统改去 pan 整个窗口 —— 结果就是用户实测的「点击浏览器怎么会弹出选择模型的
            // sheet」：地址栏被顶到状态栏那个位置，点下去命中的是聊天页顶栏那颗模型选择器。
            // 自己让位（照仓里 AsrServicesEditorScreen:247 / ChatSelectionUi:71 那一套）之后，
            // 键盘只吃掉页面高度，顶栏与标签条原地不动。
            .imePadding(),
    ) {
        MemoTopBar(
            // 标题优先取**活动标签**的标题（StateFlow，随导航实时更新），退到 find 快照里的标题，
            // 再退到固定串 —— 接管时用户该看见自己在哪一页（spec §6）。
            title = active?.title?.takeIf { it.isNotBlank() }
                ?: session.snapshot?.title?.takeIf { it.isNotBlank() }
                ?: stringResource(UiR.string.browser_overlay_title),
            onBack = onBack,
            actions = {
                TextButton(onClick = onBack) {
                    Text(stringResource(UiR.string.browser_overlay_hand_back))
                }
                TextButton(onClick = {
                    onClearAndClose()
                    onBack()
                }) {
                    Text(stringResource(UiR.string.browser_overlay_clear_close))
                }
            },
        )
        TabStrip(session, tabs)
        AddressBar(
            session = session,
            active = active,
            onSubmit = { raw ->
                val url = addressInputToUrl(raw)
                when {
                    // 空输入没什么可提交，也不算「被本机挡下」—— 不许弹一句只允许 https。
                    url == null -> true
                    isAllowedUrl(url) -> {
                        scope.launch { session.navigateFromUi(url) }
                        true
                    }

                    else -> false
                }
            },
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // `key(session, 活动下标)`：换标签 = 换 WebView。`factory` 只在节点首次进入组合时跑，
            // 而「挂哪一枚视图」不能靠 `update` 的隐式重组时机（挂错一枚就是「点标签没反应」）；
            // 用 key 把整枚节点重建，factory 必然再跑一次，语义写在脸上。
            //
            // 第一个 key 管的是另一件事：遮罩开着的时候本会话实例也可能被别的会话 `sessionFor`
            // 抢走并销毁、随后本会话新建一枚。不加它就会继续挂旧引用 —— 遮罩画出**空白页**，
            // 而 `DisposableEffect(session)` 已经对新实例 takeOver() ⇒ 模型被挡住、用户什么也
            // 看不见，且失败静默。
            key(session, active?.index ?: 0) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        android.widget.FrameLayout(ctx).also { frame -> session.attachTo(frame) }
                    },
                    onRelease = { frame -> session.detachFrom(frame) },
                )
            }
            jsDialog?.let { dialog ->
                JsDialogLayer(
                    message = dialog.message,
                    needsText = dialog.kind == BrowserJsKind.PROMPT,
                    defaultValue = dialog.defaultValue.orEmpty(),
                    onConfirm = { text -> session.answerJsDialog(true, text) },
                    onDismiss = { session.answerJsDialog(false, null) },
                )
            }
        }
    }
}

/**
 * 标签条：一枚标签一张芯片（点=切活动，×=关），右边一颗 + 直到 [BrowserSession.MAX_TABS]。
 *
 * `selectTabFromUi` / `closeTabFromUi` 都是**主线程同步**调用（就在点击回调里），它们不取会话
 * 那把动作锁 —— 理由写在 `BrowserSession.selectTabFromUi` 上：同步代码不可能在两个挂起点之间
 * 插进来，而唯一并发的一路（模型的 JS 还挂着时用户切标签）的后果只是「多作废一次把手」，
 * 不会把动作点到另一枚标签上。
 */
@Composable
private fun TabStrip(session: BrowserSession, tabs: List<BrowserTabInfo>) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEach { tab ->
            val label = tab.title.ifBlank { tab.url }.ifBlank { "${tab.index + 1}" }
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(MemoRadius.PILL_DP.dp))
                    .background(
                        if (tab.active) cs.primaryContainer else cs.surfaceContainerHigh,
                        RoundedCornerShape(MemoRadius.PILL_DP.dp),
                    )
                    .clickable { session.selectTabFromUi(tab.index) }
                    .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = label.take(16),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (tab.active) cs.onPrimaryContainer else cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 名称区给个下界：空白标签的名字只有「1」这么宽，紧贴着 × 就会让
                    // 「点标签名切换」误触成「关掉这个标签」。
                    modifier = Modifier.widthIn(min = 32.dp, max = 120.dp),
                )
                // × 是一颗 26dp 的圆钮，**不是** TextButton：后者自带 48dp 最小触摸目标，
                // 会把整枚胶囊顶到 48dp 高（用户「这个标签胶囊太大了呀」量的就是那一版）；
                // 但我上一版一路收到 20dp，又小到按不准（同日「改的又有点太小了」）。
                // 26dp 钮 + 上下 6dp = 约 38dp 一行：够按，又不吃掉半屏页面。
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .clickable { session.closeTabFromUi(tab.index) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Lucide.X,
                        contentDescription = stringResource(UiR.string.browser_tab_close),
                        modifier = Modifier.size(15.dp),
                        tint = cs.onSurfaceVariant,
                    )
                }
            }
        }
        if (tabs.size < BrowserSession.MAX_TABS) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable { session.newTabFromUi() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Lucide.Plus,
                    contentDescription = stringResource(UiR.string.browser_tab_new),
                    modifier = Modifier.size(17.dp),
                    tint = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 地址栏。框里显示的是**当前页地址**（`active.url`），用户一开始改就跟着改动走（`draft`），
 * 换标签或换会话就清空草稿 —— 三条来源的优先级写在这一处，不散到调用方。
 *
 * 被 [isAllowedUrl] 挡下时不弹条、不闪退，只在这行下面落一句 [UiR.string.browser_address_blocked]：
 * 那句话必须让用户知道「是本机不让开」，不是「这个网页打不开」。
 */
@Composable
private fun AddressBar(
    session: BrowserSession,
    active: BrowserTabInfo?,
    onSubmit: (String) -> Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var draft by remember(session, active?.index) { mutableStateOf("") }
    var blocked by remember(session) { mutableStateOf(false) }
    val shown = draft.ifBlank { active?.url.orEmpty() }
    val go = { blocked = !onSubmit(shown) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        OutlinedTextField(
            value = shown,
            onValueChange = { value ->
                draft = value
                blocked = false
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            // 独立整行输入框走一级容器档（IosFormField 非 inline 同款）；SMALL 是行内紧凑字段专用。
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = semantic.surfaceCardFill,
                unfocusedContainerColor = semantic.surfaceCardFill,
                focusedBorderColor = cs.primary.copy(alpha = 0.4f),
                unfocusedBorderColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { go() }, onDone = { go() }),
            placeholder = { Text(stringResource(UiR.string.browser_address_hint)) },
            trailingIcon = {
                // 「前往」也压成紧凑内边距：默认那颗会把输入框顶胖。
                TextButton(
                    onClick = go,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text(stringResource(UiR.string.browser_address_go))
                }
            },
            isError = blocked,
        )
        if (blocked) {
            Text(
                text = stringResource(UiR.string.browser_address_blocked),
                style = MaterialTheme.typography.bodySmall,
                color = cs.error,
                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
            )
        }
    }
}

/**
 * JS 弹窗的**页面之上那一层**（spec §12.4）：不是 `Dialog`（同一原因 —— window token），是盖住
 * 页面的 scrim + 一张卡。`CONFIRM` 给两颗钮，`PROMPT` 多一个输入框，`ALERT` 只有「确定」。
 *
 * 三条出口（确定 / 取消 / 遮罩销毁兜底）都必须**恰好回答一次**那个 `JsResult`，否则这一页的 JS
 * 永远卡在弹窗上；「恰好一次」由 `BrowserJsDialog.answered` 那道闸保证，这里只负责把用户的意思
 * 递出去。
 */
@Composable
private fun JsDialogLayer(
    message: String,
    needsText: Boolean,
    defaultValue: String,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val semantic = LocalSemanticColors.current
    var text by remember(message) { mutableStateOf(defaultValue) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.scrim.copy(alpha = 0.4f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        // 配色与字号照 MemoAlertDialog 的基线（CARD 圆角 + overlaySurface 底 + 14sp 正文 +
        // 取消 onSurface@74% / 确认 primary），但**不能**用 MemoAlertDialog 本体 —— 那是
        // Dialog（window token 拿不到，§5.31），这里必须是页面之上的一层。
        Card(
            shape = RoundedCornerShape(MemoRadius.CARD_DP.dp),
            colors = CardDefaults.cardColors(containerColor = cs.overlaySurfaceColor()),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = message,
                    style = TextStyle(fontSize = 14.sp, color = cs.onSurface),
                )
                if (needsText) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        singleLine = true,
                        // 容器内嵌套块 = INNER 档；配色与地址栏同源（calm fill + focus ring）。
                        shape = RoundedCornerShape(MemoRadius.INNER_DP.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = semantic.surfaceCardFill,
                            unfocusedContainerColor = semantic.surfaceCardFill,
                            focusedBorderColor = cs.primary.copy(alpha = 0.4f),
                            unfocusedBorderColor = Color.Transparent,
                        ),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(
                            text = stringResource(UiR.string.browser_js_cancel),
                            style = TextStyle(fontSize = 14.sp, color = cs.onSurface.copy(alpha = 0.74f)),
                        )
                    }
                    TextButton(onClick = { onConfirm(if (needsText) text else null) }) {
                        Text(
                            text = stringResource(UiR.string.browser_js_confirm),
                            style = TextStyle(fontSize = 14.sp, color = cs.primary),
                        )
                    }
                }
            }
        }
    }
}
