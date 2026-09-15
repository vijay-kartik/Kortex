package dev.kortex.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.app.ui.KortexTheme
import dev.kortex.app.ui.screens.RootScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Session requested by a notification tap (share-intake result); consumed by RootScreen. */
    private val requestedSessionId = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The theme is committed dark, so pin light system-bar icons regardless of device theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        requestedSessionId.value = intent.getStringExtra(EXTRA_OPEN_SESSION_ID)
        setContent {
            KortexTheme {
                RootScreen(
                    requestedSessionId = requestedSessionId.collectAsStateWithLifecycle().value,
                    onSessionRequestConsumed = { requestedSessionId.value = null },
                )
            }
        }
    }

    // launchMode="singleTop": a notification tap while the app is open lands here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_SESSION_ID)?.let { requestedSessionId.value = it }
    }

    companion object {
        const val EXTRA_OPEN_SESSION_ID = "dev.kortex.app.OPEN_SESSION_ID"
    }
}
