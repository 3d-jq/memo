package com.psyche.memo

import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer

/**
 * 调试期性能探针（**只在该 app 的 debug 构建里输出**；release 里 [begin]/[mark] 全是 no-op）。
 *
 * 为什么需要：真机 `dumpsys gfxinfo` 已经证明「卡在 UI 线程」（用户 2026-09-15 实测
 * 407 帧里 38 帧 janky，其中 37 帧记在 `Slow UI thread` 上，GPU 50/90 分位只有 2/3 ms）。
 * 但 gfxinfo 只说"UI 线程慢"，不说是**哪一段**。这个探针把
 * 「点会话 → 抽屉收起 → 新会话页组合完 → 首屏消息渲染完」这条链上的时间点，以及
 * 这一段窗口内的掉帧统计，打到 logcat（tag `MemoPerf`），用来定位真正的热点再改。
 *
 * 用法：正常用 app（点侧边栏里的会话），然后 `adb logcat -s MemoPerf`。
 */
internal object PerfProbe {

    private const val TAG = "MemoPerf"

    /** 观测窗口：点开一条会话后看 2 秒内的帧。 */
    private const val WINDOW_MS = 2_000L

    /** 超过这个帧间隔算掉帧（60Hz 一帧 16.7ms，取 2 帧以内可接受）。 */
    private const val JANK_MS = 33L

    /** 由 [MemoApplication] 在可调试构建里打开（release 恒 false ⇒ 全 no-op）。 */
    @Volatile
    var enabled: Boolean = false

    fun init(applicationInfo: android.content.pm.ApplicationInfo) {
        enabled = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    private var label: String? = null
    private var startMs = 0L
    private var frames = 0
    private var janky = 0
    private var worstMs = 0L
    private var lastFrameMs = 0L
    private var watching = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val nowMs = SystemClock.uptimeMillis()
            if (lastFrameMs != 0L) {
                val delta = nowMs - lastFrameMs
                frames++
                if (delta >= JANK_MS) janky++
                if (delta > worstMs) worstMs = delta
            }
            lastFrameMs = nowMs
            if (nowMs - startMs < WINDOW_MS && watching) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                watching = false
                Log.i(
                    TAG,
                    "summary [${label}] frames=$frames janky=$janky worst=${worstMs}ms " +
                        "(窗口 ${WINDOW_MS}ms)",
                )
            }
        }
    }

    /** 开始观测（用户点击那一刻调用）。 */
    fun begin(tag: String) {
        if (!enabled) return
        label = tag
        startMs = SystemClock.uptimeMillis()
        frames = 0
        janky = 0
        worstMs = 0
        lastFrameMs = 0
        watching = true
        // Choreographer 必须在有 Looper 的线程上取（主线程）。
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /** 打一个时间点（相对 [begin]）。 */
    fun mark(step: String) {
        if (!enabled) return
        val begin = startMs
        if (begin == 0L) {
            Log.i(TAG, "mark $step (没有 begin)")
            return
        }
        Log.i(TAG, "mark [${label}] $step +${SystemClock.uptimeMillis() - begin}ms")
    }

    /** 手动结束观测（不传也行，窗口到点自动收）。 */
    fun end() {
        if (!enabled) return
        watching = false
    }

    /** 当前构建是否带探针（测试用）。 */
    fun isEnabled(): Boolean = enabled
}
