package com.psyche.memo.provider.generation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.assistant.AssistantStore
import com.psyche.memo.data.db.AssistantCache
import com.psyche.memo.data.db.loadSchemaStatements
import com.psyche.memo.data.generation.GenerationServiceStore
import com.psyche.memo.data.model.Assistant
import com.psyche.memo.data.model.AssistantGenerationBinding
import com.psyche.memo.data.model.GenerationKind
import com.psyche.memo.data.model.GenerationService
import com.psyche.memo.data.model.GenerationTestState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 生成服务仓储（自研功能）：变更计数、删除服务时**连带清助手绑定**、测试结果落库。
 * 存储那一半在 [GenerationServiceStore]，这里钉的是它对助手引用的处理。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GenerationServiceRepositoryTest {

    private lateinit var db: SQLiteDatabase
    private lateinit var assistants: AssistantStore
    private lateinit var repo: GenerationServiceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = SQLiteDatabase.create(null)
        for (statement in loadSchemaStatements(context)) db.execSQL(statement)
        AssistantCache.invalidate()
        assistants = AssistantStore(db)
        repo = GenerationServiceRepository(GenerationServiceStore(db), assistants)
    }

    @After
    fun tearDown() {
        AssistantCache.invalidate()
        db.close()
    }

    private fun imageService() = GenerationService(
        id = "",
        kind = GenerationKind.IMAGE,
        name = "画图",
        apiKey = "sk-image",
        model = "gpt-image-1",
    )

    /** 助手绑定：图片/视频各指向一个服务。 */
    private fun seedAssistant(imageId: String?, videoId: String?): String {
        val id = assistants.add("管家")
        val existing = assistants.get(id)!!
        assistants.update(
            existing.copy(
                imageGeneration = imageId?.let { AssistantGenerationBinding(enabled = true, serviceId = it) },
                videoGeneration = videoId?.let { AssistantGenerationBinding(enabled = true, serviceId = it) },
            ),
        )
        return id
    }

    @Test
    fun `create update and test results bump the version`() = runBlocking {
        val start = repo.version.value
        val created = repo.create(imageService())
        assertEquals(start + 1, repo.version.value)
        assertEquals(listOf(created.id), repo.list(GenerationKind.IMAGE).map { it.id })

        repo.update(created.copy(name = "画图2"))
        assertEquals("画图2", repo.get(created.id)?.name)

        repo.setTestState(created.id, GenerationTestState.OK)
        assertEquals(GenerationTestState.OK, repo.get(created.id)?.lastTestState)
        assertEquals(start + 3, repo.version.value)
    }

    @Test
    fun `deleting a service clears only the matching assistant binding`() = runBlocking {
        val img = repo.create(imageService())
        val vid = repo.create(imageService().copy(kind = GenerationKind.VIDEO, name = "视频", model = "sora-2"))
        val assistantId = seedAssistant(imageId = img.id, videoId = vid.id)

        assertTrue(repo.delete(img.id))

        val assistant = assistants.get(assistantId)!!
        assertNull("图片绑定应被清掉", assistant.imageGeneration)
        assertEquals("视频绑定不受影响", vid.id, assistant.videoGeneration?.serviceId)
    }

    @Test
    fun `deleting an unbound service leaves every assistant alone`() = runBlocking {
        val img = repo.create(imageService())
        val other = repo.create(imageService().copy(name = "另一个"))
        val assistantId = seedAssistant(imageId = other.id, videoId = null)

        repo.delete(img.id)

        assertEquals(other.id, assistants.get(assistantId)?.imageGeneration?.serviceId)
    }

    @Test
    fun `assistant copies a default binding object`() {
        // AssistantGenerationBinding 的默认值就是「关着、没选服务」。
        val binding = AssistantGenerationBinding()
        assertEquals(false, binding.enabled)
        assertNull(binding.serviceId)
        assertEquals(false, binding.isUsable)
        assertTrue(Assistant().imageGeneration == null && Assistant().videoGeneration == null)
    }
}
