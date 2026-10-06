package dev.kortex.app.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void
import dev.kortex.finance.R as FinanceR
import dev.kortex.finance.sms.BankSms
import dev.kortex.finance.sms.BankSmsNotifications
import dev.kortex.finance.ui.common.FinanceDatePicker
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Tools & Settings › FINANCES (docs/SMS_AUTO_PLAN.md). Turning it on asks for the SMS permission,
 * and notifications with it; turned down for good, the row sends the user to the app's Android
 * settings. If the permission is taken away later, the row says nothing is being read. The SMS
 * inbox row shows what's waiting and clears what's kept.
 */
@Composable
internal fun BankSmsSection(vm: BankSmsViewModel = hiltViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val toReview by vm.toReview.collectAsStateWithLifecycle()
    val kept by vm.kept.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val importing by remember(context) { BankSms.observeImporting(context) }.collectAsStateWithLifecycle(false)
    var confirmClear by remember { mutableStateOf(false) }
    // Turning on: where to start reading (new SMS only, or earlier ones too).
    var choosingStart by remember { mutableStateOf(false) }
    // Add earlier SMS, once it's on: just the day.
    var pickingEarlier by remember { mutableStateOf(false) }
    // The day earlier SMS are read from, held while the permissions are asked.
    var importFrom by remember { mutableStateOf<LocalDate?>(null) }
    var readDeclined by remember { mutableStateOf(false) }

    var granted by remember { mutableStateOf(hasPermission(context, Manifest.permission.RECEIVE_SMS)) }
    // Asked and turned down: Android won't show the dialog again, so point to its settings.
    var declined by remember { mutableStateOf(false) }

    // The permission may have been granted or taken away in Android settings meanwhile.
    LifecycleResumeEffect(Unit) {
        granted = hasPermission(context, Manifest.permission.RECEIVE_SMS)
        onPauseOrDispose {}
    }

    fun startImport(from: LocalDate) {
        readDeclined = false
        BankSms.importSince(context, from.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    // Notifications ride along: saved entries and SMS to review are told through them. Turning
    // that one down only hides them; the entries are still added. READ_SMS only when earlier SMS
    // were asked for.
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        fun ok(permission: String) = results[permission] ?: hasPermission(context, permission)
        val receive = ok(Manifest.permission.RECEIVE_SMS)
        granted = receive
        declined = !receive
        if (receive) vm.setEnabled(true)
        val from = importFrom
        importFrom = null
        if (from != null && (receive || settings.enabled)) {
            if (ok(Manifest.permission.READ_SMS)) startImport(from) else readDeclined = true
        }
    }

    /** Asks for [permissions]; Android only shows the ones not granted yet, and the callback carries on. */
    fun ask(permissions: List<String>) = askPermission.launch(permissions.toTypedArray())

    fun turnOn(from: LocalDate?) {
        importFrom = from
        ask(permissionsToAsk(readEarlier = from != null))
    }

    fun addEarlier(from: LocalDate) {
        importFrom = from
        ask(listOf(Manifest.permission.READ_SMS))
    }

    fun onToggle(turnOn: Boolean) {
        if (turnOn) choosingStart = true else vm.setEnabled(false)
    }

    val needsPermission = !granted && (settings.enabled || declined)

    Column {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Panel,
            border = BorderStroke(1.dp, Edge),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(36.dp).background(SynapseDim, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(FinanceR.drawable.ic_fin_sms),
                            contentDescription = null,
                            tint = Synapse,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Add bank SMS automatically", style = RowTitle, color = Ink)
                        Text(
                            when {
                                needsPermission && settings.enabled -> "SMS permission is off, so nothing is being read"
                                needsPermission -> "Kortex needs permission to receive SMS"
                                else -> "Turns payment alerts from your bank into entries"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (needsPermission) Amber else Muted,
                        )
                        if (needsPermission) {
                            Text(
                                "Allow in phone settings →",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                color = Synapse,
                                modifier = Modifier.padding(top = 2.dp).clickable { openAppSettings(context) },
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(checked = settings.enabled, onCheckedChange = ::onToggle, colors = switchColors())
                }

                AnimatedVisibility(visible = settings.enabled, enter = expandVertically(), exit = shrinkVertically()) {
                    Column {
                        HorizontalDivider(color = Edge)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Save clear ones without asking", style = RowTitle, color = Ink)
                                Text(
                                    "Off, every bank SMS waits for you to check it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Muted,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Switch(checked = settings.autoSave, onCheckedChange = vm::setAutoSave, colors = switchColors())
                        }
                        HorizontalDivider(color = Edge)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !importing) { pickingEarlier = true }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Add earlier SMS", style = RowTitle, color = Ink)
                                Text(
                                    when {
                                        importing -> "Adding earlier SMS…"
                                        readDeclined -> "Permission to read SMS was declined"
                                        else -> "Read bank SMS that arrived before, from a day you pick"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (readDeclined && !importing) Amber else Muted,
                                )
                            }
                            if (importing) {
                                Spacer(Modifier.width(12.dp))
                                CircularProgressIndicator(Modifier.size(18.dp), color = Synapse, strokeWidth = 2.dp)
                            }
                        }
                    }
                }

                // Shown even with the feature off: what was kept stays until it's cleared.
                AnimatedVisibility(visible = kept > 0, enter = expandVertically(), exit = shrinkVertically()) {
                    Column {
                        HorizontalDivider(color = Edge)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("SMS inbox", style = RowTitle, color = Ink)
                                Text(
                                    when (toReview) {
                                        0 -> "Nothing waiting for review"
                                        1 -> "1 waiting for review · Finances › Dashboard"
                                        else -> "$toReview waiting for review · Finances › Dashboard"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (toReview > 0) Amber else Muted,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            TextButton(onClick = { confirmClear = true }) { Text("Clear", color = Synapse) }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = settings.enabled) {
            Text(
                "Only SMS from bank senders are read; messages from people are never kept. Payments " +
                    "Kortex can read on the phone stay on it. A bank SMS it can't read is sent to your " +
                    "AI model with long numbers masked.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    if (choosingStart) {
        StartFromDialog(
            onDismiss = { choosingStart = false },
            onConfirm = { from ->
                choosingStart = false
                turnOn(from)
            },
        )
    }

    if (pickingEarlier) {
        FinanceDatePicker(
            date = LocalDate.now().minusDays(DEFAULT_DAYS_BACK),
            earliest = LocalDate.now().minusDays(MAX_DAYS_BACK),
            latest = LocalDate.now(),
            onPick = { day ->
                pickingEarlier = false
                addEarlier(day)
            },
            onDismiss = { pickingEarlier = false },
        )
    }

    if (confirmClear) {
        ClearInboxDialog(
            toReview = toReview,
            onDismiss = { confirmClear = false },
            onClear = {
                confirmClear = false
                vm.clearInbox()
                BankSmsNotifications.cancelReview(context)
            },
        )
    }
}

@Composable
private fun ClearInboxDialog(toReview: Int, onDismiss: () -> Unit, onClear: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Clear SMS inbox?") },
        text = {
            Text(
                buildString {
                    append("Kortex forgets the bank SMS it has kept")
                    if (toReview > 0) append(", including ${if (toReview == 1) "the one" else "the $toReview"} waiting for review")
                    append(". Entries already added stay.")
                },
                color = Muted,
            )
        },
        confirmButton = { TextButton(onClick = onClear) { Text("Clear", color = Amber) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Ink) } },
    )
}

/** How far back earlier SMS can be read, and where the date starts. */
private const val MAX_DAYS_BACK = 365L
private const val DEFAULT_DAYS_BACK = 30L

/**
 * Turning it on: read only SMS from now on, or SMS that already arrived since a day too. [onConfirm]
 * gets that day, or null for new SMS only.
 */
@Composable
private fun StartFromDialog(onDismiss: () -> Unit, onConfirm: (LocalDate?) -> Unit) {
    var earlier by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf(LocalDate.now().minusDays(DEFAULT_DAYS_BACK)) }
    var picking by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { Text("Start reading from") },
        text = {
            Column {
                StartOption("New SMS only", "From now on", selected = !earlier, onClick = { earlier = false })
                StartOption(
                    "Earlier SMS too",
                    "Since ${from.format(DayFormat)} · tap to change",
                    selected = earlier,
                    onClick = {
                        if (earlier) picking = true else earlier = true
                    },
                )
                AnimatedVisibility(visible = earlier) {
                    Text(
                        "Kortex also reads the bank SMS already on your phone since that day. Ones from before " +
                            "you added an account wait for review, since its opening balance already counts them.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(from.takeIf { earlier }) }) { Text("Turn on", color = Synapse) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Ink) } },
    )
    if (picking) {
        FinanceDatePicker(
            date = from,
            earliest = LocalDate.now().minusDays(MAX_DAYS_BACK),
            latest = LocalDate.now(),
            onPick = {
                from = it
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun StartOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = Synapse))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(title, style = RowTitle, color = Ink)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

private fun permissionsToAsk(readEarlier: Boolean): List<String> = buildList {
    add(Manifest.permission.RECEIVE_SMS)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    if (readEarlier) add(Manifest.permission.READ_SMS)
}

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

private val RowTitle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium)

@Composable
private fun switchColors() = SwitchDefaults.colors(
    checkedTrackColor = Synapse,
    checkedThumbColor = Void,
    uncheckedTrackColor = Edge,
    uncheckedThumbColor = Muted,
    uncheckedBorderColor = Color.Transparent,
)
