package dev.kortex.app.ui.screens.settings

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.app.domain.gmail.GmailAccess
import dev.kortex.app.domain.gmail.GmailToken
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Tools & Settings › NATIVE GMAIL: pick a Google account, allow Kortex to read it, and keep it as
 * the connected account. The account waiting on consent is kept in [SavedStateHandle], so the
 * consent result still connects it after the activity is recreated.
 */
@HiltViewModel
class GmailConnectViewModel @Inject constructor(
    private val gmail: GmailAccess,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    sealed interface Effect {
        data class PickAccount(val intent: Intent) : Effect
        data class AskConsent(val intent: Intent) : Effect
        data class Message(val text: String) : Effect
    }

    private val _effects = Channel<Effect>(Channel.BUFFERED)
    val effects: Flow<Effect> = _effects.receiveAsFlow()

    private var pendingAccount: String?
        get() = savedState[PENDING_ACCOUNT]
        set(value) { savedState[PENDING_ACCOUNT] = value }

    fun connect() {
        _effects.trySend(Effect.PickAccount(gmail.pickAccountIntent()))
    }

    fun onAccountPicked(account: String?) {
        if (account.isNullOrBlank()) return
        pendingAccount = account
        viewModelScope.launch {
            when (val result = gmail.connect(account)) {
                is GmailToken.NeedsConsent -> _effects.send(Effect.AskConsent(result.intent))
                is GmailToken.Failed -> {
                    pendingAccount = null
                    _effects.send(Effect.Message("Error: ${result.message}"))
                }
                else -> pendingAccount = null
            }
        }
    }

    fun onConsentResult(granted: Boolean) {
        val account = pendingAccount ?: return
        pendingAccount = null
        if (!granted) return
        viewModelScope.launch {
            if (gmail.connect(account) !is GmailToken.Granted) {
                _effects.send(Effect.Message("Failed to get token after consent."))
            }
        }
    }

    private companion object {
        const val PENDING_ACCOUNT = "gmail_pending_account"
    }
}
