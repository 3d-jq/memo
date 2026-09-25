package com.psyche.memo.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.psyche.memo.provider.browser.BrowserSession
import com.psyche.memo.ui.MemoTopBar
import com.psyche.memo.ui.OverlayBackHandler
import com.psyche.memo.ui.R as UiR

/**
 * 用户接管：打开期间 [BrowserSession.takeOver] 让工具侧一律回 `USER_CONTROLS_PAGE`（spec §6），
 * 关闭时成对 `detach()` + `release()`。
 *
 * 形状照 [HtmlPreviewScreen]：**同屏二级页**（`OverlayBackHandler` + 不透明整屏 Column +
 * [MemoTopBar]），**不是 `Dialog`** —— PORTING §5.31 那次「一点就闪退」的根因就是 `Dialog`
 * 拿不到 window token（`MainActivity` 是纯 `ComponentActivity`，ViewTree owner 只装在
 * activity-compose 自己那棵树上）。挂进来的是会话**那一个** WebView：新建一个就是另一个页面，
 * 用户看到的和模型操作的就成了两回事。
 *
 * 两颗按钮都只走 `onBack`，真正的交还由 `DisposableEffect.onDispose` 统一做 —— 于是销毁
 * 路径只有一条，不会出现「按钮自己 release 了一遍、onDispose 又 release 一遍、中间还
 * 忘了 detach」这种两本账。`交还给助手` 不清数据：用户可能只想自己看一眼再让助手接着做，
 * 页面与登录态都得留着。
 *
 * @param session 调用方**同步**从 `container.browserSessions.peek(conversationId)` 拿到的实例。
 *   [BrowserSession.attachTo] 没有 `closed` 闸（那是工具侧的入口，界面这条路径必须自己保证），
 *   所以这里不许跨挂起点持有引用再去挂 —— 把已 `destroy()` 的 WebView `addView` 回去真机必炸。
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
    // spec §6 的「遮罩顶部：当前标题」。`snapshot` 是只读的内存字段（与 `peek` 同量级），
    // 直接读即可 —— 不订阅、不进 LaunchedEffect，那才是组合期该干的事。
    val pageTitle = session.snapshot?.title?.takeIf { it.isNotBlank() }
        ?: stringResource(UiR.string.browser_overlay_title)

    DisposableEffect(session) {
        session.takeOver()
        onDispose {
            session.detach()
            session.release()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(cs.surface)
            .statusBarsPadding(),
    ) {
        MemoTopBar(
            title = pageTitle,
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
        // `key(session)`：遮罩开着的时候本会话实例也可能被别的会话 `sessionFor` 抢走并
        // 销毁、随后本会话新建一枚。`factory` 只在节点首次进入组合时跑 —— 不加 key 就会
        // 继续挂旧引用：遮罩画出**空白页**，而 `DisposableEffect(session)` 已经对新实例
        // takeOver() ⇒ 模型被挡住、用户什么也看不见，且失败静默。
        key(session) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    android.widget.FrameLayout(ctx).also { frame -> session.attachTo(frame) }
                },
                onRelease = { session.detach() },
            )
        }
    }
}
