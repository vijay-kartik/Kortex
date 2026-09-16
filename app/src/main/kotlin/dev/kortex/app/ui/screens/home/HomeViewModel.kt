package dev.kortex.app.ui.screens.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kortex.app.KortexApp
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val store = (application as KortexApp).container.settingsStore

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
