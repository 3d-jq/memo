package com.psyche.memo

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.model.ChatMessage
import com.psyche.memo.data.model.Conversation
import com.psyche.memo.data.model.MessagePart
import com.psyche.memo.data.model.TextPart
import com.psyche.memo.ui.chat.newActionToggleable
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
        // 30 秒不是随手加的：这是真实时钟轮询，GitHub 免费 runner 冷启动时（Robolectric 解
        // sqlite4java 原生库 + JIT 预热）5 秒等不完，CI 每轮挂的都是不同用例（run #6/#7）。
        // 断言本身没放宽 —— 加载没完成照样红。
        val deadline = System.currentTimeMillis() + 30_000
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
     * 会话页 VM 的回收（用户 2026-09-15「点击对话多还是会卡 / 从侧边栏到主页这个会很卡」）：
     * `viewModel(key = conversationId)` 不会因为 key 变化释放旧 VM，所以由容器在切会话时
     * 把不忙的旧 VM 清成空壳；切回来时 [ChatViewModel.ensureLoaded] 重新读首屏。
     */
    @Test
    fun `released conversations drop their window and reload when opened again`() {
        val id = conversation()
        for (i in 0 until 5) insert(id, i)
        val vm = open(id)
        assertEquals(5, vm.messages.value.size)

        vm.releaseForReuse()

        assertTrue("回收后不该再握着消息窗口", vm.messages.value.isEmpty())
        assertFalse("回收后要标记成未加载，切回来才会重读", vm.tailLoaded.value)
        assertFalse(vm.hasLoadedContent)
        assertFalse(vm.isBusy)

        vm.ensureLoaded()
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && vm.messages.value.isEmpty()) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertEquals("切回来必须把首屏读回来", 5, vm.messages.value.size)
    }

    @Test
    fun `opening another conversation reaps the idle previous one`() {
        val first = conversation()
        for (i in 0 until 3) insert(first, i)
        val vm1 = open(first)
        assertEquals(3, vm1.messages.value.size)
        container.registerChatViewModel(vm1)

        val second = conversation()
        insert(second, 0)
        val vm2 = open(second)
        container.registerChatViewModel(vm2)

        assertTrue("切走后上一条会话的 VM 应被清成空壳", vm1.messages.value.isEmpty())
        assertFalse(vm1.hasLoadedContent)
        // 当前屏上的这条不受影响。
        assertEquals(1, vm2.messages.value.size)
    }

    @Test
    fun `the empty-chat toggle only turns on after the first window was read`() {
        // 首屏还没回来：即使窗口为空也**不能**当成空会话（否则有消息的会话会先闪一下图标）。
        assertFalse(
            newActionToggleable(
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
            newActionToggleable(
                isTemporary = false,
                tailLoaded = vm.tailLoaded.value,
                messagesEmpty = vm.messages.value.isEmpty(),
            ),
        )

        val empty = conversation()
        val emptyVm = open(empty)
        assertTrue(emptyVm.tailLoaded.value)
        assertTrue(
            newActionToggleable(
                isTemporary = false,
                tailLoaded = emptyVm.tailLoaded.value,
                messagesEmpty = emptyVm.messages.value.isEmpty(),
            ),
        )

        // 临时会话恒为开关态（不落库，所以「有没有消息」永远是空）。
        assertTrue(
            newActionToggleable(
                isTemporary = true,
                tailLoaded = false,
                messagesEmpty = true,
            ),
        )
    }
}
