package com.psyche.memo.provider.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.net.toUri
import com.psyche.memo.AppContainerImpl
import com.psyche.memo.workspace.RootfsPatchOptions
import com.psyche.memo.workspace.RootfsPatcher
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * 交互式终端会话 —— 上游 `WorkspaceTerminalSession.kt` 1:1 移植。
 *
 * 与上游的唯一结构差异：上游在 app 侧**又拼了一遍 proot argv 与 bind mount**，Memo 改成
 * 向 `core:workspace` 要（`ProotShellRunner.buildInteractiveArgv` + `WorkspaceManager.bindMounts()`）
 * —— 同一份挂载表既给一次性命令的 `-b`、也给这里的 PTY，才不会漂移。
 */

/** 按工作区准备 PTY 会话的三件套：装好 rootfs、打好补丁、跑起交互式 bash。 */
internal fun createWorkspaceTerminalSession(
    container: AppContainerImpl,
    root: String,
    client: TerminalSessionClient,
): TerminalSession {
    val manager = container.workspaceManager
    val runner = container.prootShellRunner
    val filesDir = manager.filesDir(root)
    val linuxDir = manager.linuxDir(root)
    val tempDir = manager.tempDir(root).apply { mkdirs() }

    val argv = runner.buildInteractiveArgv(
        linuxDir = linuxDir,
        filesDir = filesDir,
        bindMounts = manager.bindMounts(),
    )

    return TerminalSession(
        // TerminalSession 的 shellPath 就是 argv[0]（proot 本体），后面的是它的参数。
        argv.first(),
        filesDir.absolutePath,
        argv.drop(1).toTypedArray(),
        runner.loaderEnvironment(tempDir).map { (key, value) -> "$key=$value" }.toTypedArray(),
        2_000,
        client,
    ).apply {
        mSessionName = root
    }
}

/**
 * 起会话之前的准备工作（上游 `prepareWorkspaceTerminalSession`）：补齐三个目录，
 * 再用宿主的 DNS 给 rootfs 打补丁 —— 不补 DNS 沙箱里就没有网。
 */
internal fun prepareWorkspaceTerminalSession(context: Context, root: String, manager: com.psyche.memo.workspace.WorkspaceManager) {
    manager.filesDir(root).mkdirs()
    manager.tempDir(root).mkdirs()
    RootfsPatcher().patch(
        manager.linuxDir(root),
        RootfsPatchOptions(nameservers = context.activeDnsServers()),
    )
}

/** rootfs 装好了没（`bin/sh` 在不在）。 */
internal fun workspaceRootfsReady(manager: com.psyche.memo.workspace.WorkspaceManager, root: String): Boolean =
    manager.hasRootfs(root)

class WorkspaceTerminalSessionClient(
    private val context: Context,
    private val onFinished: () -> Unit,
) : TerminalSessionClient {
    var terminalView: TerminalView? = null

    override fun onTextChanged(changedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession) = Unit

    override fun onSessionFinished(finishedSession: TerminalSession) {
        terminalView?.onScreenUpdated()
        onFinished()
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
            ?: return
        val bytes = text.toByteArray()
        session.write(bytes, 0, bytes.size)
    }

    override fun onBell(session: TerminalSession) = Unit

    override fun onColorsChanged(session: TerminalSession) {
        terminalView?.invalidate()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {
        terminalView?.invalidate()
    }

    override fun getTerminalCursorStyle(): Int =
        TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal error", e)
    }
}

class WorkspaceTerminalViewClient(
    private val context: Context,
) : TerminalViewClient {
    var terminalView: TerminalView? = null
    var controlDown: Boolean = false
    var altDown: Boolean = false

    override fun onScale(scale: Float): Float = scale.coerceIn(0.8f, 1.25f)

    override fun onSingleTapUp(e: MotionEvent) {
        if (openUrlAtTap(e)) return
        focusAndShowKeyboard()
    }

    /**
     * 点击落在 URL 上就用浏览器打开。TerminalView 0.118.0 没有内置链接点击，
     * 这里基于 `getColumnAndRow()` + 屏幕缓冲文本自行实现，并通过 `getLineWrap()`
     * 还原被软换行拆开的长 URL。
     */
    private fun openUrlAtTap(e: MotionEvent): Boolean {
        val view = terminalView ?: return false
        if (view.isSelectingText) return false
        val emulator = view.mEmulator ?: return false
        val screen = emulator.getScreen()
        val columns = emulator.mColumns
        val columnAndRow = view.getColumnAndRow(e, true)
        val column = columnAndRow[0]
        val row = columnAndRow[1]
        val rows = emulator.mRows
        val minAccessibleRow = -screen.activeTranscriptRows
        val maxAccessibleRow = rows - 1
        if (column < 0 || column >= columns) return false
        if (row < minAccessibleRow || row > maxAccessibleRow) return false

        // 向上/向下扩展到完整逻辑行（被软换行拆开的行 mLineWrap 为 true）。
        // 限制最多扩展 URL_MAX_WRAP_ROWS 行：真实 URL 跨不了这么多行，同时避免连续无换行的
        // 长输出导致单次点击遍历整个 transcript。
        val minRow = (row - URL_MAX_WRAP_ROWS).coerceAtLeast(minAccessibleRow)
        val maxRow = (row + URL_MAX_WRAP_ROWS).coerceAtMost(maxAccessibleRow)
        var startRow = row
        while (startRow > minRow && screen.getLineWrap(startRow - 1)) startRow--
        var endRow = row
        while (endRow < maxRow && screen.getLineWrap(endRow)) endRow++

        val line = StringBuilder()
        var tapIndex = -1
        for (r in startRow..endRow) {
            if (r == row) {
                // 用 [0, column] 这段文本的长度精确换算点击字符在本行内的下标，避免宽字符错位
                tapIndex = line.length + (screen.getSelectedText(0, r, column, r).length - 1).coerceAtLeast(0)
            }
            line.append(screen.getSelectedText(0, r, columns - 1, r))
        }
        if (tapIndex < 0) return false

        val match = URL_REGEX.findAll(line).firstOrNull { tapIndex in it.range } ?: return false
        val url = match.value.trimEnd(*URL_TRAILING_TRIM)
        return runCatching {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.getOrElse {
            Log.w(TAG, "Failed to open url: $url", it)
            false
        }
    }

    fun focusAndShowKeyboard() {
        val view = terminalView ?: return
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        view.post {
            view.requestFocus()
            inputMethodManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) = Unit

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false

    override fun onLongPress(event: MotionEvent): Boolean = false

    override fun readControlKey(): Boolean = controlDown

    override fun readAltKey(): Boolean = altDown

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false

    override fun onEmulatorSet() = Unit

    override fun logError(tag: String, message: String) {
        Log.e(tag, message)
    }

    override fun logWarn(tag: String, message: String) {
        Log.w(tag, message)
    }

    override fun logInfo(tag: String, message: String) {
        Log.i(tag, message)
    }

    override fun logDebug(tag: String, message: String) {
        Log.d(tag, message)
    }

    override fun logVerbose(tag: String, message: String) {
        Log.v(tag, message)
    }

    override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {
        Log.e(tag, message, e)
    }

    override fun logStackTrace(tag: String, e: Exception) {
        Log.e(tag, "Terminal view error", e)
    }

    private companion object {
        const val TAG = "WorkspaceTerminal"

        // 一个 URL 最多还原跨越的软换行行数（向上/向下各算），足够覆盖任意真实 URL
        const val URL_MAX_WRAP_ROWS = 50

        val URL_REGEX =
            Regex("""(https?|ftp)://[\w\-._~:/?#\[\]@!$&'()*+,;=%]+""", RegexOption.IGNORE_CASE)

        // 终端里 URL 后面常跟标点（行尾句号、被括号包裹等），打开前去掉这些结尾字符
        val URL_TRAILING_TRIM = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')
    }
}

/** 宿主当前网络的 DNS（打 rootfs 补丁用；拿不到就返回空，`RootfsPatcher` 会退回公共 DNS）。 */
private fun Context.activeDnsServers(): List<String> {
    val connectivityManager =
        getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return emptyList()
    val network = connectivityManager.activeNetwork ?: return emptyList()
    return connectivityManager.getLinkProperties(network)
        ?.dnsServers
        ?.mapNotNull { it.hostAddress }
        .orEmpty()
}

/** 往会话写一段文本（附加键栏用）。 */
internal fun TerminalSession.writeText(text: String) {
    val bytes = text.toByteArray()
    write(bytes, 0, bytes.size)
}
