package dev.kortex.app.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.app.data.settings.SettingsStore
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val store: SettingsStore,
) : ViewModel() {

    /**
     * Starts at `true` so a returning user never sees the onboarding flash before DataStore
     * reports back; a genuine first run corrects it a frame later.
     */
    val onboardingSeen: StateFlow<Boolean> =
        store.myInfoOnboardingSeen.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun markOnboardingSeen() {
        viewModelScope.launch { store.setMyInfoOnboardingSeen() }
    }
}
