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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreation re-delivers the launch intent; only the first launch should act on a share.
        if (savedInstanceState == null && intent.action == Intent.ACTION_SEND) {
            val url = sharedLink(intent)
            if (url == null) {
                // Launched just for this share, so there's nothing to show behind the rejection.
                rejectShare()
                finish()
                return
            }
            sharedLinkUrl.value = url
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
                )
            }
        }
    }

    // launchMode="singleTop": a notification tap or share while the app is open lands here.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_SESSION_ID)?.let { requestedSessionId.value = it }
        if (intent.action == Intent.ACTION_SEND) {
            sharedLink(intent)?.let { sharedLinkUrl.value = it } ?: rejectShare()
        }
    }

    private fun rejectShare() {
        Toast.makeText(this, "Kortex can only save links — that wasn't a valid URL.", Toast.LENGTH_SHORT).show()
    }

    /** The shared text as a URL, or null unless the whole text is a single well-formed http(s) link. */
    private fun sharedLink(intent: Intent): String? {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        if (text.isEmpty() || text.any(Char::isWhitespace)) return null
        return text.takeIf { linkDomain(it) != null }
    }

    companion object {
        const val EXTRA_OPEN_SESSION_ID = "dev.kortex.app.OPEN_SESSION_ID"
    }
}
