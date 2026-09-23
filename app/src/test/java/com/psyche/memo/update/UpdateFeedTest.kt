package com.psyche.memo.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 上游 update_provider.dart 的 _isRemoteNewer 与 UpdateInfo.fromJson 的对应测试。 */
class UpdateFeedTest {

    @Test
    fun comparesOnlyTheFirstThreeNumericSegments() {
        assertTrue(UpdateFeed.isRemoteNewer("1.0.1", "1.0.0"))
        assertTrue(UpdateFeed.isRemoteNewer("1.1.0", "1.0.9"))
        assertTrue(UpdateFeed.isRemoteNewer("2.0.0", "1.9.9"))
        assertFalse(UpdateFeed.isRemoteNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateFeed.isRemoteNewer("1.0.0", "1.0.1"))
        assertFalse(UpdateFeed.isRemoteNewer("0.9.9", "1.0.0"))
    }

    /** 上游忽略 build 号，且缺的段按 0 补 —— 所以 "1.0.0" 对 "1" 是相等，不算新版本。 */
    @Test
    fun treatsMissingSegmentsAsZeroAndIgnoresBuild() {
        assertTrue(UpdateFeed.isRemoteNewer("1.1", "1.0.0"))
        assertFalse(UpdateFeed.isRemoteNewer("1.0.0", "1"))
        assertFalse(UpdateFeed.isRemoteNewer("1.0.0+2073", "1.0.0+9"))
    }

    @Test
    fun toleratesPrefixesAndSuffixes() {
        assertTrue(UpdateFeed.isRemoteNewer("v1.2.3", "1.2.2"))
        assertFalse(UpdateFeed.isRemoteNewer("1.2.3-beta", "1.2.3"))
    }

    @Test
    fun parsesReleaseAndPrefersTheApkAsset() {
        val info = UpdateFeed.parseRelease(
            """
            {
              "tag_name": "v1.2.0",
              "html_url": "https://github.com/3d-jq/memo/releases/tag/v1.2.0",
              "published_at": "2026-09-21T10:00:00Z",
              "body": "修了若干问题",
              "assets": [
                {"name": "source.zip", "browser_download_url": "https://example.com/src.zip"},
                {"name": "memo-debug.apk", "browser_download_url": "https://example.com/memo.apk"}
              ]
            }
            """.trimIndent()
        )
        assertEquals("1.2.0", info?.version)
        assertEquals("修了若干问题", info?.notes)
        assertEquals("2026-09-21T10:00:00Z", info?.releasedAt)
        assertEquals("https://example.com/memo.apk", info?.downloadUrl)
        // 应用内下载要用它命名文件 / 当通知栏标题（用户 2026-09-23「实时更新通知」）。
        assertEquals("memo-debug.apk", info?.downloadName)
    }

    /** 没有 apk 资产（只发了 tag）就退回 release 页面，不能没有下载地址。 */
    @Test
    fun fallsBackToTheReleasePageWithoutAssets() {
        val info = UpdateFeed.parseRelease(
            """{"tag_name":"1.2.0","html_url":"https://github.com/3d-jq/memo/releases/tag/1.2.0"}"""
        )
        assertEquals("1.2.0", info?.version)
        assertEquals("https://github.com/3d-jq/memo/releases/tag/1.2.0", info?.downloadUrl)
        assertEquals("", info?.notes)
        assertNull(info?.releasedAt)
        // 只有页面时下载不了文件 —— UI 据此走浏览器（见 UpdateDownloader.start）。
        assertNull("没有 apk 资产时不该有文件名", info?.downloadName)
    }

    /** 资产没写 name 时用 URL 末段兜底（GitHub 的资产名就是文件名，但别假设它一定在）。 */
    @Test
    fun derivesTheAssetNameFromTheUrlWhenMissing() {
        val info = UpdateFeed.parseRelease(
            """
            {
              "tag_name": "1.2.0",
              "assets": [{"browser_download_url": "https://example.com/memo-v1.2.0.apk"}]
            }
            """.trimIndent()
        )
        assertEquals("memo-v1.2.0.apk", info?.downloadName)
    }

    @Test
    fun rejectsPayloadsWithoutATagOrNotAnObject() {
        assertNull(UpdateFeed.parseRelease("""{"html_url":"https://x"}"""))
        assertNull(UpdateFeed.parseRelease("[]"))
        assertNull(UpdateFeed.parseRelease("not json"))
    }
}
