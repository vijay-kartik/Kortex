package dev.kortex.app.ui.screens.chat

import dev.kortex.app.domain.agent.ModelSelection
import dev.kortex.app.domain.chat.ChatSession
import dev.kortex.app.domain.chat.ChatSessionRepository
import dev.kortex.app.domain.chat.ChatSessionSummary
import dev.kortex.app.domain.chat.ChatTurn
import dev.kortex.app.domain.voice.VoiceDictation
import dev.kortex.app.domain.voice.VoiceEvent
import dev.kortex.app.domain.voice.VoiceFailure
import dev.kortex.core.llm.LlmChunk
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.llm.LlmRequest
import dev.kortex.core.llm.LlmResponse
import dev.kortex.core.log.Logger
import dev.kortex.core.observability.AgentRun
import dev.kortex.core.observability.AgentRunStore
import dev.kortex.core.observability.AgentRunSummary
import dev.kortex.core.state.Message
import dev.kortex.core.tool.ToolRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The chat screen's voice dictation and send flow, with the speech engine, LLM and storage faked. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    private val voice = FakeVoiceDictation()
    private val llm = FakeLlm()
    private val sessions = FakeSessions()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(provider: String = "ollama-cloud", model: String = "gemma4:31b") = ChatViewModel(
        provider = llm,
        tools = ToolRegistry(),
        modelSelection = FakeModelSelection(provider, model),
        sessionRepository = sessions,
        runTraceStore = NoRunStore,
        voiceDictation = voice,
    )

    @Test
    fun `a final transcript stages a voice attachment`() = runTest {
        val vm = viewModel()

        vm.startVoiceInput()
        voice.events.emit(VoiceEvent.Final("remind me to call mom"))

        val staged = vm.stagedAttachments.value.single()
        assertEquals("audio/x-voice", staged.mimeType)
        assertEquals("remind me to call mom", staged.transcript)
        assertEquals(VoiceState.Idle, vm.voice.value)
        assertNull(vm.voiceError.value)
    }

    @Test
    fun `a blank transcript stages nothing and says so`() = runTest {
        val vm = viewModel()

        vm.startVoiceInput()
        voice.events.emit(VoiceEvent.Final(""))

        assertTrue(vm.stagedAttachments.value.isEmpty())
        assertEquals("Didn't catch that — try again.", vm.voiceError.value)
        assertEquals(VoiceState.Idle, vm.voice.value)
    }

    @Test
    fun `a failed dictation shows why and returns to idle`() = runTest {
        val vm = viewModel()

        vm.startVoiceInput()
        voice.events.emit(VoiceEvent.Failed(VoiceFailure.NO_PERMISSION))

        assertTrue(vm.stagedAttachments.value.isEmpty())
        assertEquals("Microphone permission is required.", vm.voiceError.value)
        assertEquals(VoiceState.Idle, vm.voice.value)
    }

    @Test
    fun `a cancelled dictation stages nothing, even if a result arrives late`() = runTest {
        val vm = viewModel()

        vm.startVoiceInput()
        vm.cancelVoiceInput()
        voice.events.emit(VoiceEvent.Final("never mind"))

        assertTrue(voice.cancelled)
        assertTrue(vm.stagedAttachments.value.isEmpty())
        assertNull(vm.voiceError.value)
        assertEquals(VoiceState.Idle, vm.voice.value)
    }

    @Test
    fun `a voice-only message asks the agent the transcript`() = runTest {
        val vm = viewModel()
        vm.startVoiceInput()
        voice.events.emit(VoiceEvent.Final("remind me to call mom"))

        vm.send("")
        // The agent graph runs on Dispatchers.Default; the session save marks the turn done.
        val save = sessions.saved.first { it.isNotEmpty() }.single()

        val userMessages = llm.requests.flatMap { req -> req.messages.filter { it.role == Message.Role.USER } }
        assertTrue(userMessages.any { it.content == "remind me to call mom" })
        assertEquals("remind me to call mom", save.fallbackTitle)
        // The chat shows the voice note itself, not the transcript a second time as text.
        val userTurn = vm.ui.first().turns.first().message
        assertEquals("", userTurn.content)
        assertEquals("remind me to call mom", userTurn.attachments.single().transcript)
        assertTrue(vm.stagedAttachments.value.isEmpty())
    }

    @Test
    fun `the header shows the OpenAI model the user picked`() = runTest {
        val vm = viewModel(provider = "openai", model = "gpt-4-turbo")

        assertEquals("gpt-4-turbo", vm.ui.first { it.activeModel != null }.activeModel)
    }

    private class FakeVoiceDictation : VoiceDictation {
        override val events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 16)
        var cancelled = false

        override fun start() {}
        override fun stop() {}
        override fun cancel() { cancelled = true }
        override fun release() {}
    }

    private class FakeModelSelection(provider: String, model: String) : ModelSelection {
        override val activeProvider: Flow<String> = flowOf(provider)
        override val activeModel: Flow<String> = flowOf(model)
    }

    private class FakeLlm : LlmProvider {
        val requests = mutableListOf<LlmRequest>()

        override suspend fun complete(req: LlmRequest, logger: Logger?): LlmResponse {
            requests += req
            return LlmResponse(Message(Message.Role.ASSISTANT, "Done."))
        }

        override fun stream(req: LlmRequest): Flow<LlmChunk> = emptyFlow()
    }

    private class FakeSessions : ChatSessionRepository {
        data class Save(val id: String, val turns: List<ChatTurn>, val fallbackTitle: String)
        val saved = MutableStateFlow<List<Save>>(emptyList())

        override fun observeSummaries(): Flow<List<ChatSessionSummary>> = flowOf(emptyList())
        override suspend fun get(id: String): ChatSession? = null
        override suspend fun save(id: String, turns: List<ChatTurn>, fallbackTitle: String) {
            saved.update { it + Save(id, turns, fallbackTitle) }
        }
        override suspend fun delete(id: String) {}
    }

    private object NoRunStore : AgentRunStore {
        override suspend fun save(run: AgentRun) {}
        override fun observeSummaries(limit: Int): Flow<List<AgentRunSummary>> = flowOf(emptyList())
        override suspend fun get(id: String): AgentRun? = null
        override suspend fun clear() {}
    }
}
