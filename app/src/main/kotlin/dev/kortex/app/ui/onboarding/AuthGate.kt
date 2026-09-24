package dev.kortex.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Void
import dev.kortex.design.anim.EmphasizedAccelerate
import dev.kortex.design.anim.EmphasizedDecelerate
import dev.kortex.sync.CloudAccount

/**
 * Kortex needs a signed-in account: with nobody signed in, only onboarding is drawn. After a sign-in
 * the flow stays up until "Start using Kortex", then [content] scales in behind it.
 *
 * [playIntro] is true on a cold start that should hand the splash icon to the welcome screen;
 * [splashHandoff] delivers it once the splash is ready to leave.
 */
@Composable
fun AuthGate(
    account: CloudAccount,
    playIntro: Boolean,
    splashHandoff: () -> SplashHandoff?,
    content: @Composable () -> Unit,
) {
    val user by account.user.collectAsStateWithLifecycle()
    var onboarding by rememberSaveable { mutableStateOf(user == null) }
    // Onboarding reached from the app rather than a launch means the user just logged out.
    var signedOut by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(user) {
        if (user == null && !onboarding) {
            signedOut = true
            onboarding = true
        }
    }
    // The splash belongs to this launch only; returning here after a sign-out starts plainly.
    var introPending by remember { mutableStateOf(playIntro) }

    AnimatedContent(
        targetState = onboarding,
        transitionSpec = {
            if (targetState) {
                fadeIn(tween(300, delayMillis = 90, easing = EmphasizedDecelerate)) togetherWith
                    fadeOut(tween(90, easing = EmphasizedAccelerate))
            } else {
                (fadeIn(tween(420, delayMillis = 120, easing = EmphasizedDecelerate)) +
                    scaleIn(tween(520, delayMillis = 120, easing = EmphasizedDecelerate), initialScale = 0.94f)) togetherWith
                    (fadeOut(tween(200, easing = EmphasizedAccelerate)) +
                        scaleOut(tween(200, easing = EmphasizedAccelerate), targetScale = 1.04f))
            }
        },
        modifier = Modifier.fillMaxSize().background(Void),
        label = "auth-gate",
    ) { showOnboarding ->
        if (showOnboarding) {
            OnboardingFlow(
                splashHandoff = splashHandoff.takeIf { introPending },
                signedOut = signedOut,
                onFinished = {
                    introPending = false
                    signedOut = false
                    onboarding = false
                },
            )
        } else {
            content()
        }
    }
}
