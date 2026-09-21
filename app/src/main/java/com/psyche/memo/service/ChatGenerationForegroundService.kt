package com.psyche.memo.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.psyche.memo.MainActivity
import com.psyche.memo.R

private const val TAG = "ChatGenerationFgs"

/**
 * 在有聊天生成进行时把进程保持在前台 —— RikkaHub
 * service/ChatGenerationForegroundService.kt 移植。生成本体仍归
 * ChatViewModel 所有；本服务只提供 Activity 退到后台后流式继续所需的
 * 前台服务生命周期。onTimeout 通过 [ChatBackgroundController] 的回调
 * 停掉对应生成（RikkaHub 侧为 Koin 注入 ChatService，Memo 无 DI 容器）。
 */
class ChatGenerationForegroundService : Service() {
    companion object {
        private const val ACTION_ACQUIRE = "com.psyche.memo.action.CHAT_GENERATION_ACQUIRE"
        private const val ACTION_RELEASE = "com.psyche.memo.action.CHAT_GENERATION_RELEASE"
        private const val EXTRA_GENERATION_ID = "generation_id"
        private const val EXTRA_CONVERSATION_ID = "conversation_id"

        const val NOTIFICATION_ID = 2002

        fun acquire(context: Context, generationId: String, conversationId: String): Boolean {
            val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                action = ACTION_ACQUIRE
                putExtra(EXTRA_GENERATION_ID, generationId)
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            return runCatching {
                ContextCompat.startForegroundService(context, intent)
                true
            }.onFailure {
                Log.e(TAG, "Unable to start chat generation foreground service", it)
            }.getOrDefault(false)
        }

        fun release(context: Context, generationId: String) {
            val intent = Intent(context, ChatGenerationForegroundService::class.java).apply {
                action = ACTION_RELEASE
                putExtra(EXTRA_GENERATION_ID, generationId)
            }
            runCatching {
                context.startService(intent)
            }.onFailure {
                Log.e(TAG, "Unable to release chat generation foreground service", it)
            }
        }
    }

    private val activeGenerations = linkedMapOf<String, String>()
    private var isForeground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ACQUIRE -> acquire(intent)
            ACTION_RELEASE -> release(intent)
            else -> stopService()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeGenerations.clear()
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        super.onDestroy()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.e(TAG, "Foreground service timed out (type=$fgsType)")
        activeGenerations.values
            .distinct()
            .forEach { conversationId ->
                ChatBackgroundController.onForegroundServiceTimeout(conversationId)
            }
        // Android only allows a few seconds after onTimeout before raising RemoteServiceException.
        stopService()
    }

    private fun acquire(intent: Intent) {
        val generationId = intent.getStringExtra(EXTRA_GENERATION_ID) ?: return stopService()
        val conversationId = intent.getStringExtra(EXTRA_CONVERSATION_ID) ?: return stopService()
        activeGenerations[generationId] = conversationId
        updateForegroundNotification(conversationId)
    }

    private fun release(intent: Intent) {
        intent.getStringExtra(EXTRA_GENERATION_ID)?.let(activeGenerations::remove)
        if (activeGenerations.isEmpty()) {
            stopService()
        } else {
            updateForegroundNotification(activeGenerations.values.last())
        }
    }

    private fun updateForegroundNotification(conversationId: String) {
        try {
            val notification = buildNotification(conversationId)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForeground = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter foreground", e)
            activeGenerations.clear()
            stopSelf()
        }
    }

    private fun stopService() {
        if (isForeground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForeground = false
        }
        stopSelf()
    }

    private fun buildNotification(conversationId: String) =
        NotificationCompat.Builder(this, ChatBackgroundController.CHANNEL_CHAT_BACKGROUND)
            .setSmallIcon(R.drawable.ic_stat_memo)
            // core/ui strings android_background_notification_title/text
            // （app_zh.arb 同源三语，原文 Kelivo 已 Memo 化）。
            .setContentTitle(getString(com.psyche.memo.ui.R.string.android_background_notification_title))
            .setContentText(getString(com.psyche.memo.ui.R.string.android_background_notification_text))
            .setContentIntent(getConversationPendingIntent(conversationId))
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun getConversationPendingIntent(conversationId: String): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ChatBackgroundController.EXTRA_CONVERSATION_ID, conversationId)
        }
        return PendingIntent.getActivity(
            this,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
