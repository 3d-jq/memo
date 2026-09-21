package com.psyche.memo

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * FileProvider 的路径声明必须覆盖**整个**私有目录。
 *
 * 2026-09-21 用户实测：对话里点生成的视频**直接闪退** ——
 * `IllegalArgumentException: Failed to find configured root that contains
 * /data/data/com.psyche.memo/files/videos/vid_….mp4`，由 `FileProvider.getUriForFile` 抛出
 * （触摸分发链上抛的，所以是崩溃而不是提示）。当时 `file_paths.xml` 只声明了 `upload/`、
 * `images/`、`cache/`，而 Memo 自己写出来的文件散落在 `videos/`、`tool_images/`、`logs/`、
 * `workspaces/` 等一堆子目录里 —— 逐个声明的写法注定会漏，漏一个就在分享/打开时崩。
 *
 * 所以这里钉住：`files-path` 必须是 `.`（覆盖 filesDir 全部子目录），`cache-path` 同理。
 * provider 是 `exported=false` + `grantUriPermissions=true`，URI 只对显式授权的接收方有效，
 * 声明范围放宽不产生对外暴露。
 */
class FileProviderPathsTest {

    private val xml = File("src/main/res/xml/file_paths.xml")

    @Test
    fun declaresTheWholeFilesAndCacheRoots() {
        assertTrue("找不到 ${xml.path}（测试工作目录应为 app 模块）", xml.isFile)
        val text = xml.readText()

        assertTrue(
            "file_paths.xml 必须声明 <files-path path=\".\"/>，否则 filesDir 下任何新子目录" +
                "（例如 videos/）被 FileProvider 拿到时都会抛 IllegalArgumentException 崩溃。\n" +
                "当前内容：\n$text",
            Regex("""<files-path\b[^>]*\bpath\s*=\s*"\.""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text),
        )
        assertTrue(
            "file_paths.xml 必须声明 <cache-path path=\".\"/>（缓存里的文件也要能分享/打开）。\n" +
                "当前内容：\n$text",
            Regex("""<cache-path\b[^>]*\bpath\s*=\s*"\.""", RegexOption.IGNORE_CASE)
                .containsMatchIn(text),
        )
    }
}
