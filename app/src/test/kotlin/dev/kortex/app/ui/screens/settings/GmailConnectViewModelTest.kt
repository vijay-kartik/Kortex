package dev.kortex.app.ui.screens.settings

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import dev.kortex.app.domain.gmail.FakeGmailAccess
import dev.kortex.app.domain.gmail.GmailToken
import dev.kortex.app.ui.screens.settings.GmailConnectViewModel.Effect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Settings › NATIVE GMAIL connect flow, with the device's accounts faked. */
@OptIn(ExperimentalCoroutinesApi::class)
class GmailConnectViewModelTest {
    private val consentIntent = Intent()
    private val needsConsent = GmailToken.NeedsConsent(consentIntent)
    private val granted = GmailToken.Granted("me@gmail.com", "t1")

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `connect opens the account picker`() = runTest {
        val gmail = FakeGmailAccess()
        val vm = GmailConnectViewModel(gmail, SavedStateHandle())

        vm.connect()

        assertSame(gmail.pickIntent, (vm.effects.first() as Effect.PickAccount).intent)
    }

    @Test
    fun `an account that already allows reading connects straight away`() = runTest {
        val gmail = FakeGmailAccess(granted)
        val vm = GmailConnectViewModel(gmail, SavedStateHandle())

        vm.onAccountPicked("me@gmail.com")

        assertEquals(listOf("me@gmail.com"), gmail.connected)
    }

    @Test
    fun `an account that needs consent asks for it, then connects once granted`() = runTest {
        val gmail = FakeGmailAccess(needsConsent, granted)
        val vm = GmailConnectViewModel(gmail, SavedStateHandle())

        vm.onAccountPicked("me@gmail.com")
        assertSame(consentIntent, (vm.effects.first() as Effect.AskConsent).intent)
        assertTrue(gmail.connected.isEmpty())

        vm.onConsentResult(granted = true)

        assertEquals(listOf("me@gmail.com"), gmail.connected)
    }

    @Test
    fun `the consent result still connects after the activity is recreated`() = runTest {
        val gmail = FakeGmailAccess(needsConsent, granted)
        val savedState = SavedStateHandle()
        GmailConnectViewModel(gmail, savedState).onAccountPicked("me@gmail.com")

        // The process went away while the consent screen was up; only the saved state came back.
        val restoredState = SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) })
        GmailConnectViewModel(gmail, restoredState).onConsentResult(granted = true)

        assertEquals(listOf("me@gmail.com"), gmail.connected)
    }

    @Test
    fun `refused consent connects nothing`() = runTest {
        val gmail = FakeGmailAccess(needsConsent)
        val vm = GmailConnectViewModel(gmail, SavedStateHandle())

        vm.onAccountPicked("me@gmail.com")
        vm.effects.first()
        vm.onConsentResult(granted = false)

        assertTrue(gmail.connected.isEmpty())
    }

    @Test
    fun `no token after consent says so`() = runTest {
        val gmail = FakeGmailAccess(needsConsent, GmailToken.Failed("Network down"))
        val vm = GmailConnectViewModel(gmail, SavedStateHandle())

        vm.onAccountPicked("me@gmail.com")
        vm.effects.first()
        vm.onConsentResult(granted = true)

        assertEquals(Effect.Message("Failed to get token after consent."), vm.effects.first())
        assertTrue(gmail.connected.isEmpty())
    }

    @Test
    fun `an error shows its message`() = runTest {
        val gmail = FakeGmailAccess(GmailToken.Failed("Google account 'me@gmail.com' not found on device."))
        val vm = GmailConnectViewModel(gmail, SavedStateHandle())

        vm.onAccountPicked("me@gmail.com")

        assertEquals(Effect.Message("Error: Google account 'me@gmail.com' not found on device."), vm.effects.first())
        assertTrue(gmail.connected.isEmpty())
    }
}
