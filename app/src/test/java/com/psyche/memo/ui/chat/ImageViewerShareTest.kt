package com.psyche.memo.ui.chat

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * ImageViewerOverlay 分享链路（image_viewer_page.dart _shareCurrent /
 * _saveCurrent 的 Android 移植）：materializeShareablePath 必须把本地文件、
 * data: URI 统一解析成可分享的本地路径，坏输入返回 null 并提示。
 * （FileProvider 的 content:// 包装层无法在 Robolectric 下稳定验证——
 * 静态 PathStrategy 缓存与临时 dataDir 冲突——只锁纯逻辑层。）
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageViewerShareTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun localFilePath_isUsedAsIs() {
        val file = File(context.filesDir, "share-fixture.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(file.absolutePath, materializeShareablePath(context, file.absolutePath))
    }

    @Test
    fun fileUriScheme_isAccepted() {
        val file = File(context.filesDir, "share-fixture-2.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(file.absolutePath, materializeShareablePath(context, "file://${file.absolutePath}"))
    }

    @Test
    fun dataUri_isMaterializedUnderCacheShare() {
        val pngBytes = android.util.Base64.encodeToString(byteArrayOf(1, 2, 3, 4), android.util.Base64.DEFAULT)
        val path = materializeShareablePath(context, "data:image/png;base64,$pngBytes")
        assertNotNull(path)
        // Materialized copy must live under cacheDir/share (declared in file_paths.xml).
        assertTrue(path!!.startsWith(File(context.cacheDir, "share").absolutePath))
        assertTrue(File(path).exists())
    }

    @Test
    fun missingFile_yieldsNull() {
        assertNull(materializeShareablePath(context, File(context.filesDir, "does-not-exist.png").absolutePath))
    }

    @Test
    fun emptyUrl_yieldsNull() {
        assertNull(materializeShareablePath(context, ""))
    }
}
