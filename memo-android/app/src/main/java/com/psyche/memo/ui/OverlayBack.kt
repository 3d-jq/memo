package com.psyche.memo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * 同屏二级页（overlay）统一的**系统返回**拦截。
 *
 * 工程里有一批「二级页」是**同屏叠一层 composable**，不是新的导航目的地：
 * 记忆提示词模板编辑器、记忆追踪详情、统计榜单全屏页、HTML 预览、日志文件页……
 * 它们的顶栏返回箭头是好的，但**系统返回键没人拦** —— 按下去会直接 pop 掉整条路由：
 *
 * · 设置 → 记忆 → 提示词模板，按返回**直接掉回主设置页**（用户 2026-09-16）；
 * · 对话里打开 HTML 预览，按返回**直接退出对话页**；
 * · 日志文件页同理（用户 2026-09-15，那批已在 `LogViewerScreen.OverlayScaffold` 修过）。
 *
 * 判据：**同屏二级页必须调一次 [OverlayBackHandler]**，把返回交给它自己的关闭动作
 * （回到上一级视图）。反过来，[androidx.compose.ui.window.Dialog] 与
 * `ModalBottomSheet` 形态的覆盖层**不要调** —— 系统返回由它们自己收掉。
 */
@Composable
internal fun OverlayBackHandler(onClose: () -> Unit) {
    BackHandler { onClose() }
}
