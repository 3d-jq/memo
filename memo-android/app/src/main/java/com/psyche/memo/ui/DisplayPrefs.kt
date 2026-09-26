package com.psyche.memo.ui

import com.psyche.memo.AppContainerImpl
import com.psyche.memo.provider.browser.BrowserTools

/**
 * 显示类布尔偏好的**唯一入口**。
 *
 * 为什么要有：项目里同一个键存在**两种存储形态** —— 裸布尔 JSON（`true`/`false`，早期写入）
 * 与 `"1"`/`"0"`（行为/启动页写入）。`ChatViewModel` 早就为这个打过补丁（注释：「只认一种就会
 * 读成恒 false」），但**抽屉的日期分组开关**仍然只认 `"1"`，于是老键读出来是关 ——
 * 用户 2026-09-23 报「侧边栏怎么没有对话时间显示了」。
 *
 * 规矩：**读**两种都认（容错），**写**只写 `"1"`/`"0"`（单一形态，慢慢收敛）。
 */
object DisplayPrefs {

    /**
     * 显示类偏好的**版本号**（Compose 状态）。
     *
     * 为什么需要：聊天页 / 抽屉这些消费者把设置 `remember` 起来缓存，而「显示设置」的子页是
     * **同屏叠层**（不是导航目的地）——关掉时宿主不会重组，缓存的设置就一直是旧值，
     * 表现为「开关点了没反应」（用户 2026-09-23「显示助手头像点击根本没有反应」）。
     * 写入时 `+1`，消费者把它当 `remember` 的 key 即可立刻跟上。
     */
    private val revisionState = androidx.compose.runtime.mutableStateOf(0)
    var revision: Int
        get() = revisionState.value
        private set(value) {
            revisionState.value = value
        }

    /** 会话列表是否显示日期分组头（今天/昨天/…）。上游同名键，默认关。 */
    const val SHOW_CHAT_LIST_DATE = "display_show_chat_list_date_v1"

    /**
     * 消息列表里是否显示助手头像（**Memo 新增，上游没有**；**默认开**）。
     *
     * 语义就是**显示/隐藏**：关掉时助手消息头部不出现头像（回落到上游的「模型图标」开关）；
     * 打开时一定显示助手头像（助手没设头像则用首字母圆牌回退）。
     * 旧实现是「打开=强制、关闭=维持上游」——默认关时仍会显示头像，与「关闭就该不显示」不符
     *（用户 2026-09-23 实测指出）。
     */
    const val SHOW_ASSISTANT_AVATAR = "display_show_assistant_avatar_v1"

    /**
     * 布尔解码（**纯函数**，好测）：`"1"`/`"true"` → true，`"0"`/`"false"` → false，
     * 其它/缺失 → [default]。
     */
    fun decodeBool(raw: String?, default: Boolean): Boolean =
        when (raw?.trim()?.lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> default
        }

    fun readBool(container: AppContainerImpl, key: String, default: Boolean): Boolean =
        decodeBool(container.preferenceRepository.readJson(key), default)

    fun writeBool(container: AppContainerImpl, key: String, value: Boolean) {
        container.preferenceRepository.writeJson(key, if (value) "1" else "0")
        revision += 1
    }

    fun showChatListDate(container: AppContainerImpl): Boolean =
        readBool(container, SHOW_CHAT_LIST_DATE, default = false)

    fun showAssistantAvatar(container: AppContainerImpl): Boolean =
        readBool(container, SHOW_ASSISTANT_AVATAR, default = true)

    /** Agent 内置浏览器（本工程新增；**默认开**是用户 2026-09-25 的决定）。 */
    fun browserEnabled(container: AppContainerImpl): Boolean =
        readBool(container, BrowserTools.PREFERENCE_KEY, default = true)

    /**
     * [browserEnabled] 的写入侧。读写收在同一个文件里：键名 `BrowserTools.PREFERENCE_KEY`
     * 在生产代码里只许被 `DisplayPrefs` 引用，调用方不再手递键名 —— 否则将来改键名会
     * 「写进一个新键、读永远是默认开」，且没有任何测试会红。
     */
    fun writeBrowserEnabled(container: AppContainerImpl, value: Boolean) =
        writeBool(container, BrowserTools.PREFERENCE_KEY, value)
}
