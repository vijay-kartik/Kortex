package dev.kortex.myinfo.topics.ui.email

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.KortexTheme
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.mvi.ObserveEffects
import dev.kortex.mvi.ScopedViewModelStore
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.SavedEmail
import dev.kortex.myinfo.topics.domain.port.EmailAttachment
import dev.kortex.myinfo.topics.domain.port.EmailMessage
import dev.kortex.myinfo.topics.ui.common.BodyStyle
import dev.kortex.myinfo.topics.ui.common.ButtonStyle
import dev.kortex.myinfo.topics.ui.common.ChipStyle
import dev.kortex.myinfo.topics.ui.common.HeroTitleStyle
import dev.kortex.myinfo.topics.ui.common.MetaStyle
import dev.kortex.myinfo.topics.ui.common.RowShape
import dev.kortex.myinfo.topics.ui.common.TypeBadge
import dev.kortex.myinfo.topics.ui.common.formatDateTime
import dev.kortex.myinfo.topics.ui.common.formatSize
import dev.kortex.myinfo.topics.ui.common.openFile
import kotlinx.coroutines.launch

/**
 * A kept email, read in full inside the app. [onClose] goes back to the topic. Each email gets
 * its own ViewModel, so reopening one reads it afresh.
 */
@Composable
fun EmailReaderRoute(
    email: SavedEmail,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScopedViewModelStore(key = "email-${email.messageId}") {
        val viewModel = hiltViewModel<EmailReaderViewModel, EmailReaderViewModel.Factory>(
            creationCallback = { factory -> factory.create(email) },
        )
        val context = LocalContext.current
        val state by viewModel.state.collectAsStateWithLifecycle()
        val snackbars = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        ObserveEffects(viewModel.effects) { effect ->
            when (effect) {
                EmailReaderEffect.OpenMailApp -> if (!context.openGmail()) {
                    scope.launch { snackbars.showSnackbar("Gmail isn't installed on this phone.") }
                }
                is EmailReaderEffect.OpenFile -> if (!context.openFile(effect.file)) {
                    scope.launch { snackbars.showSnackbar("No app on this phone opens that file.") }
                }
                // Launched, so a snackbar on screen doesn't hold up the effects behind it.
                is EmailReaderEffect.ShowMessage -> scope.launch { snackbars.showSnackbar(effect.text) }
            }
        }
        BackHandler(onBack = onClose)
        EmailReaderScreen(state, viewModel::onIntent, onBack = onClose, snackbars = snackbars, modifier = modifier)
    }
}

@Composable
fun EmailReaderScreen(
    state: EmailReaderState,
    onIntent: (EmailReaderIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
) {
    val message = state.message
    // HTML is shown as laid out whenever the mail has it, even alongside a plain-text part.
    val html = message?.bodyHtml?.takeIf { it.isNotBlank() }
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = { ReaderTopBar(onBack = onBack, onOpenMailApp = { onIntent(EmailReaderIntent.OpenMailApp) }) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            when {
                // A web page scrolls itself, so the header sits above it rather than scrolling away with it.
                html != null -> {
                    Header(state, message, onIntent, Modifier.padding(horizontal = 20.dp))
                    HtmlBody(html, Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp))
                }
                else -> Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                ) {
                    Header(state, message, onIntent)
                    Body(state, onIntent)
                }
            }
        }
    }
}

@Composable
private fun ReaderTopBar(onBack: () -> Unit, onOpenMailApp: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        BarButton("‹", "Back", onBack, Modifier.align(Alignment.CenterStart), glyph = true)
        Text(
            "EMAIL",
            style = MetaStyle.copy(letterSpacing = 1.2.sp),
            color = Muted,
            modifier = Modifier.align(Alignment.Center),
        )
        BarButton("Open Gmail", "Open Gmail", onOpenMailApp, Modifier.align(Alignment.CenterEnd))
    }
}

/** Subject, sender, recipients and date: what's kept shows at once, the rest once the mail is read. */
@Composable
private fun Header(
    state: EmailReaderState,
    message: EmailMessage?,
    onIntent: (EmailReaderIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(top = 8.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TypeBadge(ItemType.Email)
            SelectionContainer {
                Text(state.subject, style = HeroTitleStyle, color = Ink, modifier = Modifier.semantics { heading() })
            }
        }
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HeaderLine("FROM", state.from)
                message?.to?.takeIf { it.isNotBlank() }?.let { HeaderLine("TO", it) }
                message?.cc?.takeIf { it.isNotBlank() }?.let { HeaderLine("CC", it) }
                state.sentAtMillis?.let { HeaderLine("SENT", formatDateTime(it)) }
            }
        }
        message?.attachments?.takeIf { it.isNotEmpty() }?.let { attachments ->
            Attachments(attachments, opening = state.opening, onOpen = { onIntent(EmailReaderIntent.OpenAttachment(it)) })
        }
        HorizontalDivider(color = Edge)
    }
}

@Composable
private fun HeaderLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MetaStyle, color = Muted, modifier = Modifier.padding(top = 3.dp))
        Text(value, style = BodyStyle, color = InkSoft)
    }
}

/**
 * What's attached, one row each. A tap downloads the file and opens it in whichever app handles
 * its type; the row says so while it downloads, and the others wait their turn.
 */
@Composable
private fun Attachments(attachments: List<EmailAttachment>, opening: String?, onOpen: (EmailAttachment) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("${attachments.size} ATTACHED", style = MetaStyle, color = Muted)
        attachments.forEach { attachment ->
            val busy = attachment.id == opening
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RowShape)
                    .background(Panel)
                    .clickable(enabled = opening == null, role = Role.Button, onClickLabel = "Open") { onOpen(attachment) }
                    .minimumInteractiveComponentSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(attachment.name, style = ChipStyle, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (busy) {
                    CircularProgressIndicator(color = Synapse, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                }
                Text(
                    if (busy) "OPENING" else formatSize(attachment.sizeBytes),
                    style = MetaStyle,
                    color = if (busy) Synapse else Muted,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/** The plain-text body of a mail without HTML, or what stands in for it: loading, a problem, or an empty mail. */
@Composable
private fun Body(state: EmailReaderState, onIntent: (EmailReaderIntent) -> Unit) {
    val message = state.message
    when {
        state.loading -> Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Synapse, strokeWidth = 2.dp)
        }
        state.problem != null -> Problem(state, onIntent)
        message != null && message.bodyText.isNotBlank() -> SelectionContainer {
            Text(message.bodyText.trim(), style = BodyStyle.copy(fontSize = 15.sp, lineHeight = 23.sp), color = Ink, modifier = Modifier.padding(bottom = 24.dp))
        }
        else -> Text(
            state.email.snippet.ifBlank { "This email has no text." },
            style = BodyStyle,
            color = Muted,
            modifier = Modifier.padding(bottom = 24.dp),
        )
    }
}

@Composable
private fun Problem(state: EmailReaderState, onIntent: (EmailReaderIntent) -> Unit) {
    val text = when (val problem = state.problem) {
        EmailProblem.NotConnected -> "Connect your Gmail account in Settings to read this email here."
        EmailProblem.Gone -> "This email is no longer in your mailbox."
        is EmailProblem.Failed -> problem.message
        null -> return
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // What was kept is still worth showing when the rest can't be had.
        state.email.snippet.takeIf { it.isNotBlank() }?.let {
            Text(it, style = BodyStyle, color = InkSoft)
        }
        Text(text, style = BodyStyle, color = Muted, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (state.canRetry) {
            BarButton("Try again", "Try again", onClick = { onIntent(EmailReaderIntent.Retry) })
        }
    }
}

/**
 * A mail's HTML body, as the sender laid it out, images and all: those carried in the mail arrive
 * already inlined, and the rest load from the web as they would in Gmail. Nothing in it runs — no
 * scripts, no access to the phone's files — and a tapped link leaves for whichever app handles it.
 */
@Composable
private fun HtmlBody(html: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
        factory = { context ->
            WebView(context).apply {
                // Mail is laid out for paper-white; a dark page behind it would show through.
                setBackgroundColor(AndroidColor.WHITE)
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                // The page has no origin of its own, so an http image is no downgrade from it.
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        view.context.openLink(request.url)
                        return true
                    }
                }
                loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

@Composable
private fun BarButton(text: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, glyph: Boolean = false) {
    Text(
        text,
        style = if (glyph) ButtonStyle.copy(fontSize = 20.sp) else ButtonStyle.copy(fontSize = 14.sp),
        color = if (glyph) Muted else Synapse,
        textAlign = TextAlign.Center,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .semantics { contentDescription = label }
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** Gmail's own front door: the inbox, since it won't be sent to one message from outside. */
private fun Context.openGmail(): Boolean {
    val launch = packageManager.getLaunchIntentForPackage(GMAIL_PACKAGE) ?: return false
    return try {
        startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

private fun Context.openLink(uri: Uri) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        // Nothing on the phone handles that kind of link; the tap does nothing.
    }
}

private const val GMAIL_PACKAGE = "com.google.android.gm"

// ── Previews ──────────────────────────────────────────────────────

private val previewEmail = SavedEmail(
    messageId = "18c2a3f",
    threadId = null,
    subject = "Your visa appointment is confirmed",
    from = "Visa Centre <noreply@visa.example>",
    snippet = "Confirmed for 14 March at 09:40.",
    sentAtMillis = 1_741_000_000_000,
    rfc822MessageId = "abc@visa.example",
    accountEmail = "me@example.com",
)

@Preview
@Composable
private fun EmailReaderPreview() {
    KortexTheme {
        EmailReaderScreen(
            state = EmailReaderState(
                email = previewEmail,
                loading = false,
                message = EmailMessage(
                    subject = previewEmail.subject,
                    from = previewEmail.from,
                    to = "me@example.com",
                    cc = "",
                    sentAtMillis = previewEmail.sentAtMillis,
                    bodyText = "Hello,\n\nYour appointment is confirmed for 14 March at 09:40.\n\nBring your passport and two photos.",
                    bodyHtml = null,
                    attachments = listOf(EmailAttachment("a1", "appointment.pdf", "application/pdf", 48_213)),
                ),
            ),
            onIntent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun EmailReaderProblemPreview() {
    KortexTheme {
        EmailReaderScreen(
            state = EmailReaderState(
                email = previewEmail,
                loading = false,
                problem = EmailProblem.Failed("The Gmail sign-in has expired. Reconnect the account in Settings."),
            ),
            onIntent = {},
            onBack = {},
        )
    }
}
