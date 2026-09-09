package com.psyche.memo.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.psyche.memo.MainActivity
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.ReasoningPart
import com.psyche.memo.data.model.ReasoningSegment
import com.psyche.memo.data.model.ReasoningSegmentCodec
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.data.model.ToolCallPart
import java.util.concurrent.ConcurrentHashMap

// Live Update 通知节流间隔：流式输出每个 chunk 都会触发一次更新，
// notify() 是 binder IPC 且系统本身会对高频更新限流，必须在应用侧节流
// （RikkaHub LIVE_UPDATE_NOTIFICATION_THROTTLE_MS）。
private const val LIVE_UPDATE_NOTIFICATION_THROTTLE_MS = 1000L

/**
 * Live Update 进度通知 —— RikkaHub service/ChatNotificationManager.kt 移植。
 * RikkaHub 经 AppEventBus 订阅 ChatService 的生成事件；Memo 无事件总线，
 * 由 ChatViewModel 在流式回调 / 生成收尾处直接调用（事件语义 1:1）：
 * - [onGenerationUpdate] = ChatGenerationUpdate：app 后台 + 开关开时，以
 *   1s 节流覆盖前台服务同一条通知（id 同 ChatGenerationForegroundService），
 *   按 determineNotificationContent 的判定顺序显示"正在运行工具 / 思考中 /
 *   正在编写回复"进度。
 * - [onGenerationEnded] = ChatGenerationEnded：取消进度通知并清节流表；
 *   完成通知仍由 ChatBackgroundController 按原项目三态模式负责。
 *
 * 偏差：RikkaHub 通知还带 requestPromotedOngoing / shortCriticalText（chip
 * 文案仅用于后者，Android 15+/16 增强，需 androidx.core 1.17+）；Memo 在
 * core 1.16 下整体省略，chip_* 字符串资源不引入。
 */
object ChatNotificationManager {

    const val CHANNEL_CHAT_LIVE_UPDATE = "chat_live_update"

    /** RikkaHub DisplaySetting.enableLiveUpdateNotification（默认关）。 */
    const val PREF_ENABLE_LIVE_UPDATE = "enable_live_update_notification_v1"

    private var appContext: Context? = null
    private var readSetting: (String) -> String? = { null }

    private val liveUpdateLastSentAt = ConcurrentHashMap<String, Long>()

    /** MemoApplication.onCreate 注入（RikkaHub 构造注入等价）。 */
    fun init(context: Context, read: (String) -> String?) {
        appContext = context
        readSetting = read
    }

    fun liveUpdateEnabled(): Boolean = readSetting(PREF_ENABLE_LIVE_UPDATE) == "1"

    /**
     * 流式 chunk 更新（ChatService ChatGenerationUpdate.tryEmit 等价）。
     * [segmentsJson] 与 reasoning parts 按位置配对（ReasoningSegment 契约）；
     * 放在节流 gate 之后才解码，避免每个 chunk 白做 JSON 解析。
     */
    fun onGenerationUpdate(
        conversationId: String,
        senderName: String,
        parts: List<MessagePart>,
        segmentsJson: String?,
    ) {
        val context = appContext ?: return
        if (ChatBackgroundController.isAppForegroundNow()) return
        if (!liveUpdateEnabled()) return
        val now = SystemClock.elapsedRealtime()
        val lastSentAt = liveUpdateLastSentAt[conversationId]
        if (lastSentAt != null && now - lastSentAt < LIVE_UPDATE_NOTIFICATION_THROTTLE_MS) return
        liveUpdateLastSentAt[conversationId] = now

        val liveContent = determineContent(parts, ReasoningSegmentCodec.decode(segmentsJson))
        val (statusText, contentText) = when (liveContent.state) {
            LiveUpdateState.TOOL -> context.getString(
                com.psyche.memo.ui.R.string.notification_live_update_tool,
                liveContent.toolName.orEmpty(),
            ) to liveContent.text
            LiveUpdateState.THINKING -> context.getString(
                com.psyche.memo.ui.R.string.notification_live_update_thinking,
            ) to liveContent.text
            LiveUpdateState.WRITING -> context.getString(
                com.psyche.memo.ui.R.string.notification_live_update_writing,
            ) to liveContent.text
            LiveUpdateState.IDLE -> context.getString(
                com.psyche.memo.ui.R.string.notification_live_update_title,
            ) to ""
        }
        // RikkaHub sendLiveUpdateNotification：更新前台服务正在使用的同一条
        // 通知，避免重复显示生成进度。
        context.sendNotification(
            channelId = CHANNEL_CHAT_LIVE_UPDATE,
            notificationId = ChatGenerationForegroundService.NOTIFICATION_ID,
        ) {
            title = senderName
            content = contentText
            subText = statusText
            ongoing = true
            onlyAlertOnce = true
            category = NotificationCompat.CATEGORY_PROGRESS
            useBigTextStyle = true
            contentIntent = conversationPendingIntent(context, conversationId)
        }
    }

    /**
     * 生成结束（ChatService onCompletion 的 ChatGenerationEnded 等价）：
     * 清理进度通知与节流表。完成通知由 ChatBackgroundController 负责。
     */
    fun onGenerationEnded(conversationId: String) {
        val context = appContext ?: return
        liveUpdateLastSentAt.remove(conversationId)
        // 前台服务持有通知时系统会保留它；未启动时清理普通 ongoing 通知。
        context.cancelNotification(ChatGenerationForegroundService.NOTIFICATION_ID)
    }

    private fun conversationPendingIntent(context: Context, conversationId: String) =
        PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(ChatBackgroundController.EXTRA_CONVERSATION_ID, conversationId)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}

/** 通知三态（RikkaHub determineNotificationContent 的可测纯函数化）。 */
enum class LiveUpdateState { TOOL, THINKING, WRITING, IDLE }

data class LiveUpdateContent(
    val state: LiveUpdateState,
    val toolName: String?,
    val text: String,
)

/**
 * RikkaHub determineNotificationContent 判定顺序 1:1：工具执行中 > 思考中 >
 * 写回复 > 默认。工具执行中 = 最后一个工具 part 的 content 为空（执行完成
 * 才写入结果）；思考中 = 最后一个 reasoning part 按位置配对的 segment
 * finishedAt == null。截断量照抄 RikkaHub：工具入参 take(100)、思考/正文
 * takeLast(200)。
 */
fun determineContent(
    parts: List<MessagePart>,
    segments: List<ReasoningSegment>,
): LiveUpdateContent {
    val lastTool = parts.filterIsInstance<ToolCallPart>().lastOrNull()
        ?.let { ToolCallPart.decode(it.payloadJson) }
    if (lastTool != null && lastTool.content.isNullOrEmpty()) {
        // RikkaHub lastTool.toolName.substringAfterLast("__")（剥 MCP 前缀）。
        return LiveUpdateContent(
            LiveUpdateState.TOOL,
            lastTool.name.substringAfterLast("__"),
            lastTool.arguments.take(100),
        )
    }
    var reasoningIndex = -1
    var lastReasoningText: String? = null
    parts.forEach { part ->
        if (part is ReasoningPart) {
            reasoningIndex++
            lastReasoningText = part.text
        }
    }
    if (lastReasoningText != null && segments.getOrNull(reasoningIndex)?.finishedAt == null) {
        return LiveUpdateContent(LiveUpdateState.THINKING, null, lastReasoningText!!.takeLast(200))
    }
    val lastText = parts.filterIsInstance<TextPart>().lastOrNull()?.text
    if (lastText != null) {
        return LiveUpdateState.WRITING.let { LiveUpdateContent(it, null, lastText.takeLast(200)) }
    }
    return LiveUpdateContent(LiveUpdateState.IDLE, null, "")
}
