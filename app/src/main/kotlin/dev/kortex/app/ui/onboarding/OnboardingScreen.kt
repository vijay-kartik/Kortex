package dev.kortex.app.ui.onboarding

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kortex.app.R
import dev.kortex.app.ui.security.findFragmentActivity
import dev.kortex.design.Amber
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.KortexMark
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void
import dev.kortex.design.anim.EmphasizedAccelerate
import dev.kortex.design.anim.EmphasizedDecelerate
import dev.kortex.design.drawKortexMark
import dev.kortex.sync.CloudUser
import dev.kortex.sync.OtherAccountData
import dev.kortex.sync.SyncProgress
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import dev.kortex.design.R as DesignR

// Welcome intro, from the moment the splash is released. The mark fires in place, glides into the
// header while it settles, and the copy follows it in.
private const val HANDOFF_TIMEOUT_MS = 800L
private const val FIRE_MS = 560
private const val TRAVEL_DELAY_MS = 260L
private const val TRAVEL_MS = 720
private const val REVEAL_DELAY_MS = 560L
private const val REVEAL_MS = 900

// Staggered entrances: item i starts at i × STEP of the reveal and takes SPAN of it.
private const val STAGGER_STEP = 0.11f
private const val STAGGER_SPAN = 0.5f

private val MarkSlot = 72.dp
private val TopGap = 96.dp

/**
 * First launch and signed-out onboarding: Welcome (sign in with Google) → [another account's links]
 * → [Restoring your library] → All set.
 * [splashHandoff] is non-null only when this launch should take over the splash icon; [signedOut]
 * greets someone who just logged out (Figma: Login & Logout 09).
 */
@Composable
fun OnboardingFlow(
    splashHandoff: (() -> SplashHandoff?)?,
    signedOut: Boolean,
    onFinished: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val activity = LocalContext.current.findFragmentActivity()

    AnimatedContent(
        targetState = ui.step,
        transitionSpec = { sharedAxisX() },
        modifier = Modifier.fillMaxSize().background(Void),
        label = "onboarding-step",
    ) { step ->
        when (step) {
            OnboardingStep.Welcome -> WelcomeScreen(
                signedOut = signedOut,
                signingIn = ui.signingIn,
                error = ui.error,
                splashHandoff = splashHandoff,
                onSignIn = { activity?.let(vm::signIn) },
            )
            OnboardingStep.OtherAccountLinks -> OtherAccountLinksScreen(
                user = ui.user,
                other = ui.otherAccount,
                resolving = ui.resolving,
                onKeep = vm::keepOtherAccountLinks,
                onRemove = vm::removeOtherAccountLinks,
            )
            OnboardingStep.Restoring -> RestoringScreen(user = ui.user, progress = ui.restore)
            OnboardingStep.AllSet -> AllSetScreen(user = ui.user, sync = ui.sync, onStart = onFinished)
        }
    }
}

/** Material shared-axis X: a short slide in the direction of travel, faded through. */
private fun AnimatedContentTransitionScope<OnboardingStep>.sharedAxisX(): ContentTransform {
    val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
    val shift = { width: Int -> (width * 0.08f).toInt() * direction }
    return (fadeIn(tween(210, delayMillis = 90, easing = EmphasizedDecelerate)) +
        slideInHorizontally(tween(300, easing = EmphasizedDecelerate)) { shift(it) }) togetherWith
        (fadeOut(tween(90, easing = EmphasizedAccelerate)) +
            slideOutHorizontally(tween(300, easing = EmphasizedAccelerate)) { -shift(it) })
}

// ── Welcome ─────────────────────────────────────────────────────────────

@Composable
private fun WelcomeScreen(
    signedOut: Boolean,
    signingIn: Boolean,
    error: String?,
    splashHandoff: (() -> SplashHandoff?)?,
    onSignIn: () -> Unit,
) {
    val intro = splashHandoff != null
    val fire = remember { Animatable(if (intro) 0f else 1f) }
    val travel = remember { Animatable(if (intro) 0f else 1f) }
    val reveal = remember { Animatable(if (intro) 0f else 1f) }
    // All in window pixels: where the splash icon was, where the mark ends up, and where this screen sits.
    var from by remember { mutableStateOf<Rect?>(null) }
    var slot by remember { mutableStateOf<Rect?>(null) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val landed by remember { derivedStateOf { travel.value >= 1f } }

    if (splashHandoff != null) {
        LaunchedEffect(Unit) {
            val handoff = withTimeoutOrNull(HANDOFF_TIMEOUT_MS) {
                snapshotFlow { splashHandoff() }.filterNotNull().first()
            }
            snapshotFlow { slot }.filterNotNull().first()
            from = handoff?.iconBounds
            // No splash to take over: the mark simply sits in the header and the copy still rises in.
            if (from == null) travel.snapTo(1f)
            // Let the overlay draw the mark over the splash icon before the splash goes.
            repeat(2) { withFrameNanos { } }
            handoff?.release()
            coroutineScope {
                launch { fire.animateTo(1f, tween(FIRE_MS, easing = LinearEasing)) }
                launch {
                    delay(TRAVEL_DELAY_MS)
                    travel.animateTo(1f, tween(TRAVEL_MS, easing = EmphasizedDecelerate))
                }
                launch {
                    delay(REVEAL_DELAY_MS)
                    reveal.animateTo(1f, tween(REVEAL_MS, easing = LinearEasing))
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        Column(
            modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(TopGap))
            Box(
                Modifier.size(MarkSlot).onGloballyPositioned { slot = it.boundsInWindow() },
                contentAlignment = Alignment.Center,
            ) {
                if (landed) KortexMark(Modifier.requiredSize(MarkSlot * 1.5f))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                if (signedOut) "You’re signed out" else "Welcome to Kortex",
                style = MaterialTheme.typography.headlineSmall,
                color = Ink,
                textAlign = TextAlign.Center,
                modifier = Modifier.staggered({ reveal.value }, 0).semantics { heading() },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (signedOut) {
                    "Sign in to keep using Kortex. Your links and topics are still on this phone."
                } else {
                    "Save links, build topics and ask your agent — all in one place."
                },
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                color = Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.staggered({ reveal.value }, 1),
            )
            Spacer(Modifier.height(48.dp))
            Benefit(
                icon = DesignR.drawable.ic_cloud,
                title = "Back up your library",
                body = "Links and topics are saved to your Google account, so a reinstall or new phone loses nothing.",
                modifier = Modifier.staggered({ reveal.value }, 2),
            )
            Spacer(Modifier.height(20.dp))
            Benefit(
                icon = DesignR.drawable.ic_shield,
                title = "Private by default",
                body = "Chats, settings and API keys never leave this phone.",
                modifier = Modifier.staggered({ reveal.value }, 3),
            )
            Spacer(Modifier.weight(1f))
            Column(
                Modifier.fillMaxWidth().staggered({ reveal.value }, 4, rise = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ErrorLine(error)
                PillButton(onClick = onSignIn, container = Ink, enabled = !signingIn) {
                    GoogleButtonContent(signingIn)
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        // The travelling mark: from the splash icon to the header slot, then it hands over to the slot.
        if (!landed) {
            Canvas(Modifier.fillMaxSize()) {
                val start = from ?: return@Canvas
                val target = slot ?: return@Canvas
                // The slot is the icon's visible 72 units; the mark's full 108-unit viewport is 1.5× that.
                val end = target.inflate(target.width / 4f)
                drawKortexMark(lerp(start, end, travel.value).translate(-origin), fire = fire.value)
            }
        }
    }
}

/** Keeps the last message while it animates out, so the line doesn't blank mid-collapse. */
@Composable
private fun ErrorLine(error: String?) {
    var shown by remember { mutableStateOf("") }
    if (error != null) shown = error
    AnimatedVisibility(
        visible = error != null,
        enter = expandVertically(tween(260, easing = EmphasizedDecelerate)) + fadeIn(tween(200, delayMillis = 60)),
        exit = shrinkVertically(tween(200, easing = EmphasizedAccelerate)) + fadeOut(tween(120)),
    ) {
        Text(
            shown,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = Amber,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        )
    }
}

@Composable
private fun GoogleButtonContent(signingIn: Boolean) {
    AnimatedContent(
        targetState = signingIn,
        transitionSpec = { fadeIn(tween(180, delayMillis = 60)) togetherWith fadeOut(tween(120)) },
        label = "google-button",
    ) { busy ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Void, strokeWidth = 2.dp)
            } else {
                Image(painterResource(R.drawable.ic_google_g), contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (busy) "Signing in…" else "Continue with Google",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                color = Void,
            )
        }
    }
}

@Composable
private fun Benefit(@DrawableRes icon: Int, title: String, body: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(36.dp).background(SynapseDim, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = null, tint = Synapse, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Ink)
            Text(body, style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp), color = Muted)
        }
    }
}

// ── Another account's links ─────────────────────────────────────────────

/** The links on this phone belong to the account signed in before; they go with this one or leave. */
@Composable
private fun OtherAccountLinksScreen(
    user: CloudUser?,
    other: OtherAccountData?,
    resolving: Boolean,
    onKeep: () -> Unit,
    onRemove: () -> Unit,
) {
    val what = listOfNotNull(
        other?.linkCount?.takeIf { it > 0 }?.let { if (it == 1) "1 link" else "$it links" },
        other?.topicCount?.takeIf { it > 0 }?.let { if (it == 1) "1 topic" else "$it topics" },
    ).joinToString(" and ")
    val previous = other?.ownerEmail ?: "another account"
    val current = user?.email ?: "this account"

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(TopGap))
        Box(Modifier.size(MarkSlot).background(SynapseDim, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(DesignR.drawable.ic_link), contentDescription = null, tint = Synapse, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "This phone has $what from $previous",
            style = MaterialTheme.typography.headlineSmall,
            color = Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Add them to $current, or remove them from this phone. Removing them doesn’t touch what $previous has in the cloud.",
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
            color = Muted,
            textAlign = TextAlign.Center,
        )
        val unpushed = other?.unpushedCount ?: 0
        if (unpushed > 0) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (unpushed == 1) "1 change was never synced and would be lost if removed."
                else "$unpushed changes were never synced and would be lost if removed.",
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = Amber,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.weight(1f))
        PillButton(onClick = onKeep, container = SynapseDim, enabled = !resolving) {
            Text(
                "Add to $current",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                color = Synapse,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(12.dp))
        PillButton(onClick = onRemove, container = Panel, enabled = !resolving) {
            Text(
                "Remove from this phone",
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                color = Ink,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ── Restoring your library ──────────────────────────────────────────────

/**
 * The account's library coming back after a sign-in (Figma: Login & Logout 03). Links pull first,
 * then topics with their items. Files aren't backed up, so the design's Files row isn't here.
 */
@Composable
private fun RestoringScreen(user: CloudUser?, progress: SyncProgress?) {
    val links = progress?.links ?: SyncProgress.Part(0, 0)
    val topics = progress?.topics ?: SyncProgress.Part(0, 0)
    val linksPulled = links.done >= links.total

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(TopGap))
        Box(Modifier.size(MarkSlot), contentAlignment = Alignment.Center) {
            KortexMark(Modifier.requiredSize(MarkSlot * 1.5f))
        }
        Spacer(Modifier.height(24.dp))
        Text(
            "Restoring your library",
            style = MaterialTheme.typography.headlineSmall,
            color = Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(10.dp))
        user?.email?.let { email ->
            Text(
                "Signed in as $email",
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
                color = Muted,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(40.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Panel, RoundedCornerShape(12.dp))
                .border(1.dp, Edge, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            RestoreRow("Links", links, started = true)
            RestoreRow("Topics", topics, started = linksPulled)
        }
        Spacer(Modifier.weight(1f))
        Text(
            "Keep Kortex open. This only happens the first time you sign in on a phone.",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Spacer(Modifier.height(48.dp))
    }
}

/**
 * One part of the restore: its count and bar once it has started, "Waiting" before, "None" when the
 * account has nothing of it.
 */
@Composable
private fun RestoreRow(label: String, part: SyncProgress.Part, started: Boolean) {
    val active = started && part.total > 0
    val fraction by animateFloatAsState(
        targetValue = if (active) part.done.toFloat() / part.total else 0f,
        animationSpec = tween(300, easing = EmphasizedDecelerate),
        label = "restore-$label",
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                color = if (active) Ink else Muted,
                modifier = Modifier.weight(1f),
            )
            Text(
                when {
                    part.total == 0 -> "None"
                    !started -> "Waiting"
                    else -> "${part.done} of ${part.total}"
                },
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = if (active) Synapse else Muted,
            )
        }
        Box(Modifier.fillMaxWidth().height(4.dp).background(Edge, RoundedCornerShape(2.dp))) {
            Box(Modifier.fillMaxWidth(fraction).height(4.dp).background(Synapse, RoundedCornerShape(2.dp)))
        }
    }
}

// ── All set ─────────────────────────────────────────────────────────────

@Composable
private fun AllSetScreen(user: CloudUser?, sync: SignInSync?, onStart: () -> Unit) {
    val badge = remember { Animatable(0f) }
    val check = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch {
            delay(120)
            badge.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
        }
        launch {
            delay(320)
            check.animateTo(1f, tween(380, easing = EmphasizedDecelerate))
        }
        launch {
            delay(260)
            reveal.animateTo(1f, tween(800, easing = LinearEasing))
        }
    }
    val firstName = user?.name?.substringBefore(' ')?.takeIf { it.isNotBlank() }

    Column(
        modifier = Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(TopGap))
        CheckBadge(scale = { badge.value }, drawn = { check.value })
        Spacer(Modifier.height(24.dp))
        Text(
            if (firstName != null) "You’re all set, $firstName" else "You’re all set",
            style = MaterialTheme.typography.headlineSmall,
            color = Ink,
            textAlign = TextAlign.Center,
            modifier = Modifier.staggered({ reveal.value }, 0).semantics { heading() },
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "Your library is backed up to your Google account.",
            style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.staggered({ reveal.value }, 1),
        )
        Spacer(Modifier.height(32.dp))
        SummaryCard(user, sync, Modifier.staggered({ reveal.value }, 2))
        if (sync is SignInSync.Failed) {
            Text(
                sync.message,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp, lineHeight = 18.sp),
                color = Amber,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).staggered({ reveal.value }, 2),
            )
        }
        Spacer(Modifier.weight(1f))
        Column(
            Modifier.fillMaxWidth().staggered({ reveal.value }, 3, rise = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PillButton(onClick = onStart, container = SynapseDim) {
                Text(
                    "Start using Kortex",
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
                    color = Synapse,
                )
            }
            Text(
                "Sync any time from Tools & Settings › Cloud sync.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = Muted,
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** A circle that springs in, then a check that draws itself. */
@Composable
private fun CheckBadge(scale: () -> Float, drawn: () -> Float) {
    Box(
        Modifier
            .size(MarkSlot)
            .graphicsLayer {
                val s = scale()
                scaleX = 0.5f + 0.5f * s
                scaleY = 0.5f + 0.5f * s
                alpha = s.coerceIn(0f, 1f)
            }
            .background(SynapseDim, CircleShape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // Material check in a 24-unit box, drawn at 36dp in the middle of the 72dp badge.
            val unit = 1.5.dp.toPx()
            val inset = 18.dp.toPx()
            fun p(x: Float, y: Float) = Offset(inset + x * unit, inset + y * unit)
            val path = Path().apply {
                moveTo(p(4f, 12f).x, p(4f, 12f).y)
                lineTo(p(9f, 17f).x, p(9f, 17f).y)
                lineTo(p(20f, 6f).x, p(20f, 6f).y)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val partial = Path()
            measure.getSegment(0f, measure.length * drawn(), partial, true)
            drawPath(
                partial,
                color = Synapse,
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
    }
}

/** Who signed in, what came back and when it last synced (Figma: Login & Logout 04 › Card/Summary). */
@Composable
private fun SummaryCard(user: CloudUser?, sync: SignInSync?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(Panel, shape)
            .border(1.dp, Edge, shape),
    ) {
        AccountRow(user)
        val restored = (sync as? SignInSync.Done)?.restored
        if (restored != null) {
            SummaryDivider()
            SummaryRow(
                "Restored",
                listOf(
                    if (restored.links == 1) "1 link" else "${restored.links} links",
                    if (restored.topics == 1) "1 topic" else "${restored.topics} topics",
                ).joinToString(" · "),
            )
        }
        if (sync != null) {
            SummaryDivider()
            SummaryRow(
                "Last synced",
                if (sync is SignInSync.Done) "Just now" else "Didn’t finish",
                valueColor = if (sync is SignInSync.Done) Ink else Amber,
            )
        }
    }
}

@Composable
private fun AccountRow(user: CloudUser?) {
    val label = user?.name ?: user?.email ?: "Google account"
    Row(
        Modifier.fillMaxWidth().padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(36.dp).background(SynapseDim, CircleShape), contentAlignment = Alignment.Center) {
            Text(label.first().uppercase(), color = Synapse, fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val email = user?.email
            if (email != null && email != label) {
                Text(email, style = MaterialTheme.typography.bodySmall, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, valueColor: Color = Ink) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = Muted, modifier = Modifier.weight(1f))
        Text(
            value,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            color = valueColor,
        )
    }
}

@Composable
private fun SummaryDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Edge))
}

// ── Shared pieces ───────────────────────────────────────────────────────

/** Full-width 48dp pill that dips slightly while pressed. */
@Composable
private fun PillButton(
    onClick: () -> Unit,
    container: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = container,
        interactionSource = interaction,
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** Fades and rises item [index] in as [progress] runs 0→1; read in the draw phase only. */
private fun Modifier.staggered(progress: () -> Float, index: Int, rise: Dp = 16.dp) = graphicsLayer {
    val start = index * STAGGER_STEP
    val p = EmphasizedDecelerate.transform(((progress() - start) / STAGGER_SPAN).coerceIn(0f, 1f))
    alpha = p
    translationY = (1f - p) * rise.toPx()
}
