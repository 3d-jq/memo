package com.psyche.memo.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment

/**
 * 点「去下载」时在**应用内下载更新包** —— 1:1 照 RikkaHub `UpdateChecker.downloadUpdate`
 * （`utils/UpdateChecker.kt:65-85`）：交给系统 `DownloadManager`，
 * `VISIBILITY_VISIBLE_NOTIFY_COMPLETED` 让通知栏出现**实时进度**、下载完点一下就能装。
 *
 * 用户 2026-09-23「这个实时更新通知这个没有做好呀，跟 rikkhub 效果不一样呀」——
 * 我们原来只是 `ACTION_VIEW` 把 release 页面丢给浏览器，装完还得自己找文件。
 *
 * 起不来时**回落成开浏览器**（上游也是这么兜的）：API <= 28 写公共下载目录要
 * `WRITE_EXTERNAL_STORAGE`（见 manifest 里的 `maxSdkVersion="28"` 声明），没授权时
 * `enqueue` 会抛 SecurityException —— 那种情况下照旧把页面丢给浏览器，不能什么也不做。
 */
object UpdateDownloader {

    /**
     * 交给系统下载，返回是否成功入队。`fileName` 同时是通知栏标题、也是落盘文件名。
     * 阻塞极短（一次 IPC），但仍然别在组合期调 —— 调用点是点击回调。
     */
    fun enqueue(context: Context, url: String, fileName: String, description: String): Boolean =
        runCatching {
            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setTitle(fileName)
                setDescription(description)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE,
                )
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
            }
            val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            manager.enqueue(request)
            true
        }.getOrDefault(false)

    /**
     * 两个更新弹窗（启动时 / 关于页）共用的「去下载」动作：优先应用内下载，
     * 没有 `.apk` 资产（只有 release 页面）或入队失败时开浏览器。
     *
     * [description] 是通知栏里的说明文字，由 UI 层传入（`stringResource`）。
     */
    fun start(context: Context, release: UpdateInfo, description: String) {
        val name = release.downloadName
        if (name != null && enqueue(context, release.downloadUrl, name, description)) return
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.downloadUrl)))
        }
    }
}
