package com.psyche.memo.data.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The instruction-injection seed prompt mirrors learning_mode_store.dart. */
class LearningModePromptTest {

    @Test
    fun `default prompt matches the upstream text`() {
        val prompt = LearningModePrompt.DEFAULT
        assertTrue(prompt.startsWith("You are currently STUDYING"))
        assertTrue(prompt.contains("DO NOT GIVE ANSWERS OR DO HOMEWORK FOR THE USER"))
        assertEquals(2607, prompt.length)
    }
}
