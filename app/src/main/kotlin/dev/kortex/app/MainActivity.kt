package dev.kortex.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.app.ui.KortexTheme
import dev.kortex.app.ui.screens.home.RootScreen
import dev.kortex.app.ui.screens.links.linkDomain
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Session requested by a notification tap (share-intake result); consumed by RootScreen. */
    private val requestedSessionId = MutableStateFlow<String?>(null)

    /** Link shared from another app, to open in the new-link screen; consumed by RootScreen. */
    private val sharedLinkUrl = MutableStateFlow<String?>(null)

    /** Non-link text shared from another app, to draft into a new chat; consumed by RootScreen. */
    private val sharedChatText = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreation re-delivers the launch intent; only the first launch should act on a share.
        if (savedInstanceState == null && intent.action == Intent.ACTION_SEND && !acceptShare(intent)) {
            // Launched just for this share, so there's nothing to show behind the rejection.
            finish()
            return
        }
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
                    sharedLinkUrl = sharedLinkUrl.collectAsStateWithLifecycle().value,
                    onSharedLinkConsumed = { sharedLinkUrl.value = null },
                    sharedChatText = sharedChatText.collectAsStateWithLifecycle().value,
                    onSharedChatTextConsumed = { sharedChatText.value = null },
                )
            }
        }
    }

    // launchMode="singleTop": a notification tap or share while the app is open lands here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_SESSION_ID)?.let { requestedSessionId.value = it }
        if (intent.action == Intent.ACTION_SEND) acceptShare(intent)
    }

    /**
     * Routes shared text: a single well-formed http(s) link goes to the new-link screen, anything
     * else is drafted into a new chat (not sent). Returns false, with a toast, when there's no text.
     */
    private fun acceptShare(intent: Intent): Boolean {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        when {
            text.isEmpty() -> {
                Toast.makeText(this, "Nothing to open — the shared text was empty.", Toast.LENGTH_SHORT).show()
                return false
            }
            text.none(Char::isWhitespace) && linkDomain(text) != null -> sharedLinkUrl.value = text
            else -> sharedChatText.value = text
        }
        return true
    }

    companion object {
        const val EXTRA_OPEN_SESSION_ID = "dev.kortex.app.OPEN_SESSION_ID"
    }
}
