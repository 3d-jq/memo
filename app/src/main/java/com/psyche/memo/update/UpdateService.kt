package com.psyche.memo.update

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 上游 `UpdateProvider.checkForUpdates`（update_provider.dart:85）的对应物：拉一次
 * 最新版本、和本机 versionName 比。端点是本项目自己的 GitHub Releases —— 上游那条
 * `kelivo.psycheas.top/update.json` 按品牌红线不能带（见 PORTING §5.37）。
 */
object UpdateService {

    const val RELEASE_URL = "https://api.github.com/repos/3d-jq/memo/releases/latest"

    sealed interface Outcome {
        data class Available(val info: UpdateInfo) : Outcome
        data object UpToDate : Outcome
        data class Failed(val message: String) : Outcome
    }

    /** 阻塞调用，放 IO 线程跑。共享的 LLM 客户端超时很长，这里单独收一下。 */
    fun check(client: OkHttpClient, currentVersion: String): Outcome {
        val http = client.newBuilder()
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url(RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .get()
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.code < 200 || response.code >= 300) {
                    return@use Outcome.Failed("HTTP ${response.code}")
                }
                val info = UpdateFeed.parseRelease(body)
                if (info == null) {
                    Outcome.Failed("Unrecognised release payload")
                } else if (UpdateFeed.isRemoteNewer(info.version, currentVersion)) {
                    Outcome.Available(info)
                } else {
                    Outcome.UpToDate
                }
            }
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
