package dev.kortex.app.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withResumed
import dev.kortex.app.domain.security.AppLockSettings
import dev.kortex.app.domain.security.LockAfter
import dev.kortex.app.ui.security.AuthResult
import dev.kortex.app.ui.security.BiometricAvailability
import dev.kortex.app.ui.security.Biometrics
import dev.kortex.app.ui.security.findFragmentActivity
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.R
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void

/**
 * Tools & Settings › PRIVACY & SECURITY. Turning app lock on or off needs a biometric check;
 * with nothing registered on the phone the user is sent to Android Settings to add one, and on
 * return the check runs again.
 */
@Composable
internal fun AppLockSection(
    settings: AppLockSettings,
    onEnabledChange: (Boolean) -> Unit,
    onLockAfterChange: (LockAfter) -> Unit,
    onHideInRecentsChange: (Boolean) -> Unit,
    onAuthInProgress: (Boolean) -> Unit,
    onMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var availability by remember { mutableStateOf(Biometrics.availability(context)) }
    var showSetupDialog by remember { mutableStateOf(false) }
    // The user closed the set-up dialog; the row then explains why the switch stayed off.
    var setupDeclined by remember { mutableStateOf(false) }
    var confirmOnReturn by remember { mutableStateOf(false) }
    var pendingTurnOn by remember { mutableStateOf(false) }

    // Something may have been registered (or removed) in Android Settings while we were away.
    LifecycleResumeEffect(Unit) {
        availability = Biometrics.availability(context)
        onPauseOrDispose {}
    }

    fun confirm(turnOn: Boolean) {
        val act = activity ?: return
        if (!turnOn && Biometrics.deviceUnsecured(act)) {
            // Nothing left on the phone to check against; don't trap the user with the lock on.
            onEnabledChange(false)
            onMessage("App lock is off")
            return
        }
        onAuthInProgress(true)
        Biometrics.authenticate(
            activity = act,
            title = if (turnOn) "Turn on app lock" else "Turn off app lock",
            subtitle = if (turnOn) "Confirm it’s you to keep your chats and saved info private" else "Confirm it’s you",
            // If the fingerprint or face was removed since, the phone PIN can still turn it off.
            allowDeviceCredential = !turnOn && availability != BiometricAvailability.AVAILABLE,
        ) { result ->
            onAuthInProgress(false)
            when (result) {
                AuthResult.Success -> {
                    onEnabledChange(turnOn)
                    onMessage(if (turnOn) "App lock is on" else "App lock is off")
                }
                is AuthResult.Dismissed -> result.message?.let(onMessage)
            }
        }
    }

    // Back from Android Settings: prompt again once the screen is showing, if something was added.
    LaunchedEffect(pendingTurnOn) {
        if (pendingTurnOn) lifecycle.withResumed {
            pendingTurnOn = false
            confirm(turnOn = true)
        }
    }

    val enrollLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        availability = Biometrics.availability(context)
        if (confirmOnReturn && availability == BiometricAvailability.AVAILABLE) {
            setupDeclined = false
            pendingTurnOn = true
        }
        confirmOnReturn = false
    }

    fun openEnrollment() {
        confirmOnReturn = true
        try {
            enrollLauncher.launch(Biometrics.enrollIntent())
        } catch (e: ActivityNotFoundException) {
            // Some phones don't handle the biometric-enroll action.
            enrollLauncher.launch(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
    }

    fun onToggle(turnOn: Boolean) {
        if (!turnOn) return confirm(turnOn = false)
        when (availability) {
            BiometricAvailability.AVAILABLE -> confirm(turnOn = true)
            BiometricAvailability.NONE_ENROLLED -> showSetupDialog = true
            BiometricAvailability.UNSUPPORTED -> Unit
        }
    }

    val unsupported = availability == BiometricAvailability.UNSUPPORTED && !settings.enabled
    val noneEnrolled = availability == BiometricAvailability.NONE_ENROLLED && setupDeclined && !settings.enabled

    Column {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Panel,
            border = BorderStroke(1.dp, Edge),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (unsupported) 0.5f else 1f)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(36.dp).background(SynapseDim, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_lock),
                            contentDescription = null,
                            tint = Synapse,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("App lock", style = RowTitle, color = Ink)
                        Text(
                            when {
                                unsupported -> "This phone doesn’t support biometric unlock"
                                noneEnrolled -> "No fingerprint or face set up on this phone"
                                else -> "Require your fingerprint or face to open Kortex"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (noneEnrolled) Amber else Muted,
                        )
                        if (noneEnrolled) {
                            Text(
                                "Set up in phone settings →",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = Synapse,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .clickable(onClick = ::openEnrollment),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = settings.enabled,
                        onCheckedChange = ::onToggle,
                        enabled = !unsupported,
                        colors = lockSwitchColors(),
                    )
                }

                AnimatedVisibility(
                    visible = settings.enabled,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    Column {
                        HorizontalDivider(color = Edge)
                        LockAfterPicker(settings.lockAfter, onLockAfterChange)
                        HorizontalDivider(color = Edge)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Hide in recent apps", style = RowTitle, color = Ink)
                                Text(
                                    "Blanks the app preview and blocks screenshots",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(
                                checked = settings.hideInRecents,
                                onCheckedChange = onHideInRecentsChange,
                                colors = lockSwitchColors(),
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = settings.enabled) {
            Text(
                "Uses the fingerprint or face unlock already registered on this phone. " +
                    "Kortex never sees or stores biometric data.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    if (showSetupDialog) {
        NoBiometricsDialog(
            onDismiss = {
                showSetupDialog = false
                setupDeclined = true
            },
            onOpenSettings = {
                showSetupDialog = false
                setupDeclined = true
                openEnrollment()
            },
        )
    }
}

private val RowTitle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LockAfterPicker(selected: LockAfter, onSelect: (LockAfter) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(
            "Lock after",
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        )
        Spacer(Modifier.size(10.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LockAfter.entries.forEach { option ->
                val isSelected = option == selected
                Surface(
                    shape = CircleShape,
                    color = if (isSelected) SynapseDim else Color.Transparent,
                    border = if (isSelected) null else BorderStroke(1.dp, Edge),
                    onClick = { onSelect(option) },
                ) {
                    Text(
                        option.label,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                        ),
                        color = if (isSelected) Synapse else Muted,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NoBiometricsDialog(onDismiss: () -> Unit, onOpenSettings: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        icon = {
            Box(
                Modifier.size(48.dp).background(Amber.copy(alpha = 0.16f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painterResource(R.drawable.ic_fingerprint),
                    contentDescription = null,
                    tint = Amber,
                    modifier = Modifier.size(26.dp),
                )
            }
        },
        title = { Text("Set up fingerprint or face unlock", textAlign = TextAlign.Center) },
        text = {
            Text(
                "No fingerprint or face is registered on this phone yet. Add one in your phone’s " +
                    "settings, then come back to turn on app lock.",
                color = Muted,
            )
        },
        confirmButton = {
            TextButton(onClick = onOpenSettings) { Text("Open settings", color = Synapse) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Ink) }
        },
    )
}

@Composable
private fun lockSwitchColors() = SwitchDefaults.colors(
    checkedTrackColor = Synapse,
    checkedThumbColor = Void,
    uncheckedTrackColor = Edge,
    uncheckedThumbColor = Muted,
    uncheckedBorderColor = Color.Transparent,
)
