package com.psyche.memo

import androidx.test.core.app.ApplicationProvider
import com.psyche.memo.data.settings.PreferenceRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

/**
 * `backup_reminder_provider.dart` semantics: the due moment is
 * `(lastBackupAt ?: enabledAt) + intervalDays` at the reminder time of day,
 * a completed backup resets the clock, and a snooze only lasts the session.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupReminderTest {

    private lateinit var container: AppContainerImpl

    @Before
    fun setUp() {
        container = AppContainerImpl(ApplicationProvider.getApplicationContext())
    }

    private fun reminder() = BackupReminder(container.preferenceRepository)

    private val enabledAt = LocalDateTime.of(2026, 9, 1, 8, 0)
    private val dueAt = LocalDateTime.of(2026, 9, 8, 9, 30)

    @Test
    fun `due fires when the interval and time have passed`() {
        val reminder = reminder()
        reminder.saveSchedule(enabled = true, intervalDays = 7, reminderMinutesOfDay = 9 * 60 + 30, now = enabledAt)

        assertFalse(reminder.shouldShowReminderFlow.value)
        reminder.evaluateDue(dueAt)
        assertTrue(reminder.shouldShowReminderFlow.value)
        assertEquals(dueAt, reminder.nextReminderAt())
    }

    @Test
    fun `due exactly at the next moment counts as due`() {
        val reminder = reminder()
        reminder.saveSchedule(enabled = true, intervalDays = 7, reminderMinutesOfDay = 9 * 60 + 30, now = enabledAt)
        val exact = enabledAt.plusDays(7).withHour(9).withMinute(30)
        reminder.evaluateDue(exact)
        assertTrue(reminder.shouldShowReminderFlow.value)
    }

    @Test
    fun `a completed backup pushes the next reminder a full interval away`() {
        val reminder = reminder()
        reminder.saveSchedule(enabled = true, intervalDays = 7, reminderMinutesOfDay = 9 * 60 + 30, now = enabledAt)
        reminder.evaluateDue(dueAt)
        assertTrue(reminder.shouldShowReminderFlow.value)

        reminder.recordBackupCompleted(dueAt)
        assertFalse(reminder.shouldShowReminderFlow.value)
        assertEquals(dueAt.plusDays(7), reminder.nextReminderAt())
    }

    @Test
    fun `snooze hides the banner for the session but not after a reload`() {
        val reminder = reminder()
        reminder.saveSchedule(enabled = true, intervalDays = 7, reminderMinutesOfDay = 9 * 60 + 30, now = enabledAt)
        reminder.evaluateDue(dueAt)
        assertTrue(reminder.shouldShowReminderFlow.value)

        reminder.snoozeForSession()
        assertFalse(reminder.shouldShowReminderFlow.value)
        reminder.evaluateDue(dueAt.plusMinutes(1))
        assertFalse("snooze lasts the session", reminder.shouldShowReminderFlow.value)

        val reloaded = reminder()
        assertTrue("a fresh session shows the banner again", reloaded.shouldShowReminderFlow.value)
    }

    @Test
    fun `a restored schedule keeps its enabledAt anchor and time`() {
        val first = reminder()
        first.saveSchedule(enabled = true, intervalDays = 3, reminderMinutesOfDay = 21 * 60, now = enabledAt)

        val second = reminder()
        assertTrue(second.enabledFlow.value)
        assertEquals(3, second.intervalDaysFlow.value)
        assertEquals(21 * 60, second.reminderMinutesOfDayFlow.value)
        assertEquals(enabledAt.plusDays(3).withHour(21).withMinute(0), second.nextReminderAt())
    }

    @Test
    fun `a disabled reminder never shows`() {
        val reminder = reminder()
        reminder.saveSchedule(enabled = true, intervalDays = 1, reminderMinutesOfDay = 0, now = enabledAt)
        reminder.disable()
        reminder.evaluateDue(dueAt)
        assertFalse(reminder.shouldShowReminderFlow.value)
        assertNull(reminder.nextReminderAt())
    }
}
