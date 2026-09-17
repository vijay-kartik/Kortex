package dev.kortex.app

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.kortex.design.KortexTheme
import dev.kortex.app.ui.screens.home.EntryRequest
import dev.kortex.app.ui.screens.home.RootScreen
import dev.kortex.links.ui.linkDomain
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Latest request from a notification, shortcut or share; RootScreen clears it once handled. */
    private var entryRequest by mutableStateOf<EntryRequest?>(null)

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
        setContent {
            KortexTheme {
                RootScreen(
                    entryRequest = entryRequest,
                    onEntryRequestHandled = { entryRequest = null },
                )
            }
        }
    }

    // launchMode="singleTop": a notification tap, share or shortcut while the app is open lands here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleEntryIntent(intent)
    }

    /**
     * Turns a notification tap, launcher shortcut or share into an [EntryRequest].
     * Returns false only for a share that was rejected.
     */
    private fun handleEntryIntent(intent: Intent): Boolean {
        intent.getStringExtra(EXTRA_OPEN_SESSION_ID)?.let {
            entryRequest = EntryRequest.OpenSession(it)
            return true
        }
        when (intent.action) {
            ACTION_SAVE_LINK -> entryRequest = EntryRequest.NewLink()
            ACTION_ASK_AGENT -> entryRequest = EntryRequest.NewChat()
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
            text.none(Char::isWhitespace) && linkDomain(text) != null -> entryRequest = EntryRequest.NewLink(text)
            else -> entryRequest = EntryRequest.NewChat(text)
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
