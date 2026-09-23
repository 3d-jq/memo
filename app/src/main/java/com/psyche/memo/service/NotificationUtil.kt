package com.psyche.memo.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.psyche.memo.R

/**
 * 通知构建器的配置 DSL —— RikkaHub utils/NotificationUtil.kt 原样移植
 * （小图标换成 Memo 的 ic_stat_memo）。
 */
class NotificationConfig {
    var title: String = ""
    var content: String = ""
    var subText: String? = null
    var smallIcon: Int = R.drawable.ic_stat_memo
    var autoCancel: Boolean = false
    var ongoing: Boolean = false
    var onlyAlertOnce: Boolean = false
    var category: String? = null
    var visibility: Int = NotificationCompat.VISIBILITY_PRIVATE
    var contentIntent: PendingIntent? = null
    var useBigTextStyle: Boolean = false

    /**
     * Android 15+ 的**提升性常驻通知**（RikkaHub `requestPromotedOngoing`）：把这条 ongoing
     * 通知提升成状态栏上的实时活动芯片（乘车/外卖那种），[shortCriticalText] 就是芯片上那
     * 几个字。两个都只在 API 35+ 有效，低版本系统忽略（不会报错）。
     */
    var requestPromotedOngoing: Boolean = false
    var shortCriticalText: String? = null

    // 默认通知效果
    var useDefaults: Boolean = false
}

object NotificationUtil {

    /** 检查是否有通知权限。 */
    fun hasNotificationPermission(context: Context): Boolean {
        return ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** 使用 DSL 风格创建并发送通知，返回是否成功发送。 */
    @SuppressLint("MissingPermission")
    fun notify(
        context: Context,
        channelId: String,
        notificationId: Int,
        config: NotificationConfig.() -> Unit,
    ): Boolean {
        if (!hasNotificationPermission(context)) {
            return false
        }

        val notificationConfig = NotificationConfig().apply(config)
        val notification = buildNotification(context, channelId, notificationConfig)

        NotificationManagerCompat.from(context).notify(notificationId, notification.build())
        return true
    }

    /** 构建通知。 */
    fun buildNotification(
        context: Context,
        channelId: String,
        config: NotificationConfig,
    ): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, channelId).apply {
            setContentTitle(config.title)
            setContentText(config.content)
            setSmallIcon(config.smallIcon)
            setAutoCancel(config.autoCancel)
            setOngoing(config.ongoing)
            setOnlyAlertOnce(config.onlyAlertOnce)
            setVisibility(config.visibility)

            config.subText?.let { setSubText(it) }
            config.category?.let { setCategory(it) }
            config.contentIntent?.let { setContentIntent(it) }

            if (config.requestPromotedOngoing) setRequestPromotedOngoing(true)
            config.shortCriticalText?.let { setShortCriticalText(it) }

            if (config.useBigTextStyle) {
                setStyle(NotificationCompat.BigTextStyle().bigText(config.content))
            }

            if (config.useDefaults) {
                setDefaults(NotificationCompat.DEFAULT_ALL)
            }
        }
    }

    /** 取消通知。 */
    fun cancel(context: Context, notificationId: Int) {
        NotificationManagerCompat.from(context).cancel(notificationId)
    }

    /** 取消所有通知。 */
    fun cancelAll(context: Context) {
        NotificationManagerCompat.from(context).cancelAll()
    }
}

/** Context 扩展函数，简化通知发送。 */
fun Context.sendNotification(
    channelId: String,
    notificationId: Int,
    config: NotificationConfig.() -> Unit,
): Boolean = NotificationUtil.notify(this, channelId, notificationId, config)

/** Context 扩展函数，取消通知。 */
fun Context.cancelNotification(notificationId: Int) {
    NotificationUtil.cancel(this, notificationId)
}
