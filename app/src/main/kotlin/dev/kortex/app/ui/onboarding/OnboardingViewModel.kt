package dev.kortex.app.ui.onboarding

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.sync.CloudAccount
import dev.kortex.sync.CloudUser
import dev.kortex.sync.SignInResult
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class OnboardingStep { Welcome, AllSet }

data class OnboardingUi(
    val step: OnboardingStep = OnboardingStep.Welcome,
    val user: CloudUser? = null,
    val signingIn: Boolean = false,
    /** Shown above the sign-in button; null after a cancel, which isn't an error. */
    val error: String? = null,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val account: CloudAccount,
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
            val result = account.signIn(activity)
            _ui.update {
                when (result) {
                    is SignInResult.Success -> it.copy(signingIn = false, step = OnboardingStep.AllSet, user = result.user)
                    SignInResult.Cancelled -> it.copy(signingIn = false)
                    is SignInResult.Failed -> it.copy(signingIn = false, error = result.message)
                }
            }
        }
    }
}
