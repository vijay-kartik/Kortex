package dev.kortex.myinfo.topics.ui.player

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.mvi.MviViewModel
import dev.kortex.myinfo.topics.di.TopicsScope
import dev.kortex.myinfo.topics.domain.model.PlaybackReport
import dev.kortex.myinfo.topics.domain.model.PlaybackState
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.WatchSession
import dev.kortex.myinfo.topics.domain.model.startSeconds
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.port.Connectivity
import dev.kortex.myinfo.topics.domain.usecase.MarkEmbedBlocked
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopic
import dev.kortex.myinfo.topics.domain.usecase.RecordVideoProgress
import dev.kortex.myinfo.topics.domain.usecase.SetItemDone
import dev.kortex.myinfo.topics.domain.usecase.YouTubeVideoId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Plays one of a topic's videos and keeps what was watched (docs/TOPIC_VIDEOS_PLAN.md). The
 * player's reports go through a [WatchSession]; what it says to save is saved on [appScope], so a
 * save started as the user leaves still lands.
 */
@HiltViewModel(assistedFactory = VideoPlayerViewModel.Factory::class)
class VideoPlayerViewModel @AssistedInject constructor(
    // Named: Dagger tells assisted parameters apart by type, and these are both Longs.
    @Assisted("topicId") private val topicId: Long,
    @Assisted("itemId") private val itemId: Long,
    observeTopic: ObserveTopic,
    private val recordProgress: RecordVideoProgress,
    private val markEmbedBlocked: MarkEmbedBlocked,
    private val setItemDone: SetItemDone,
    private val connectivity: Connectivity,
    private val clock: Clock,
    @TopicsScope private val appScope: CoroutineScope,
) : MviViewModel<VideoPlayerState, VideoPlayerIntent, VideoPlayerEffect>(VideoPlayerState(itemId)) {

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("topicId") topicId: Long, @Assisted("itemId") itemId: Long): VideoPlayerViewModel
    }

    private var session: WatchSession? = null
    private var closing = false

    init {
        viewModelScope.launch {
            observeTopic(topicId).collect { detail ->
                val video = detail?.items?.firstOrNull { it.id == itemId } as? TopicItem.Video
                if (detail == null || video == null) {
                    close()
                    return@collect
                }
                setState {
                    copy(
                        topicName = detail.topic.name,
                        video = video,
                        upNext = detail.items.filterIsInstance<TopicItem.Video>().filter { !it.watched && it.id != itemId },
                        nowMillis = clock.nowMillis(),
                    )
                }
                if (currentState.start == null && currentState.problem == null) begin(video)
            }
        }
    }

    override fun handleIntent(intent: VideoPlayerIntent) {
        when (intent) {
            is VideoPlayerIntent.StateChanged -> {
                session?.onState(intent.state)?.let(::save)
                val playing = intent.state == PlaybackState.Playing
                // Buffering mid-play keeps the screen on; only a real stop lets it sleep.
                val stopped = intent.state == PlaybackState.Paused || intent.state == PlaybackState.Ended
                setState {
                    copy(
                        ended = if (playing) false else ended || intent.state == PlaybackState.Ended,
                        playing = when {
                            playing -> true
                            stopped -> false
                            else -> this.playing
                        },
                    )
                }
            }
            is VideoPlayerIntent.SecondChanged -> {
                session?.onSecond(intent.second)?.let(::save)
                val whole = intent.second.toInt()
                if (whole != currentState.positionSeconds) setState { copy(positionSeconds = whole) }
            }
            is VideoPlayerIntent.LengthKnown -> session?.onLength(intent.seconds)
            is VideoPlayerIntent.RateChanged -> session?.onRate(intent.rate)
            is VideoPlayerIntent.Failed -> fail(intent.failure)
            VideoPlayerIntent.Backgrounded -> session?.close()?.let(::save)

            VideoPlayerIntent.StartOver -> {
                setState { copy(resumingAtSeconds = null) }
                sendEffect(VideoPlayerEffect.SeekTo(0f))
            }
            VideoPlayerIntent.TryAgain -> tryAgain()
            VideoPlayerIntent.ToggleWatched -> currentState.video?.let { video ->
                viewModelScope.launch { setItemDone(video.id, !video.watched) }
            }
            VideoPlayerIntent.UndoWatched -> viewModelScope.launch { setItemDone(itemId, false) }
            VideoPlayerIntent.OpenInYouTube -> currentState.video?.let { sendEffect(VideoPlayerEffect.OpenUrl(it.link.url)) }
            VideoPlayerIntent.Share -> currentState.video?.let { video ->
                val title = video.link.title.ifBlank { video.link.url }
                sendEffect(VideoPlayerEffect.ShareText(subject = title, text = "$title\n${video.link.url}"))
            }
            is VideoPlayerIntent.Play -> sendEffect(VideoPlayerEffect.Play(intent.video))
        }
    }

    /** Works out what to load and from where; an address without a video id can't play here. */
    private fun begin(video: TopicItem.Video) {
        val id = YouTubeVideoId.parse(video.link.url)
        if (id == null || video.embedBlocked) {
            setState { copy(problem = if (id == null) PlayerProblem.Unavailable else PlayerProblem.EmbedBlocked) }
            return
        }
        val start = video.record.startSeconds()
        session = WatchSession(clock, start)
        val resuming = video.progress?.resumeSeconds?.takeIf { start > 0 }
        setState {
            copy(
                start = PlayerStart(id.value, start),
                problem = if (connectivity.isOnline()) null else PlayerProblem.Offline,
                resumingAtSeconds = resuming,
            )
        }
        if (resuming != null) {
            viewModelScope.launch {
                delay(RESUME_NOTICE_MILLIS)
                setState { copy(resumingAtSeconds = null) }
            }
        }
    }

    private fun fail(failure: PlayerFailure) {
        // What was seen before it failed still counts.
        session?.close()?.let(::save)
        val problem = when (failure) {
            PlayerFailure.EmbedBlocked -> {
                appScope.launch { markEmbedBlocked(itemId) }
                PlayerProblem.EmbedBlocked
            }
            PlayerFailure.NotFound -> PlayerProblem.Unavailable
            PlayerFailure.Other -> if (connectivity.isOnline()) PlayerProblem.Failed else PlayerProblem.Offline
        }
        setState { copy(problem = problem, resumingAtSeconds = null, playing = false) }
    }

    /** Builds the player again, from where it got to, once there's a connection to stream on. */
    private fun tryAgain() {
        val state = currentState
        if (state.problem != PlayerProblem.Offline && state.problem != PlayerProblem.Failed) return
        if (!connectivity.isOnline()) {
            setState { copy(problem = PlayerProblem.Offline) }
            sendEffect(VideoPlayerEffect.ShowMessage("Still offline"))
            return
        }
        val from = session?.currentSecond?.toInt() ?: state.start?.startSeconds ?: 0
        setState { copy(problem = null, attempt = attempt + 1, start = start?.copy(startSeconds = from)) }
    }

    private fun save(report: PlaybackReport) {
        appScope.launch {
            if (recordProgress(itemId, report)) sendEffect(VideoPlayerEffect.MarkedWatched)
        }
    }

    private fun close() {
        if (closing) return
        closing = true
        sendEffect(VideoPlayerEffect.Close)
    }

    /** Leaving the player: whatever the session hasn't saved yet. */
    override fun onCleared() {
        session?.close()?.let { report -> appScope.launch { recordProgress(itemId, report) } }
        session = null
    }

    private companion object {
        const val RESUME_NOTICE_MILLIS = 4_000L
    }
}
