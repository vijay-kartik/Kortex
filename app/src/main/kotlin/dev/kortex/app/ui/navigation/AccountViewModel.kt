package dev.kortex.app.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.sync.CloudAccount
import dev.kortex.sync.CloudUser
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** The signed-in account shown in the home menu, and logging out of it. */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val account: CloudAccount,
) : ViewModel() {

    val user: StateFlow<CloudUser?> = account.user

    /** AuthGate sees the user go and returns to the sign-in screen. */
    fun logOut() {
        viewModelScope.launch { account.signOut() }
    }
}
