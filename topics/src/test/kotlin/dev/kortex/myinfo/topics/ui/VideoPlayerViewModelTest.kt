package dev.kortex.myinfo.topics.ui

import androidx.lifecycle.ViewModelStore
import dev.kortex.myinfo.topics.domain.FakeTopicsRepository
import dev.kortex.myinfo.topics.domain.model.ItemType
import dev.kortex.myinfo.topics.domain.model.PlaybackState
import dev.kortex.myinfo.topics.domain.model.SavedLink
import dev.kortex.myinfo.topics.domain.model.SeenRanges
import dev.kortex.myinfo.topics.domain.model.Topic
import dev.kortex.myinfo.topics.domain.model.TopicItem
import dev.kortex.myinfo.topics.domain.model.VideoProgress
import dev.kortex.myinfo.topics.domain.port.Clock
import dev.kortex.myinfo.topics.domain.port.Connectivity
import dev.kortex.myinfo.topics.domain.usecase.MarkEmbedBlocked
import dev.kortex.myinfo.topics.domain.usecase.ObserveTopic
import dev.kortex.myinfo.topics.domain.usecase.RecordVideoProgress
import dev.kortex.myinfo.topics.domain.usecase.SetItemDone
import dev.kortex.myinfo.topics.ui.player.PlayerFailure
import dev.kortex.myinfo.topics.ui.player.PlayerProblem
import dev.kortex.myinfo.topics.ui.player.PlayerStart
import dev.kortex.myinfo.topics.ui.player.VideoPlayerEffect
import dev.kortex.myinfo.topics.ui.player.VideoPlayerIntent
import dev.kortex.myinfo.topics.ui.player.VideoPlayerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerViewModelTest {
    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = UnconfinedTestDispatcher(scheduler)
    private val repository = FakeTopicsRepository()
    private var online = true
    private var now = NOW
    private val clock = Clock { now }

    private val topic = Topic(7, "Trip to Dubai", purpose = null, pinned = false, sections = setOf(ItemType.Video), createdAtMillis = 0, updatedAtMillis = 0)
    private val video = video(1, "dQw4w9WgXcQ", addedAt = 30)
    private val next = video(2, "9bZkp7q19f0", addedAt = 20)
    private val older = video(3, "kJQP7kiw5Fk", addedAt = 10)
    private val seen = video(4, "JGwWNGJdvx8", addedAt = 5).copy(watched = true)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a video never played starts from the top, with the topic's other unwatched videos up next`() = runTest(dispatcher) {
        val state = viewModel().state.value

        assertEquals(PlayerStart("dQw4w9WgXcQ", 0), state.start)
        assertNull(state.problem)
        assertNull(state.resumingAtSeconds)
        assertEquals("Trip to Dubai", state.topicName)
        assertEquals(listOf(next, older), state.upNext)
    }

    @Test
    fun `a video played before resumes a little early, and says so for a few seconds`() = runTest(dispatcher) {
        val viewModel = viewModel(video.copy(durationSeconds = 842, progress = VideoProgress(320, SeenRanges.Empty, NOW)))

        assertEquals(317, viewModel.state.value.start?.startSeconds)
        assertEquals(320, viewModel.state.value.resumingAtSeconds)

        advanceTimeBy(4_001)
        assertNull(viewModel.state.value.resumingAtSeconds)
    }

    @Test
    fun `start over seeks to the top and drops the notice`() = runTest(dispatcher) {
        val viewModel = viewModel(video.copy(durationSeconds = 842, progress = VideoProgress(320, SeenRanges.Empty, NOW)))

        viewModel.onIntent(VideoPlayerIntent.StartOver)

        assertEquals(VideoPlayerEffect.SeekTo(0f), viewModel.effects.first())
        assertNull(viewModel.state.value.resumingAtSeconds)
    }

    @Test
    fun `offline, the player waits, and Try again builds it once there's a connection`() = runTest(dispatcher) {
        online = false
        val viewModel = viewModel()
        assertEquals(PlayerProblem.Offline, viewModel.state.value.problem)
        assertFalse(viewModel.state.value.showsPlayer)

        viewModel.onIntent(VideoPlayerIntent.TryAgain)
        assertEquals(VideoPlayerEffect.ShowMessage("Still offline"), viewModel.effects.first())

        online = true
        viewModel.onIntent(VideoPlayerIntent.TryAgain)
        assertNull(viewModel.state.value.problem)
        assertEquals(1, viewModel.state.value.attempt)
    }

    @Test
    fun `playing then pausing saves what was seen`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.play(0..30)
        viewModel.onIntent(VideoPlayerIntent.StateChanged(PlaybackState.Paused))

        val saved = repository.videos.getValue(1)
        assertEquals(100, saved.durationSeconds)
        assertEquals("0-30", saved.progress!!.seen.encode())
        assertEquals(30, saved.progress!!.resumeSeconds)
        assertEquals(30, viewModel.state.value.positionSeconds)
    }

    @Test
    fun `reaching the end marks it watched, offers the next one, and undo takes it back`() = runTest(dispatcher) {
        val viewModel = viewModel()
        viewModel.play(0..99)
        viewModel.onIntent(VideoPlayerIntent.StateChanged(PlaybackState.Ended))

        assertEquals(VideoPlayerEffect.MarkedWatched, viewModel.effects.first())
        assertTrue(repository.videos.getValue(1).watched)
        assertTrue(viewModel.state.value.ended)
        assertEquals(next, viewModel.state.value.nextUnwatched)

        viewModel.onIntent(VideoPlayerIntent.UndoWatched)
        assertEquals(false, repository.done[1])
    }

    @Test
    fun `the screen stays on while playing, through buffering, and not once paused`() = runTest(dispatcher) {
        val viewModel = viewModel()
        assertFalse(viewModel.state.value.playing)

        viewModel.onIntent(VideoPlayerIntent.StateChanged(PlaybackState.Playing))
        assertTrue(viewModel.state.value.playing)

        viewModel.onIntent(VideoPlayerIntent.StateChanged(PlaybackState.Buffering))
        assertTrue(viewModel.state.value.playing)

        viewModel.onIntent(VideoPlayerIntent.StateChanged(PlaybackState.Paused))
        assertFalse(viewModel.state.value.playing)
    }

    @Test
    fun `leaving the player saves what the last report didn't`() = runTest(dispatcher) {
        val store = ViewModelStore()
        val viewModel = viewModel()
        store.put("player", viewModel)
        viewModel.play(0..10)
        assertNull(repository.videos.getValue(1).progress)

        store.clear()

        assertEquals("0-10", repository.videos.getValue(1).progress!!.seen.encode())
    }

    @Test
    fun `an uploader blocking the embed is remembered, and nothing more is tracked`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onIntent(VideoPlayerIntent.Failed(PlayerFailure.EmbedBlocked))

        assertEquals(PlayerProblem.EmbedBlocked, viewModel.state.value.problem)
        assertTrue(repository.videos.getValue(1).embedBlocked)
    }

    @Test
    fun `a failure while offline reads as offline`() = runTest(dispatcher) {
        val viewModel = viewModel()
        online = false

        viewModel.onIntent(VideoPlayerIntent.Failed(PlayerFailure.Other))

        assertEquals(PlayerProblem.Offline, viewModel.state.value.problem)
    }

    @Test
    fun `an address with no YouTube id can't play here`() = runTest(dispatcher) {
        val state = viewModel(video.copy(link = SavedLink(1, "https://vimeo.com/123456", "Dubai", thumbnailPath = null))).state.value

        assertEquals(PlayerProblem.Unavailable, state.problem)
        assertNull(state.start)
    }

    @Test
    fun `the player closes when its video is deleted`() = runTest(dispatcher) {
        val viewModel = viewModel()

        repository.observedItems.value = listOf(next, older)

        assertEquals(VideoPlayerEffect.Close, viewModel.effects.first())
    }

    @Test
    fun `mark watched ticks it by hand, and ticking again unticks it`() = runTest(dispatcher) {
        val viewModel = viewModel()

        viewModel.onIntent(VideoPlayerIntent.ToggleWatched)
        assertEquals(true, repository.done[1])

        repository.observedItems.value = listOf(video.copy(watched = true), next, older)
        viewModel.onIntent(VideoPlayerIntent.ToggleWatched)
        assertEquals(false, repository.done[1])
    }

    /** Playing from the first second to the last of [seconds], a tick each, as the player reports. */
    private fun VideoPlayerViewModel.play(seconds: IntRange) {
        onIntent(VideoPlayerIntent.LengthKnown(100f))
        onIntent(VideoPlayerIntent.StateChanged(PlaybackState.Playing))
        for (second in seconds) {
            now += 100
            onIntent(VideoPlayerIntent.SecondChanged(second.toFloat()))
        }
    }

    private fun viewModel(playing: TopicItem.Video = video): VideoPlayerViewModel {
        repository.observedTopics.value = listOf(topic)
        repository.observedItems.value = listOf(playing, next, older, seen)
        repository.videos[playing.id] = playing.record
        return VideoPlayerViewModel(
            topicId = 7,
            itemId = playing.id,
            observeTopic = ObserveTopic(repository),
            recordProgress = RecordVideoProgress(repository, clock),
            markEmbedBlocked = MarkEmbedBlocked(repository, clock),
            setItemDone = SetItemDone(repository, clock),
            connectivity = Connectivity { online },
            clock = clock,
            appScope = CoroutineScope(dispatcher),
        )
    }

    private fun video(id: Long, youTubeId: String, addedAt: Long) = TopicItem.Video(
        id = id,
        topicId = 7,
        addedAtMillis = addedAt,
        link = SavedLink(id, "https://youtu.be/$youTubeId", "Video $id", thumbnailPath = null),
        durationSeconds = null,
        watched = false,
    )

    private companion object {
        const val NOW = 1_700_000_000_000L
    }
}
