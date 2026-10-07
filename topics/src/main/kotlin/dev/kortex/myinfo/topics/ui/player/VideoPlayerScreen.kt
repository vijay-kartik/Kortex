package dev.kortex.myinfo.topics.ui.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.FullscreenListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.toFloat
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import dev.kortex.design.Edge
import dev.kortex.design.Ink
import dev.kortex.design.InkSoft
import dev.kortex.design.KortexSnackbarHost
import dev.kortex.design.KortexTheme
import dev.kortex.design.Muted
import dev.kortex.design.Panel
import dev.kortex.design.Sunken
import dev.kortex.design.Synapse
import dev.kortex.design.SynapseDim
import dev.kortex.design.Void
import dev.kortex.mvi.ObserveEffects
import dev.kortex.mvi.ScopedViewModelStore
import dev.kortex.myinfo.topics.domain.model.PlaybackState
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.SeenRange
import dev.kortex.myinfo.topics.domain.model.SeenRanges
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.VideoProgress
import dev.kortex.myinfo.topics.ui.common.BodyStyle
import dev.kortex.myinfo.topics.ui.common.ButtonStyle
import dev.kortex.myinfo.topics.ui.common.HeroTitleStyle
import dev.kortex.myinfo.topics.ui.common.MetaStyle
import dev.kortex.myinfo.topics.ui.common.RowShape
import dev.kortex.myinfo.topics.ui.common.RowTitleStyle
import dev.kortex.myinfo.topics.ui.common.VideoThumbnail
import dev.kortex.myinfo.topics.ui.common.ageLabel
import dev.kortex.myinfo.topics.ui.common.formatDuration
import dev.kortex.myinfo.topics.ui.common.openUrl
import dev.kortex.myinfo.topics.ui.common.shareText
import kotlinx.coroutines.launch

/**
 * One of a topic's videos, playing in YouTube's embedded player (Figma: Topic videos 2e–2i).
 * [onPlay] opens another of the topic's videos the way tapping it in the feed would; [onClose]
 * goes back to the feed. Each video gets its own ViewModel, which saves what was watched as it goes
 * and once more when it's cleared.
 */
@Composable
fun VideoPlayerRoute(
    topicId: Long,
    itemId: Long,
    onClose: () -> Unit,
    onPlay: (TopicItem.Video) -> Unit,
    modifier: Modifier = Modifier,
) {
    ScopedViewModelStore(key = "video-$itemId") {
        val viewModel = hiltViewModel<VideoPlayerViewModel, VideoPlayerViewModel.Factory>(
            creationCallback = { factory -> factory.create(topicId, itemId) },
        )
        val context = LocalContext.current
        val state by viewModel.state.collectAsStateWithLifecycle()
        val snackbars = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        var player by remember { mutableStateOf<YouTubePlayer?>(null) }
        ObserveEffects(viewModel.effects) { effect ->
            when (effect) {
                is VideoPlayerEffect.SeekTo -> player?.seekTo(effect.seconds)
                is VideoPlayerEffect.OpenUrl -> if (!context.openUrl(effect.url)) {
                    scope.launch { snackbars.showSnackbar("No app on this phone opens YouTube links.") }
                }
                is VideoPlayerEffect.ShareText -> context.shareText(effect.subject, effect.text)
                VideoPlayerEffect.MarkedWatched -> scope.launch {
                    val result = snackbars.showSnackbar("Marked watched", actionLabel = "Undo", duration = SnackbarDuration.Short)
                    if (result == SnackbarResult.ActionPerformed) viewModel.onIntent(VideoPlayerIntent.UndoWatched)
                }
                // Launched, so a snackbar on screen doesn't hold up the effects behind it.
                is VideoPlayerEffect.ShowMessage -> scope.launch { snackbars.showSnackbar(effect.text) }
                is VideoPlayerEffect.Play -> onPlay(effect.video)
                VideoPlayerEffect.Close -> onClose()
            }
        }
        // The player pauses itself when the app is stopped; this saves before the process might go.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) viewModel.onIntent(VideoPlayerIntent.Backgrounded)
            }
            lifecycle.addObserver(observer)
            onDispose { lifecycle.removeObserver(observer) }
        }
        // Sideways is full screen, as long as there's a player to fill it with (Figma: Topic videos 2j).
        val activity = context.findActivity()
        val orientation = remember(activity) { activity?.let(::PlayerOrientation) }
        DisposableEffect(orientation) {
            orientation?.start()
            onDispose { orientation?.stop() }
        }
        val fullScreen = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE && state.showsPlayer
        HideSystemBars(fullScreen)
        KeepScreenOn(state.playing)
        BackHandler(onBack = onClose)
        // Registered later, so it goes first: back leaves full screen before it leaves the player.
        BackHandler(enabled = fullScreen) { orientation?.leaveFullScreen() }
        VideoPlayerScreen(
            state = state,
            onIntent = viewModel::onIntent,
            onBack = onClose,
            snackbars = snackbars,
            modifier = modifier,
            fullScreen = fullScreen,
            onFullScreen = orientation?.let { { it.enterFullScreen() } },
            embed = { start, embedModifier ->
                // A player built again (the activity recreated, say for a theme change) picks up where this one got to.
                val from = start.copy(startSeconds = state.positionSeconds ?: start.startSeconds)
                YouTubeEmbed(from, state.attempt, viewModel::onIntent, onPlayer = { player = it }, modifier = embedModifier)
            },
        )
    }
}

/**
 * [embed] draws the player itself; previews pass a stand-in, as the real one is a WebView that
 * only loads on a device. In [fullScreen] the player fills the screen, letterboxed, with YouTube's
 * controls and nothing of Kortex's. It stays in the same place in the tree either way, so turning
 * the phone re-lays the player out rather than building it again, and playback carries on.
 * [onFullScreen] is null where the screen can't be turned (previews).
 */
@Composable
fun VideoPlayerScreen(
    state: VideoPlayerState,
    onIntent: (VideoPlayerIntent) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    snackbars: SnackbarHostState = remember { SnackbarHostState() },
    fullScreen: Boolean = false,
    onFullScreen: (() -> Unit)? = null,
    embed: @Composable (PlayerStart, Modifier) -> Unit = { _, embedModifier -> Box(embedModifier.background(Color.Black)) },
) {
    Scaffold(
        modifier = modifier,
        containerColor = if (fullScreen) Color.Black else MaterialTheme.colorScheme.background,
        snackbarHost = { if (!fullScreen) KortexSnackbarHost(snackbars, Modifier.navigationBarsPadding()) },
        topBar = { if (!fullScreen) PlayerTopBar(state.topicName, onBack) },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // 16:9 at full width: never under YouTube's 200 dp minimum on a phone.
            Box(
                if (fullScreen) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                }.background(Color.Black),
            ) {
                val start = state.start
                if (state.showsPlayer && start != null) {
                    val embedModifier = if (fullScreen) {
                        Modifier
                            .align(Alignment.Center)
                            .fillMaxHeight()
                            .aspectRatio(16f / 9f, matchHeightConstraintsFirst = true)
                    } else {
                        Modifier.fillMaxSize()
                    }
                    embed(start, embedModifier)
                } else {
                    state.problem?.let { ProblemPanel(it, state, onIntent, Modifier.align(Alignment.Center)) }
                }
            }
            val video = state.video
            if (video != null && !fullScreen) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp)
                        .navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Under the player, never on it: YouTube doesn't allow anything drawn over the embed.
                    AnimatedVisibility(visible = state.resumingAtSeconds != null) {
                        state.resumingAtSeconds?.let { ResumeRow(it, onStartOver = { onIntent(VideoPlayerIntent.StartOver) }) }
                    }
                    Header(
                        video,
                        state.nowMillis,
                        onFullScreen = onFullScreen.takeIf { state.showsPlayer },
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (state.problem == PlayerProblem.EmbedBlocked) {
                        Notice("Kortex can’t see how much you watch in YouTube. Tick Mark watched when you’re done.")
                    } else {
                        progressLine(video, state.positionSeconds)?.let { ProgressCard(it) }
                    }
                    Actions(video, showOpenInYouTube = state.problem != PlayerProblem.EmbedBlocked, onIntent = onIntent)
                    val next = state.nextUnwatched
                    when {
                        next != null -> NextUnwatchedCard(next, onPlay = { onIntent(VideoPlayerIntent.Play(next)) })
                        state.upNext.isNotEmpty() && !state.ended -> UpNext(state, onIntent)
                    }
                }
            }
        }
    }
}

/**
 * YouTube's IFrame player with its own controls. Built afresh for another video or a Try again;
 * released when it leaves the screen. Nothing is layered over it.
 */
@Composable
private fun YouTubeEmbed(
    start: PlayerStart,
    attempt: Int,
    onIntent: (VideoPlayerIntent) -> Unit,
    onPlayer: (YouTubePlayer?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val report by rememberUpdatedState(onIntent)
    val setPlayer by rememberUpdatedState(onPlayer)
    key(start.videoId, attempt) {
        AndroidView(
            modifier = modifier,
            factory = { context ->
                YouTubePlayerView(context).apply {
                    enableAutomaticInitialization = false
                    lifecycle.addObserver(this)
                    val options = IFramePlayerOptions.Builder(context)
                        .controls(1)
                        .rel(0)
                        .ivLoadPolicy(3)
                        .fullscreen(0)
                        .build()
                    val listener = object : AbstractYouTubePlayerListener() {
                        override fun onReady(youTubePlayer: YouTubePlayer) {
                            setPlayer(youTubePlayer)
                            youTubePlayer.loadVideo(start.videoId, start.startSeconds.toFloat())
                        }

                        override fun onStateChange(youTubePlayer: YouTubePlayer, state: PlayerConstants.PlayerState) =
                            report(VideoPlayerIntent.StateChanged(state.toPlaybackState()))

                        override fun onCurrentSecond(youTubePlayer: YouTubePlayer, second: Float) =
                            report(VideoPlayerIntent.SecondChanged(second))

                        override fun onVideoDuration(youTubePlayer: YouTubePlayer, duration: Float) =
                            report(VideoPlayerIntent.LengthKnown(duration))

                        override fun onPlaybackRateChange(youTubePlayer: YouTubePlayer, playbackRate: PlayerConstants.PlaybackRate) =
                            report(VideoPlayerIntent.RateChanged(playbackRate.toFloat()))

                        override fun onError(youTubePlayer: YouTubePlayer, error: PlayerConstants.PlayerError) =
                            report(VideoPlayerIntent.Failed(error.toFailure()))
                    }
                    // YouTube's full-screen button is off (turning the phone does it, see PlayerOrientation).
                    // Should the page still ask, the library needs a listener or it throws; this one declines.
                    addFullscreenListener(object : FullscreenListener {
                        override fun onEnterFullscreen(fullscreenView: View, exitFullscreen: () -> Unit) = exitFullscreen()
                        override fun onExitFullscreen() = Unit
                    })
                    // Connectivity is the screen's to handle: it shows the offline state instead of a stalled player.
                    initialize(listener, false, options)
                }
            },
            onRelease = { view ->
                lifecycle.removeObserver(view)
                view.release()
                setPlayer(null)
            },
        )
    }
}

private fun PlayerConstants.PlayerState.toPlaybackState(): PlaybackState = when (this) {
    PlayerConstants.PlayerState.UNSTARTED -> PlaybackState.Unstarted
    PlayerConstants.PlayerState.BUFFERING -> PlaybackState.Buffering
    PlayerConstants.PlayerState.PLAYING -> PlaybackState.Playing
    PlayerConstants.PlayerState.PAUSED -> PlaybackState.Paused
    PlayerConstants.PlayerState.ENDED -> PlaybackState.Ended
    PlayerConstants.PlayerState.VIDEO_CUED, PlayerConstants.PlayerState.UNKNOWN -> PlaybackState.Other
}

// IFrame errors 101 and 150 both arrive as "not playable in embedded player".
private fun PlayerConstants.PlayerError.toFailure(): PlayerFailure = when (this) {
    PlayerConstants.PlayerError.VIDEO_NOT_PLAYABLE_IN_EMBEDDED_PLAYER -> PlayerFailure.EmbedBlocked
    PlayerConstants.PlayerError.VIDEO_NOT_FOUND -> PlayerFailure.NotFound
    PlayerConstants.PlayerError.UNKNOWN,
    PlayerConstants.PlayerError.INVALID_PARAMETER_IN_REQUEST,
    PlayerConstants.PlayerError.HTML_5_PLAYER,
    PlayerConstants.PlayerError.REQUEST_MISSING_HTTP_REFERER,
    -> PlayerFailure.Other
}

@Composable
private fun PlayerTopBar(topicName: String, onBack: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Text(
            "‹",
            style = ButtonStyle.copy(fontSize = 20.sp),
            color = Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .minimumInteractiveComponentSize()
                .semantics { contentDescription = "Back" }
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClick = onBack)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
        Text(
            topicName.uppercase(),
            style = MetaStyle.copy(letterSpacing = 1.2.sp),
            color = Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 56.dp),
        )
    }
}

/** What stands where the player would be, when it can't be (Figma: Topic videos 2h, 2i). */
@Composable
private fun ProblemPanel(problem: PlayerProblem, state: VideoPlayerState, onIntent: (VideoPlayerIntent) -> Unit, modifier: Modifier = Modifier) {
    val resume = state.video?.takeUnless { it.watched }?.progress?.resumeSeconds?.takeIf { it > 0 }
    val (title, body) = when (problem) {
        PlayerProblem.Offline -> "You’re offline" to
            ("Videos stream from YouTube." + (resume?.let { " Your place is saved — you’ll pick up at ${formatDuration(it)}." } ?: ""))
        PlayerProblem.EmbedBlocked -> "Plays only on YouTube" to "The uploader has turned off playback in other apps."
        PlayerProblem.Unavailable -> "Can’t play this video here" to "It may have been removed or made private."
        PlayerProblem.Failed -> "The video didn’t load" to "Something went wrong in the player."
    }
    Column(
        modifier
            .padding(horizontal = 32.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = RowTitleStyle.copy(fontSize = 16.sp), color = Ink, textAlign = TextAlign.Center)
        Text(body, style = BodyStyle, color = Muted, textAlign = TextAlign.Center)
        when (problem) {
            PlayerProblem.Offline, PlayerProblem.Failed ->
                PanelButton("Try again", primary = false, onClick = { onIntent(VideoPlayerIntent.TryAgain) })
            PlayerProblem.EmbedBlocked, PlayerProblem.Unavailable ->
                PanelButton("Open in YouTube ↗", primary = true, onClick = { onIntent(VideoPlayerIntent.OpenInYouTube) })
        }
    }
}

@Composable
private fun PanelButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        label,
        style = ButtonStyle.copy(fontSize = 14.sp),
        color = if (primary) Void else Ink,
        modifier = Modifier
            .padding(top = 6.dp)
            .minimumInteractiveComponentSize()
            .clip(shape)
            .then(if (primary) Modifier.background(Synapse) else Modifier.background(Panel).border(1.dp, Edge, shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

@Composable
private fun ResumeRow(resumeSeconds: Int, onStartOver: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .clip(RowShape)
            .background(Panel)
            .border(1.dp, Edge, RowShape)
            .padding(start = 14.dp),
    ) {
        Text(
            "Resuming at ${formatDuration(resumeSeconds)}",
            style = RowTitleStyle,
            color = Ink,
            modifier = Modifier
                .weight(1f)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        Text(
            "START OVER",
            style = MetaStyle,
            color = Synapse,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clickable(role = Role.Button, onClickLabel = "Start over", onClick = onStartOver)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/**
 * Title and meta line. Full screen sits at the end of the meta line: YouTube's own full-screen
 * button can't be driven from outside the player, so turning the screen is Kortex's to offer.
 */
@Composable
private fun Header(video: TopicItem.Video, nowMillis: Long, onFullScreen: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            video.link.title.ifBlank { video.link.url },
            style = HeroTitleStyle.copy(fontSize = 21.sp, lineHeight = 27.sp),
            color = Ink,
            modifier = Modifier.semantics { heading() },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(videoMetaLine(video, nowMillis), style = MetaStyle, color = Muted, modifier = Modifier.weight(1f))
            if (onFullScreen != null) {
                Text(
                    "FULL SCREEN ⤢",
                    style = MetaStyle,
                    color = Synapse,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClickLabel = "Watch full screen", onClick = onFullScreen)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Status and navigation bars out of the way while full screen; a swipe from the edge brings them back for a moment. */
@Composable
private fun HideSystemBars(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(hidden) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (hidden && controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (hidden) controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

/** A video that's playing shouldn't be dimmed away mid-scene; paused, the screen sleeps as usual. */
@Composable
private fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * How much has been seen, and how much is left: the stretches seen fill the bar, and a tick marks
 * where it resumes. Skipped parts stay empty (Figma: Topic videos 2f).
 */
@Composable
private fun ProgressCard(line: ProgressLine) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(Panel)
            .border(1.dp, Edge, RowShape)
            .clearAndSetSemantics { contentDescription = line.spoken }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row {
            Text(line.label, style = MetaStyle, color = Synapse, modifier = Modifier.weight(1f))
            Text(line.trailing, style = MetaStyle, color = Muted)
        }
        SeenBar(line.seen, line.lengthSeconds, line.markerSeconds)
        if (line.showsLegend) {
            Text("▬ SEEN   │ RESUMES HERE   · SKIPPED PARTS DON’T COUNT", style = MetaStyle.copy(fontSize = 9.sp, letterSpacing = 0.5.sp), color = Muted)
        }
    }
}

@Composable
private fun SeenBar(seen: SeenRanges, lengthSeconds: Int, markerSeconds: Int?) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(10.dp),
    ) {
        val barHeight = 5.dp.toPx()
        val top = (size.height - barHeight) / 2
        val radius = CornerRadius(barHeight / 2)
        drawRoundRect(Sunken, topLeft = Offset(0f, top), size = Size(size.width, barHeight), cornerRadius = radius)
        for (range in seen.ranges) {
            val start = range.start.coerceAtMost(lengthSeconds).toFloat() / lengthSeconds * size.width
            val end = range.end.coerceAtMost(lengthSeconds).toFloat() / lengthSeconds * size.width
            if (end > start) {
                drawRoundRect(Synapse, topLeft = Offset(start, top), size = Size(end - start, barHeight), cornerRadius = radius)
            }
        }
        markerSeconds?.let {
            val x = it.toFloat() / lengthSeconds * size.width
            val tick = 3.dp.toPx()
            drawRect(Ink, topLeft = Offset((x - tick / 2).coerceIn(0f, size.width - tick), 0f), size = Size(tick, size.height))
        }
    }
}

@Composable
private fun Notice(text: String) {
    Text(
        text,
        style = BodyStyle,
        color = InkSoft,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RowShape)
            .background(Panel)
            .border(1.dp, Edge, RowShape)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

@Composable
private fun Actions(video: TopicItem.Video, showOpenInYouTube: Boolean, onIntent: (VideoPlayerIntent) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        ActionButton(
            if (video.watched) "✓ Watched" else "Mark watched",
            selected = video.watched,
            onClick = { onIntent(VideoPlayerIntent.ToggleWatched) },
            modifier = Modifier.weight(1f),
            role = Role.Checkbox,
        )
        if (showOpenInYouTube) {
            ActionButton("Open in YouTube ↗", onClick = { onIntent(VideoPlayerIntent.OpenInYouTube) }, modifier = Modifier.weight(1f))
        }
        ActionButton("Share", onClick = { onIntent(VideoPlayerIntent.Share) }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, role: Role = Role.Button) {
    val shape = RoundedCornerShape(10.dp)
    Text(
        label,
        style = ButtonStyle.copy(fontSize = 13.sp),
        color = if (selected) Synapse else Ink,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .background(if (selected) SynapseDim else Panel)
            .border(1.dp, if (selected) Synapse else Edge, shape)
            .clickable(role = role, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 11.dp),
    )
}

/** When a video ends, the next one waits here; nothing starts on its own (Figma: Topic videos 2g). */
@Composable
private fun NextUnwatchedCard(next: TopicItem.Video, onPlay: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SynapseDim.copy(alpha = 0.5f))
            .border(1.dp, Synapse.copy(alpha = 0.6f), shape)
            .clickable(role = Role.Button, onClickLabel = "Play", onClick = onPlay)
            .padding(10.dp),
    ) {
        VideoThumbnail(next, Modifier.width(96.dp).aspectRatio(16f / 9f))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("NEXT UNWATCHED", style = MetaStyle, color = Synapse)
            Text(next.link.title.ifBlank { next.link.url }, style = RowTitleStyle, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(
            "▶ Play",
            style = ButtonStyle.copy(fontSize = 13.sp),
            color = Void,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(Synapse)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun UpNext(state: VideoPlayerState, onIntent: (VideoPlayerIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            "UP NEXT · UNWATCHED IN ${state.topicName.uppercase()}",
            style = MetaStyle,
            color = Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        state.upNext.take(UP_NEXT_LIMIT).forEach { video ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RowShape)
                    .clickable(role = Role.Button, onClickLabel = "Play") { onIntent(VideoPlayerIntent.Play(video)) },
            ) {
                VideoThumbnail(video, Modifier.width(112.dp).aspectRatio(16f / 9f))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(video.link.title.ifBlank { video.link.url }, style = RowTitleStyle, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (video.playsInApp) "UNWATCHED · ${ageLabel(video.addedAtMillis, state.nowMillis)}" else "OPENS YOUTUBE ↗",
                        style = MetaStyle.copy(letterSpacing = 0.5.sp),
                        color = Synapse,
                    )
                }
            }
        }
    }
}

private const val UP_NEXT_LIMIT = 5

// ── Previews ──────────────────────────────────────────────────────

private fun previewVideo(id: Long, title: String, length: Int, progress: VideoProgress? = null, watched: Boolean = false) = TopicItem.Video(
    id = id,
    topicId = 1,
    addedAtMillis = 1_741_000_000_000 - id * 86_400_000,
    link = SavedLink(id, "https://youtu.be/dQw4w9WgXc$id", title, thumbnailPath = null),
    durationSeconds = length,
    watched = watched,
    progress = progress,
)

private val previewState = VideoPlayerState(
    itemId = 1,
    topicName = "Trip to Dubai",
    video = previewVideo(
        1, "Dubai in 3 days — what’s actually worth it", 842,
        VideoProgress(320, SeenRanges.Empty + SeenRange(0, 190) + SeenRange(300, 320), lastPlayedAtMillis = 1_740_900_000_000),
    ),
    upNext = listOf(previewVideo(2, "Metro vs taxi — getting around cheap", 521), previewVideo(3, "Desert safari: which operator we picked", 1315)),
    start = PlayerStart("dQw4w9WgXcQ", 317),
    resumingAtSeconds = 320,
    positionSeconds = 320,
    nowMillis = 1_741_000_000_000,
)

@Preview
@Composable
private fun VideoPlayerResumingPreview() {
    KortexTheme { VideoPlayerScreen(previewState, onIntent = {}, onBack = {}) }
}

@Preview
@Composable
private fun VideoPlayerFinishedPreview() {
    val video = previewState.video!!.copy(
        watched = true,
        progress = VideoProgress(842, SeenRanges.Empty + SeenRange(0, 400) + SeenRange(420, 842), lastPlayedAtMillis = 1_741_000_000_000),
    )
    KortexTheme {
        VideoPlayerScreen(previewState.copy(video = video, ended = true, resumingAtSeconds = null, positionSeconds = 842), onIntent = {}, onBack = {})
    }
}

@Preview(widthDp = 800, heightDp = 360)
@Composable
private fun VideoPlayerFullScreenPreview() {
    KortexTheme { VideoPlayerScreen(previewState.copy(resumingAtSeconds = null), onIntent = {}, onBack = {}, fullScreen = true) }
}

@Preview
@Composable
private fun VideoPlayerOfflinePreview() {
    KortexTheme { VideoPlayerScreen(previewState.copy(problem = PlayerProblem.Offline, resumingAtSeconds = null), onIntent = {}, onBack = {}) }
}

@Preview
@Composable
private fun VideoPlayerBlockedPreview() {
    KortexTheme { VideoPlayerScreen(previewState.copy(problem = PlayerProblem.EmbedBlocked, resumingAtSeconds = null), onIntent = {}, onBack = {}) }
}
