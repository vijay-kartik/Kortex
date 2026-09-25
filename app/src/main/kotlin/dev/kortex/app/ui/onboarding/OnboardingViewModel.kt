package dev.kortex.app.ui.onboarding

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.sync.CloudAccount
import dev.kortex.sync.CloudSync
import dev.kortex.sync.CloudUser
import dev.kortex.sync.OtherAccountData
import dev.kortex.sync.SignInResult
import dev.kortex.sync.SyncOutcome
import dev.kortex.sync.SyncProgress
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** In the order the flow moves through them; the transition direction follows it. */
enum class OnboardingStep { Welcome, OtherAccountLinks, Restoring, AllSet }

/** How the sign-in's sync ended, for the summary on "all set". */
sealed interface SignInSync {
    /** [restored] is null when nothing came back: a new account, or one whose data was all deleted. */
    data class Done(val restored: Restored?) : SignInSync
    data class Failed(val message: String) : SignInSync
}

/** What a restore brought back, as the phone now holds it. */
data class Restored(val links: Int, val topics: Int)

data class OnboardingUi(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val user: CloudUser? = null,
    val signingIn: Boolean = false,
    /** Shown above the sign-in button; null after a cancel, which isn't an error. */
    val error: String? = null,
    /** Set on [OnboardingStep.OtherAccountLinks]: the links another account left on this phone. */
    val otherAccount: OtherAccountData? = null,
    /** Keeping or removing those links is under way. */
    val resolving: Boolean = false,
    /** Set on [OnboardingStep.Restoring]. */
    val restore: SyncProgress? = null,
    /** Set once the sign-in's sync has ended; null when "all set" is resumed after process death. */
    val sync: SignInSync? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val account: CloudAccount,
    private val cloudSync: CloudSync,
) : ViewModel() {

    // Signed in already means the process died on "all set"; resume there.
    private val _ui = MutableStateFlow(
        account.user.value?.let { OnboardingUi(step = OnboardingStep.AllSet, user = it) } ?: OnboardingUi(),
    )
    val ui: StateFlow<OnboardingUi> = _ui.asStateFlow()

    init {
        // This ViewModel outlives the flow (it's activity-scoped), so a sign-out restarts it at welcome.
        viewModelScope.launch {
            account.user.collect { if (it == null) _ui.value = OnboardingUi() }
        }
    }

    fun signIn(activity: Activity) {
        if (_ui.value.signingIn) return
        _ui.update { it.copy(signingIn = true, error = null) }
        viewModelScope.launch {
            when (val result = account.signIn(activity)) {
                is SignInResult.Success -> onSignedIn(result.user)
                SignInResult.Cancelled -> _ui.update { it.copy(signingIn = false) }
                is SignInResult.Failed -> _ui.update { it.copy(signingIn = false, error = result.message) }
            }
        }
    }

    private suspend fun onSignedIn(user: CloudUser) {
        _ui.update { it.copy(user = user) }
        // Links another account left here must be kept or removed before anything syncs.
        val other = cloudSync.otherAccountData(user)
        if (other != null) {
            _ui.update { it.copy(signingIn = false, otherAccount = other, step = OnboardingStep.OtherAccountLinks) }
            return
        }
        restore()
    }

    /** Adds the other account's links and topics to the one just signed in. */
    fun keepOtherAccountLinks() = resolve { cloudSync.keepLocalData(it) }

    /** Removes the other account's links and topics from this phone; its cloud copy stays. */
    fun removeOtherAccountLinks() = resolve { cloudSync.discardLocalData(it) }

    private fun resolve(action: suspend (CloudUser) -> Unit) {
        val user = _ui.value.user ?: return
        if (_ui.value.resolving) return
        _ui.update { it.copy(resolving = true) }
        viewModelScope.launch {
            action(user)
            restore()
        }
    }

    /**
     * Syncs the account in. The current screen keeps its spinner while the cloud is counted; an
     * account with a library moves to "restoring" with progress, a new one goes straight to
     * "all set" (Figma: Login & Logout 03 / 04).
     */
    private suspend fun restore() {
        val outcome = cloudSync.syncNow { progress ->
            if (!progress.isEmpty) _ui.update { it.copy(step = OnboardingStep.Restoring, restore = progress) }
        }
        val sync = when (outcome) {
            SyncOutcome.Done -> SignInSync.Done(restored = if (_ui.value.restore != null) restoredCounts() else null)
            is SyncOutcome.Failed -> SignInSync.Failed(outcome.message)
        }
        _ui.update {
            it.copy(
                signingIn = false,
                resolving = false,
                otherAccount = null,
                step = OnboardingStep.AllSet,
                sync = sync,
            )
        }
    }

    // The counts include deleted docs, so a restore can bring nothing back; say nothing then.
    private suspend fun restoredCounts(): Restored? =
        Restored(links = cloudSync.linkCount(), topics = cloudSync.topicCount()).takeIf { it.links + it.topics > 0 }
}
