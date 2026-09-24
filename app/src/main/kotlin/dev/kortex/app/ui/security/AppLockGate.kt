package dev.kortex.app.ui.security

import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import dev.kortex.app.domain.security.AppLock
import dev.kortex.design.Amber
import dev.kortex.design.Ink
import dev.kortex.design.Muted
import dev.kortex.design.R
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void
import kotlinx.coroutines.launch

/**
 * Draws [content] only while the app is unlocked. On a cold start nothing behind the lock is
 * composed until the first unlock; when it re-locks later, the content stays composed (so
 * navigation and in-flight picker results survive) but is hidden and unreachable.
 */
@Composable
fun AppLockGate(appLock: AppLock, content: @Composable () -> Unit) {
    val locked by appLock.locked.collectAsStateWithLifecycle()
    var everUnlocked by rememberSaveable { mutableStateOf(!locked) }
    LaunchedEffect(locked) { if (!locked) everUnlocked = true }

    Box(Modifier.fillMaxSize()) {
        if (everUnlocked) {
            Box(
                if (locked) Modifier.fillMaxSize().alpha(0f).clearAndSetSemantics {} else Modifier.fillMaxSize(),
            ) { content() }
        }
        if (locked) LockScreen(appLock)
    }
}

/** Keeps FLAG_SECURE in step with "Hide in recent apps": blank recents preview, no screenshots. */
fun ComponentActivity.applyAppLockWindowPolicy(appLock: AppLock) {
    lifecycleScope.launch {
        appLock.settings.collect { settings ->
            if (settings.enabled && settings.hideInRecents) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

@Composable
private fun LockScreen(appLock: AppLock) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val promptRequests by appLock.promptRequests.collectAsStateWithLifecycle()
    var prompting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun prompt() {
        if (prompting || activity == null) return
        // With no screen lock left on the phone nothing could ever unlock Kortex; don't strand the user.
        if (Biometrics.deviceUnsecured(activity)) {
            appLock.unlock()
            return
        }
        prompting = true
        message = null
        appLock.authInProgress = true
        Biometrics.authenticate(
            activity = activity,
            title = "Unlock Kortex",
            subtitle = "Use your fingerprint or face",
            allowDeviceCredential = true,
        ) { result ->
            appLock.authInProgress = false
            prompting = false
            when (result) {
                AuthResult.Success -> appLock.unlock()
                is AuthResult.Dismissed -> message = result.message
            }
        }
    }

    // The prompt opens by itself on a cold start and on every return while locked.
    LaunchedEffect(promptRequests) { lifecycle.withResumed { prompt() } }

    // Back must not reach the hidden screens underneath.
    BackHandler { activity?.moveTaskToBack(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Void)
            // Swallow touches so nothing underneath can be reached.
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
            .systemBarsPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.fillMaxHeight(0.23f))
        Box(
            Modifier.size(72.dp).background(SynapseDim, RoundedCornerShape(18.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("K", color = Synapse, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(20.dp))
        Text("Kortex is locked", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Your chats and saved info stay private.",
            color = Muted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        if (!prompting) {
            message?.let {
                Text(it, color = Amber, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
            }
            Button(
                onClick = ::prompt,
                shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SynapseDim, contentColor = Synapse),
            ) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_fingerprint), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Unlock", fontWeight = FontWeight.Medium)
                }
            }
        }
        Spacer(Modifier.fillMaxHeight(0.25f))
    }
}
