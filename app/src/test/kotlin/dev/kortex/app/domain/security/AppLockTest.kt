package dev.kortex.app.domain.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [AppLock] against in-memory settings and a fake clock: when it starts locked, and when it relocks. */
class AppLockTest {
    private var clock = 10_000L

    private fun appLock(settings: AppLockSettings) = AppLock(FakeAppLockSettingsRepository(settings), clock = { clock })

    private val oneMinute = AppLockSettings(enabled = true, lockAfter = LockAfter.ONE_MINUTE)

    @Test
    fun `cold start is locked when app lock is on`() {
        assertTrue(appLock(AppLockSettings(enabled = true)).locked.value)
    }

    @Test
    fun `cold start is unlocked when app lock is off`() {
        assertFalse(appLock(AppLockSettings(enabled = false)).locked.value)
    }

    @Test
    fun `relocks once the app has been in the background for lockAfter`() {
        val lock = appLock(oneMinute).apply { unlock() }

        lock.onAppBackgrounded()
        clock += LockAfter.ONE_MINUTE.millis
        lock.onAppForegrounded()

        assertTrue(lock.locked.value)
        assertEquals(1, lock.promptRequests.value)
    }

    @Test
    fun `stays unlocked when the app comes back before lockAfter`() {
        val lock = appLock(oneMinute).apply { unlock() }

        lock.onAppBackgrounded()
        clock += LockAfter.ONE_MINUTE.millis - 1
        lock.onAppForegrounded()

        assertFalse(lock.locked.value)
        assertEquals(0, lock.promptRequests.value)
    }

    @Test
    fun `immediately relocks on any return`() {
        val lock = appLock(AppLockSettings(enabled = true, lockAfter = LockAfter.IMMEDIATELY)).apply { unlock() }

        lock.onAppBackgrounded()
        lock.onAppForegrounded()

        assertTrue(lock.locked.value)
    }

    @Test
    fun `returning while still locked asks for the prompt again`() {
        val lock = appLock(oneMinute)

        lock.onAppBackgrounded()
        clock += 1
        lock.onAppForegrounded()
        lock.onAppBackgrounded()
        clock += 1
        lock.onAppForegrounded()

        assertTrue(lock.locked.value)
        assertEquals(2, lock.promptRequests.value)
    }

    @Test
    fun `foregrounding without a background does nothing`() {
        val lock = appLock(oneMinute).apply { unlock() }

        clock += LockAfter.FIFTEEN_MINUTES.millis
        lock.onAppForegrounded()

        assertFalse(lock.locked.value)
        assertEquals(0, lock.promptRequests.value)
    }

    @Test
    fun `leaving for the PIN screen is not leaving the app`() {
        val lock = appLock(oneMinute).apply { unlock() }

        lock.authInProgress = true
        lock.onAppBackgrounded()
        clock += LockAfter.FIFTEEN_MINUTES.millis
        lock.onAppForegrounded()

        assertFalse(lock.locked.value)
        assertEquals(0, lock.promptRequests.value)
    }

    @Test
    fun `never relocks while app lock is off`() {
        val lock = appLock(AppLockSettings(enabled = false))

        lock.onAppBackgrounded()
        clock += LockAfter.FIFTEEN_MINUTES.millis
        lock.onAppForegrounded()

        assertFalse(lock.locked.value)
        assertEquals(0, lock.promptRequests.value)
    }

    @Test
    fun `turning app lock off unlocks at once and is saved`() {
        val lock = appLock(AppLockSettings(enabled = true))

        lock.setEnabled(false)

        assertFalse(lock.locked.value)
        assertFalse(lock.settings.value.enabled)
    }

    @Test
    fun `turning app lock on does not lock the session in progress`() {
        val lock = appLock(AppLockSettings(enabled = false))

        lock.setEnabled(true)

        assertFalse(lock.locked.value)
        assertTrue(lock.settings.value.enabled)
    }

    @Test
    fun `a new lockAfter applies to the next return`() {
        val lock = appLock(oneMinute).apply { unlock() }
        lock.setLockAfter(LockAfter.FIVE_MINUTES)

        lock.onAppBackgrounded()
        clock += LockAfter.ONE_MINUTE.millis
        lock.onAppForegrounded()

        assertFalse(lock.locked.value)
        assertEquals(LockAfter.FIVE_MINUTES, lock.settings.value.lockAfter)
    }
}

private class FakeAppLockSettingsRepository(initial: AppLockSettings) : AppLockSettingsRepository {
    private val _settings = MutableStateFlow(initial)
    override val settings: StateFlow<AppLockSettings> = _settings.asStateFlow()

    override fun update(transform: (AppLockSettings) -> AppLockSettings) = _settings.update(transform)
}
