package com.psyche.memo

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.TextPart
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 打开会话时**首屏窗口**的取数语义（用户 2026-09-15「点击历史对话加载对话好卡呀」
 * 「对话还是卡到爆」，对照 Flutter 原版 `chat_service.dart:582-593` /
 * `chat_controller.dart:167-200`）：
 *
 *  1. 首屏只取尾窗 [ChatViewModel] 的 `TAIL_WINDOW`（40）条，不再顺手全表
 *     `SELECT COUNT(*)` 判断"还有没有更早的"—— 窗口装满就等于有（原版
 *     `LoadedTimelinePage.hasMoreBefore = start > 0`）；
 *  2. 分支选择器的版本表只查**窗口里出现过多版本**的组（`version > 0`），
 *     单版本组不进表（UI 侧 `?: 1` 兜底），因此一次打开不再扫整个会话；
 *  3. 往前翻页时补这一页的版本表，否则老消息里的分支选择器会消失
 *     （改成按需查以后必须补，见 [ChatViewModel.loadOlderMessages]）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatTimelineWindowTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(context)
    }

    private fun conversation(): String {
        val row = Conversation.create(title = "窗口测试")
        container.conversationDao.insert(row)
        return row.id
    }

    private fun insert(
        conversationId: String,
        order: Int,
        groupId: String = "g$order",
        version: Int = 0,
    ) {
        container.messageDao.insert(
            ChatMessage(
                id = ChatMessage.newId(),
                role = "assistant",
                parts = listOf<MessagePart>(TextPart("m$order")),
                timestamp = 1_700_000_000_000_000L + order,
                conversationId = conversationId,
                groupId = groupId,
                version = version,
                messageOrder = order,
            ),
        )
    }

    /** `init` → `reloadTail` 是异步的（viewModelScope + Dispatchers.IO）。 */
    private fun open(conversationId: String): ChatViewModel {
        val vm = ChatViewModel(container, conversationId)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !vm.sendEnabled.value) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("refreshTail 没跑完（sendEnabled 仍为 false）", vm.sendEnabled.value)
        return vm
    }

    @Test
    fun `first screen loads exactly the tail window and reports more before`() {
        val id = conversation()
        for (i in 0 until 45) insert(id, i)

        val vm = open(id)

        assertEquals(40, vm.messages.value.size)
        assertEquals("m5", vm.messages.value.first().parts.filterIsInstance<TextPart>().single().text)
        assertTrue("窗口装满就说明还有更早的", vm.hasMoreBefore.value)
    }

    @Test
    fun `a conversation shorter than the window has nothing before`() {
        val id = conversation()
        for (i in 0 until 5) insert(id, i)

        val vm = open(id)

        assertEquals(5, vm.messages.value.size)
        assertFalse(vm.hasMoreBefore.value)
    }

    @Test
    fun `only groups with a newer version land in the version table`() {
        val id = conversation()
        insert(id, 0, groupId = "g-single")
        insert(id, 1, groupId = "g-multi", version = 0)
        insert(id, 2, groupId = "g-multi", version = 1)
        insert(id, 3, groupId = "g-multi", version = 2)

        val vm = open(id)

        assertEquals(listOf(0, 1, 2), vm.versionInfo.value["g-multi"])
        assertFalse(
            "单版本组不进版本表（UI 用 `versionCount ?: 1` 兜底），否则等于全表扫",
            vm.versionInfo.value.containsKey("g-single"),
        )
    }

    @Test
    fun `paging back fills the version table of the older page`() {        val id = conversation()
        // 窗口之外的更早两行属于同一个多版本组（orders 0/1，尾窗取 5..44）。
        insert(id, 0, groupId = "g-old", version = 0)
        insert(id, 1, groupId = "g-old", version = 1)
        for (i in 2 until 45) insert(id, i)

        val vm = open(id)
        assertFalse("尾窗里没有多版本组", vm.versionInfo.value.containsKey("g-old"))

        val added = runBlocking { vm.loadOlderMessages() }

        assertTrue(added > 0)
        assertEquals(listOf(0, 1), vm.versionInfo.value["g-old"])
        assertFalse("一页取不满就到底了", vm.hasMoreBefore.value)
    }

    /**
     * 顶栏 `+` 的三态判据不能再靠组合期 `messageDao.count()`（用户 2026-09-15
     * 「对话点击加载还是卡」）：改由 VM 的「首屏已读」+ 窗口是否为空决定。
     */
    @Test
    fun `the empty-chat toggle only turns on after the first window was read`() {
        // 首屏还没回来：即使窗口为空也**不能**当成空会话（否则有消息的会话会先闪一下图标）。
        assertFalse(
            com.psyche.memo.ui.newActionToggleable(
                isTemporary = false,
                tailLoaded = false,
                messagesEmpty = true,
            ),
        )

        val withMessages = conversation()
        for (i in 0 until 3) insert(withMessages, i)
        val vm = open(withMessages)
        assertTrue("首屏读完必须置位", vm.tailLoaded.value)
        assertFalse(
            com.psyche.memo.ui.newActionToggleable(
                isTemporary = false,
                tailLoaded = vm.tailLoaded.value,
                messagesEmpty = vm.messages.value.isEmpty(),
            ),
        )

        val empty = conversation()
        val emptyVm = open(empty)
        assertTrue(emptyVm.tailLoaded.value)
        assertTrue(
            com.psyche.memo.ui.newActionToggleable(
                isTemporary = false,
                tailLoaded = emptyVm.tailLoaded.value,
                messagesEmpty = emptyVm.messages.value.isEmpty(),
            ),
        )

        // 临时会话恒为开关态（不落库，所以「有没有消息」永远是空）。
        assertTrue(
            com.psyche.memo.ui.newActionToggleable(
                isTemporary = true,
                tailLoaded = false,
                messagesEmpty = true,
            ),
        )
    }
}
