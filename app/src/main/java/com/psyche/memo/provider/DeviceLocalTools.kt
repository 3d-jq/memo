package com.psyche.memo.provider

import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Port of the Android half of local_tools_service.dart's device tools
 * (android/app/.../DeviceLocalToolsHandler.kt): the pure foreground-time
 * computation for get_screen_time plus the permission helpers the assistant
 * local-tools tab needs.
 *
 * Calendar / reminders / location / weather / health stay out: location,
 * weather and health are iOS-only upstream and the calendar executors land
 * with their UI batch.
 */
object DeviceLocalTools {

    private const val LOOKBACK_MS = 12L * 60 * 60 * 1000

    /** One usage event (MOVE_TO_FOREGROUND / MOVE_TO_BACKGROUND). */
    data class UsageEvent(val timestampMs: Long, val packageName: String, val foreground: Boolean)

    /**
     * "Global single foreground" model: at any moment at most one app counts,
     * a new foreground settles the previous one, screen-off stops the clock,
     * and the window is extended backwards by [LOOKBACK_MS] so a segment that
     * started before [startMs] is clipped into range (computeForegroundTime).
     */
    fun computeForegroundTime(
        events: List<UsageEvent>,
        startMs: Long,
        endMs: Long,
        excludedPackages: Set<String> = emptySet(),
    ): Map<String, Long> {
        val foregroundMs = HashMap<String, Long>()
        var currentPkg: String? = null
        var currentStart = 0L

        fun settle(until: Long) {
            val pkg = currentPkg
            currentPkg = null
            if (pkg == null || pkg in excludedPackages) return
            val from = maxOf(currentStart, startMs)
            val duration = until - from
            if (duration > 0) foregroundMs[pkg] = (foregroundMs[pkg] ?: 0L) + duration
        }

        for (event in events) {
            if (event.foreground) {
                if (event.packageName != currentPkg) {
                    settle(event.timestampMs)
                    currentPkg = event.packageName
                    currentStart = event.timestampMs
                }
            } else {
                // Any background/other event settles the running segment.
                settle(event.timestampMs)
            }
        }
        settle(endMs)
        return foregroundMs
    }

    /** Usage Access (PACKAGE_USAGE_STATS) granted? */
    fun hasUsageStatsPermission(context: Context): Boolean =
        runCatching {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val mode = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName,
                )
            }
            mode == android.app.AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

    fun openUsageAccessSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
