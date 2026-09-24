package dev.kortex.app

import android.content.Intent
import android.os.Bundle
import android.view.animation.AccelerateInterpolator
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import androidx.fragment.app.FragmentActivity
import dev.kortex.design.KortexTheme
import dev.kortex.app.ui.screens.home.EntryRequest
import dev.kortex.app.ui.screens.home.RootScreen
import dev.kortex.app.domain.security.AppLock
import dev.kortex.app.ui.security.AppLockGate
import dev.kortex.app.ui.security.applyAppLockWindowPolicy
import dev.kortex.app.ui.onboarding.AuthGate
import dev.kortex.app.ui.onboarding.SplashHandoff
import dev.kortex.links.ui.linkDomain
import dev.kortex.sync.CloudAccount
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

// FragmentActivity: the app-lock BiometricPrompt attaches to a FragmentManager.
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var appLock: AppLock
    @Inject lateinit var cloudAccount: CloudAccount

    /** Latest request from a notification, shortcut or share; RootScreen clears it once handled. */
    private var entryRequest by mutableStateOf<EntryRequest?>(null)

    /** Set when the splash is ready to leave and the welcome intro should take over its icon. */
    private var splashHandoff by mutableStateOf<SplashHandoff?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
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
        applyAppLockWindowPolicy(appLock)
        // Only a fresh, unlocked, signed-out launch shows the welcome intro; it must be on screen to
        // take the splash over, and the lock screen would hide it.
        val playIntro = savedInstanceState == null && cloudAccount.user.value == null && !appLock.locked.value
        splash.setOnExitAnimationListener { provider ->
            if (playIntro) handSplashToIntro(provider) else fadeOutSplash(provider)
        }
        setContent {
            KortexTheme {
                // Every entry point (launcher, notification, shortcut, share) lands behind the lock.
                AppLockGate(appLock) {
                    AuthGate(cloudAccount, playIntro, splashHandoff = { splashHandoff }) {
                        RootScreen(
                            entryRequest = entryRequest,
                            onEntryRequestHandled = { entryRequest = null },
                        )
                    }
                }
            }
        }
    }

    private fun handSplashToIntro(provider: SplashScreenViewProvider) {
        val icon = provider.iconView
        val at = IntArray(2).also(icon::getLocationInWindow)
        val bounds = Rect(
            at[0].toFloat(),
            at[1].toFloat(),
            (at[0] + icon.width).toFloat(),
            (at[1] + icon.height).toFloat(),
        )
        val handoff = SplashHandoff(bounds, provider::remove)
        splashHandoff = handoff
        // If the intro never takes it, don't leave the splash covering the app.
        provider.view.postDelayed(handoff::release, SPLASH_HANDOFF_TIMEOUT_MS)
    }

    private fun fadeOutSplash(provider: SplashScreenViewProvider) {
        provider.iconView.animate()
            .scaleX(0.9f)
            .scaleY(0.9f)
            .setDuration(SPLASH_EXIT_MS)
            .setInterpolator(AccelerateInterpolator())
            .start()
        provider.view.animate()
            .alpha(0f)
            .setDuration(SPLASH_EXIT_MS)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction(provider::remove)
            .start()
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

        private const val SPLASH_EXIT_MS = 220L
        private const val SPLASH_HANDOFF_TIMEOUT_MS = 1_000L
    }
}
