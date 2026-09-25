package com.psyche.memo.service

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.psyche.memo.MainActivity
import com.psyche.memo.R
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Android 后台聊天生成 —— 行为 1:1 原项目（display_settings_page.dart
 * AndroidBackgroundChatMode 三态 + notification_service.dart 完成通知条件），
 * 前台服务与通知基建移植自 RikkaHub（ChatGenerationForegroundService /
 * NotificationUtil）。
 *
 * 模式（key `android_background_chat_mode_v1`，默认 off）：
 * - OFF        不保活、不通知
 * - ON         前台服务保活（切后台/锁屏生成不中断）
 * - ON_NOTIFY  保活 + 生成完成通知（app 不在前台或不在此会话时，点通知回会话）
 */
object ChatBackgroundController {

    enum class AndroidBackgroundChatMode { OFF, ON, ON_NOTIFY }

    const val EXTRA_CONVERSATION_ID = "notification_conversation_id"
    const val CHANNEL_CHAT_BACKGROUND = "chat_background_v1"

    /**
     * 通知点击 → 打开会话的待处理请求：MainActivity.onCreate/onNewIntent
     * 写入，MemoApp 收集后转发给 HomeScreen 的 pendingOpenConversation
     * 管道（与 chat_history 的 onOpenConversation 同一入口）。
     */
    val pendingOpenConversationId = MutableStateFlow<String?>(null)

    private const val PREF_KEY = "android_background_chat_mode_v1"

    private val isAppForeground = MutableStateFlow(false)
    private lateinit var appContext: Context

    /** app 是否在前台（ChatNotificationManager 的 gate）。 */
    fun isAppForegroundNow(): Boolean = isAppForeground.value

    /** 会话 id -> 停止该会话生成的回调（前台服务超时时调用，RikkaHub onTimeout 语义）。 */
    private val stopGenerations = LinkedHashMap<String, () -> Unit>()

    /** ProcessLifecycleOwner 要求在主线程注册；在 [init]（Application）调用。 */
    fun init(application: Application) {
        appContext = application
        ensureChannels(application)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> isAppForeground.value = true
                    Lifecycle.Event.ON_STOP -> isAppForeground.value = false
                    else -> {}
                }
            },
        )
    }

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_CHAT_BACKGROUND,
            // 原项目 kelivo_bg_chat_v2 "Chat Background"。
            "后台生成",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "聊天生成状态通知"
        }
        manager.createNotificationChannel(channel)
        // RikkaHub RikkaHubApp.createNotificationChannel L224-232: Live Update
        // channel is LOW importance and silent (progress updates must not buzz).
        val liveUpdateChannel = NotificationChannel(
            ChatNotificationManager.CHANNEL_CHAT_LIVE_UPDATE,
            context.getString(com.psyche.memo.ui.R.string.notification_channel_chat_live_update),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            enableVibration(false) // RikkaHub setVibrationEnabled(false)
        }
        manager.createNotificationChannel(liveUpdateChannel)
    }

    /** settings_provider.dart:1371-1387 —— 读 key，非法值回退 off。 */
    fun modeOf(read: (String) -> String?): AndroidBackgroundChatMode = when (read(PREF_KEY)) {
        "on" -> AndroidBackgroundChatMode.ON
        "on_notify" -> AndroidBackgroundChatMode.ON_NOTIFY
        else -> AndroidBackgroundChatMode.OFF
    }

    fun writeModeValue(mode: AndroidBackgroundChatMode): String = when (mode) {
        AndroidBackgroundChatMode.OFF -> "off"
        AndroidBackgroundChatMode.ON -> "on"
        AndroidBackgroundChatMode.ON_NOTIFY -> "on_notify"
    }

    /**
     * notification_service.dart shouldShowChatCompleted 181-190 原样：
     * 仅 onNotify 模式，且不在前台或不在当前会话时通知。
     */
    fun shouldShowChatCompleted(
        isAndroid: Boolean,
        notifyModeEnabled: Boolean,
        appInForeground: Boolean,
        isCurrentConversation: Boolean,
    ): Boolean {
        if (!isAndroid || !notifyModeEnabled) return false
        // homeRouteVisible 在单 Activity 的 Memo 中不可得；前台 + 当前会话
        // 即等价于"正在看着这段生成"。
        return !(appInForeground && isCurrentConversation)
    }

    /**
     * 三态里哪几档要**前台服务保活**：「开」与「开+通知」都要，只有 off 不要。
     *
     * 拆成两条判据是因为原先把「保活」和「通知」写成同一支（只在 ON_NOTIFY 才 acquire），
     * 于是选「开」的用户什么也没得到。
     */
    fun shouldKeepAlive(mode: AndroidBackgroundChatMode): Boolean =
        mode != AndroidBackgroundChatMode.OFF

    /** 三态里哪几档发**完成通知**：只有「开+通知」。 */
    fun shouldNotifyCompletion(mode: AndroidBackgroundChatMode): Boolean =
        mode == AndroidBackgroundChatMode.ON_NOTIFY

    /**
     * 生成开始（ChatViewModel.startGeneration / regenerate）：**只要不是 off 就 acquire
     * 前台服务**（「开」＝只保活，「开+通知」＝保活 + 完成通知），并注册超时停止回调。
     *
     * 原先只在 `ON_NOTIFY` 才 acquire ⇒ 选「开」这一档时这个设置**什么都不做**：
     * 没有前台服务、没有保活、也没有任何通知（用户 2026-09-25「把实时通知显示那个做完整，
     * 就是退出 app 也可以继续那个部分」）。三态文案（[AndroidBackgroundChatMode] 的注释）
     * 与上游一致，是实现漏了这一支。
     */
    fun onGenerationStart(
        conversationId: String,
        mode: AndroidBackgroundChatMode,
        generationId: String,
        stopGeneration: () -> Unit,
    ) {
        if (!shouldKeepAlive(mode)) return
        stopGenerations[conversationId] = stopGeneration
        ChatGenerationForegroundService.acquire(appContext, generationId, conversationId)
    }

    /**
     * 生成结束（finally）：release 前台服务；ON_NOTIFY 且通知条件满足时发
     * 完成通知（固定文案 app_zh.arb notificationChatCompletedTitle/Body）。
     */
    fun onGenerationEnd(
        conversationId: String,
        mode: AndroidBackgroundChatMode,
        generationId: String,
        isCurrentConversation: Boolean,
    ) {
        stopGenerations.remove(conversationId)
        if (!shouldKeepAlive(mode)) return
        // 两档都 acquire 过，就都要 release（否则「开」这一档服务永远停不掉）。
        ChatGenerationForegroundService.release(appContext, generationId)
        if (shouldNotifyCompletion(mode)) {
            val notify = shouldShowChatCompleted(
                isAndroid = true,
                notifyModeEnabled = true,
                appInForeground = isAppForeground.value,
                isCurrentConversation = isCurrentConversation,
            )
            if (notify) {
                sendCompletionNotification(conversationId)
            }
        }
    }

    /** ChatGenerationForegroundService.onTimeout 回调：停掉该会话生成。 */
    fun onForegroundServiceTimeout(conversationId: String) {
        stopGenerations.remove(conversationId)?.invoke()
    }

    private fun sendCompletionNotification(conversationId: String) {
        val intent = android.content.Intent(appContext, MainActivity::class.java).apply {
            flags = android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            appContext,
            conversationId.hashCode(),
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        appContext.sendNotification(
            channelId = CHANNEL_CHAT_BACKGROUND,
            notificationId = 1001,
        ) {
            // core/ui strings notification_chat_completed_title / body
            // （app_zh.arb notificationChatCompletedTitle/Body 同源三语）。
            title = appContext.getString(com.psyche.memo.ui.R.string.notification_chat_completed_title)
            content = appContext.getString(com.psyche.memo.ui.R.string.notification_chat_completed_body)
            autoCancel = true
            useDefaults = true
            category = NotificationCompat.CATEGORY_MESSAGE
            contentIntent = pendingIntent
        }
    }
}
