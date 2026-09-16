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

    /** Address for the new-link screen (empty from the shortcut); consumed by RootScreen. */
    private val newLinkUrl = MutableStateFlow<String?>(null)

    /** Composer text for a new chat (empty from the shortcut); consumed by RootScreen. */
    private val newChatDraft = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreation re-delivers the launch intent; only the first launch should act on it.
        if (savedInstanceState == null && !handleEntryIntent(intent)) {
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
                    newLinkUrl = newLinkUrl.collectAsStateWithLifecycle().value,
                    onNewLinkConsumed = { newLinkUrl.value = null },
                    newChatDraft = newChatDraft.collectAsStateWithLifecycle().value,
                    onNewChatConsumed = { newChatDraft.value = null },
                )
            }
        }
    }

    // launchMode="singleTop": a notification tap, share or shortcut while the app is open lands here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_SESSION_ID)?.let { requestedSessionId.value = it }
        handleEntryIntent(intent)
    }

    /** Routes launcher shortcuts and shares to their screen. Returns false only for a share that was rejected. */
    private fun handleEntryIntent(intent: Intent): Boolean {
        when (intent.action) {
            ACTION_SAVE_LINK -> newLinkUrl.value = ""
            ACTION_ASK_AGENT -> newChatDraft.value = ""
            Intent.ACTION_SEND -> return acceptShare(intent)
        }
        return true
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
            text.none(Char::isWhitespace) && linkDomain(text) != null -> newLinkUrl.value = text
            else -> newChatDraft.value = text
        }
        return true
    }

    companion object {
        const val EXTRA_OPEN_SESSION_ID = "dev.kortex.app.OPEN_SESSION_ID"

        // Launcher shortcut actions; must match res/xml/shortcuts.xml.
        const val ACTION_SAVE_LINK = "dev.kortex.app.action.SAVE_LINK"
        const val ACTION_ASK_AGENT = "dev.kortex.app.action.ASK_AGENT"
    }
}
