package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.common.logging.RequestLogger
import com.psyche.memo.logging.LogBootstrap
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 日志设置里「自动删除」「日志大小上限」两项的**接线**（用户 2026-09-16
 * 「日志设置里面的功能做了没有呀」）。
 *
 * 清理逻辑本身（[RequestLogger.cleanupLogs]）在 `core:common` 早有单测，但它**当时
 * 没有任何调用点** —— 两项设置写进了偏好却从不生效。原版是在设置加载完
 *（`settings_provider.dart:1156`）和改动设置时（`:5563`/`:5575`）各调一次，这里锁住
 * 容器入口 [AppContainerImpl.maybeCleanupLogs] 真的会把过期日志删掉。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LogCleanupWiringTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(context)
    }

    private fun logsDir(): File = File(context.filesDir, "logs").apply { mkdirs() }

    private fun writeRotated(name: String, ageDays: Long) {
        val file = File(logsDir(), name)
        file.writeText("x")
        file.setLastModified(System.currentTimeMillis() - ageDays * 86_400_000L)
    }

    @Test
    fun `the container applies the configured retention window`() {
        val now = System.currentTimeMillis()
        writeRotated("logs_old.txt", ageDays = 30)
        writeRotated("logs_recent.txt", ageDays = 1)
        val active = File(logsDir(), RequestLogger.ACTIVE_FILE_NAME).apply { writeText("active") }
        active.setLastModified(now)
        LogBootstrap.setAutoDeleteDays(container.preferenceRepository, 7)

        container.maybeCleanupLogs()

        // 清理跑在 appScope(IO) 上：等它落地。
        val deadline = System.currentTimeMillis() + 5_000
        while (File(logsDir(), "logs_old.txt").exists() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }

        assertFalse(
            "超过 7 天的轮转日志应该被删掉",
            File(logsDir(), "logs_old.txt").exists(),
        )
        assertTrue(
            "保留期内的日志不能删",
            File(logsDir(), "logs_recent.txt").exists(),
        )
        assertTrue(
            "正在写的活动日志永远不能删",
            File(logsDir(), RequestLogger.ACTIVE_FILE_NAME).exists(),
        )
    }
}
