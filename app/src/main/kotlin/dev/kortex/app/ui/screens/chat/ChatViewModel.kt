package dev.kortex.app.ui.screens.chat

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kortex.core.log.AndroidLogger
import dev.kortex.core.Agent
import dev.kortex.core.graph.AgentContext
import dev.kortex.core.graph.Approver
import dev.kortex.core.graph.LlmUsageListener
import dev.kortex.core.graph.ProgressListener
import dev.kortex.core.llm.LlmProvider
import dev.kortex.core.log.Logger
import dev.kortex.core.state.Attachment
import dev.kortex.core.state.Message
import dev.kortex.core.tool.ToolGovernor
import dev.kortex.core.tool.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dev.kortex.core.observability.AgentRunStore
import dev.kortex.app.domain.agent.ActiveModels
import dev.kortex.app.domain.agent.ModelSelection
import dev.kortex.app.domain.chat.ChatSessionRepository
import dev.kortex.app.domain.chat.ChatTurn
import dev.kortex.app.domain.chat.ReasoningLine
import dev.kortex.app.domain.chat.ReasoningStats
import dev.kortex.app.domain.voice.VoiceDictation
import dev.kortex.app.domain.voice.VoiceEvent
import dev.kortex.app.domain.voice.VoiceFailure
import dev.kortex.app.domain.chat.sessionTitle
import dev.kortex.mvi.MviViewModel
import java.util.UUID
import javax.inject.Inject

/**
 * Wires the pure-Kotlin [Agent] to Compose. The [Approver] bridges the agent's
 * Human-in-the-Loop pause to a UI dialog: the agent suspends until [ChatIntent.ResolveApproval].
 * The singleton [ToolRegistry] is injected so that the MCP settings
 * screen and this ViewModel operate on the same tool set; AgentBootstrap sets it up at app start.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val provider: LlmProvider,
    private val tools: ToolRegistry,
    private val modelSelection: ModelSelection,
    private val sessionRepository: ChatSessionRepository,
    private val runTraceStore: AgentRunStore,
    private val voiceDictation: VoiceDictation,
) : MviViewModel<ChatState, ChatIntent, ChatEffect>(ChatState()) {

    private var voiceTicker: Job? = null
    private var voiceStartMs = 0L

    private var approvalGate: CompletableDeferred<Boolean>? = null

    private var currentSessionId: String = UUID.randomUUID().toString()

    private val approver = Approver { name, args ->
        setState { copy(pendingApproval = "$name $args") }
        CompletableDeferred<Boolean>().also { approvalGate = it }.await()
    }
    private val progress = ProgressListener { s -> setState { copy(status = s) } }

    init {
        combine(modelSelection.activeProvider, modelSelection.activeModel) { provider, model ->
            provider to ActiveModels.of(provider, model).reasoning
        }.reduceInto { (provider, model) -> copy(activeProvider = provider, activeModel = model) }
        sessionRepository.observeSummaries().reduceInto { copy(sessions = it) }
        viewModelScope.launch { voiceDictation.events.collect(::onVoiceEvent) }
    }

    override fun handleIntent(intent: ChatIntent) {
        when (intent) {
            is ChatIntent.Send -> send(intent.text)
            is ChatIntent.StageAttachment -> setState { copy(stagedAttachments = stagedAttachments + intent.attachment) }
            is ChatIntent.RemoveAttachment ->
                setState { copy(stagedAttachments = stagedAttachments.filterIndexed { i, _ -> i != intent.index }) }
            ChatIntent.StartVoice -> startVoiceInput()
            ChatIntent.StopVoice -> stopVoiceInput()
            ChatIntent.CancelVoice -> cancelVoiceInput()
            is ChatIntent.ResolveApproval -> resolveApproval(intent.approved)
            is ChatIntent.LoadSession -> loadSession(intent.sessionId)
            is ChatIntent.NewSession -> startNewSession(intent.draft)
            is ChatIntent.DeleteSession -> deleteSession(intent.sessionId)
            is ChatIntent.RenameSession ->
                viewModelScope.launch { sessionRepository.rename(intent.sessionId, intent.title) }
        }
    }

    private fun resolveApproval(approved: Boolean) {
        setState { copy(pendingApproval = null) }
        approvalGate?.complete(approved)
        approvalGate = null
    }

    // ── voice input (dictation via VoiceDictation) ──────────

    /** Opens the mic and streams live partial transcripts into [ChatState.voice]. Caller must hold
     *  RECORD_AUDIO. The final transcript is staged as a voice-note attachment. */
    private fun startVoiceInput() {
        if (currentState.voice !is VoiceState.Idle) return
        voiceStartMs = System.currentTimeMillis()
        setState { copy(voice = VoiceState.Listening()) }
        voiceTicker = viewModelScope.launch {
            while (true) {
                delay(100)
                updateListening { copy(elapsedMs = System.currentTimeMillis() - voiceStartMs) }
            }
        }
        voiceDictation.start()
    }

    private fun stopVoiceInput() {
        if (currentState.voice !is VoiceState.Listening) return
        setState { copy(voice = VoiceState.Transcribing) }
        voiceDictation.stop()
    }

    private fun cancelVoiceInput() {
        voiceTicker?.cancel()
        voiceDictation.cancel()
        setState { copy(voice = VoiceState.Idle) }
    }

    /** Changes the voice state only while the mic is still open. */
    private fun updateListening(reducer: VoiceState.Listening.() -> VoiceState) = setState {
        if (voice is VoiceState.Listening) copy(voice = voice.reducer()) else this
    }

    private fun onVoiceEvent(event: VoiceEvent) {
        // Idle means no dictation is running (or it was cancelled): drop any late events.
        if (currentState.voice is VoiceState.Idle) return
        when (event) {
            is VoiceEvent.Level -> updateListening { copy(rms = event.rmsDb) }
            is VoiceEvent.Partial -> updateListening { copy(partial = event.text) }
            VoiceEvent.Transcribing -> updateListening { VoiceState.Transcribing }
            is VoiceEvent.Final -> {
                voiceTicker?.cancel()
                if (event.transcript.isBlank()) {
                    setState { copy(voice = VoiceState.Idle) }
                    sendEffect(ChatEffect.ShowToast("Didn't catch that — try again."))
                } else {
                    val note = Attachment(
                        mimeType = "audio/x-voice",
                        dataBase64 = "",
                        filename = "Voice message",
                        transcript = event.transcript,
                        durationMs = System.currentTimeMillis() - voiceStartMs,
                    )
                    setState { copy(stagedAttachments = stagedAttachments + note, voice = VoiceState.Idle) }
                }
            }
            is VoiceEvent.Failed -> {
                voiceTicker?.cancel()
                setState { copy(voice = VoiceState.Idle) }
                sendEffect(ChatEffect.ShowToast(voiceFailureMessage(event)))
            }
        }
    }

    private fun voiceFailureMessage(event: VoiceEvent.Failed): String = when (event.reason) {
        VoiceFailure.UNAVAILABLE -> "Speech recognition isn't available on this device."
        VoiceFailure.NO_MATCH -> "Didn't catch that — try again."
        VoiceFailure.LANGUAGE_UNAVAILABLE ->
            "Offline English isn't installed. Add it under system Settings > Voice input."
        VoiceFailure.NO_PERMISSION -> "Microphone permission is required."
        VoiceFailure.BUSY -> "The recognizer is busy — try again in a moment."
        VoiceFailure.OTHER ->
            "Voice input failed${event.code?.let { " (error $it)" }.orEmpty()} — try again."
    }

    override fun onCleared() {
        voiceDictation.release()
        super.onCleared()
    }

    private fun loadSession(sessionId: String) {
        viewModelScope.launch {
            val session = sessionRepository.get(sessionId)
            if (session != null) {
                currentSessionId = session.id
                setState { copy(turns = session.turns, busy = false, status = null) }
            }
        }
    }

    /** Deletes a past conversation. If it's the one currently open, starts a fresh one so
     *  the chat screen isn't left pointing at a session that no longer exists. */
    private fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            sessionRepository.delete(sessionId)
            if (sessionId == currentSessionId) startNewSession()
        }
    }

    /** Opens a fresh conversation; a [draft] is placed in the composer for the user to send. */
    private fun startNewSession(draft: String? = null) {
        currentSessionId = UUID.randomUUID().toString()
        setState { copy(turns = emptyList(), busy = false, status = null) }
        if (draft != null) sendEffect(ChatEffect.FillComposer(draft))
    }

    private fun send(query: String) {
        val attachmentsToSend = currentState.stagedAttachments
        setState { copy(stagedAttachments = emptyList()) }

        // Voice notes are dictation-only (transcript, no audio bytes): fold their text into
        // the query the agent sees — the router classifies on query text, and an empty query
        // would misroute a voice-only message. The displayed ChatTurn keeps the voice
        // attachment (so the bubble renders it as a voice message) and the typed text only,
        // so the transcript never shows twice.
        val voiceTranscripts = attachmentsToSend
            .filter { it.mimeType.startsWith("audio/") && !it.transcript.isNullOrBlank() }
        val agentQuery = (listOf(query) + voiceTranscripts.map { it.transcript!! })
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
        val agentAttachments = attachmentsToSend - voiceTranscripts.toSet()

        if (agentQuery.isBlank() && attachmentsToSend.isEmpty()) return
        viewModelScope.launch {
            val activeProviderName = modelSelection.activeProvider.first()
            // The same pair is shown in the header and passed to the run, so they can't disagree.
            val activeModels = ActiveModels.of(activeProviderName, modelSelection.activeModel.first())
            val model = activeModels.reasoning
            // Snapshot history before appending this turn — Agent.ask() adds the query as a
            // fresh USER message itself, so including the just-added turn would send it twice.
            val historyMessages = currentState.turns.map { it.message }
            setState {
                copy(
                    turns = turns + ChatTurn(Message(Message.Role.USER, query, attachmentsToSend)),
                    busy = true,
                    status = "thinking…",
                    liveReasoning = emptyList(),
                    activeProvider = activeProviderName,
                    activeModel = model,
                )
            }
            // Scoped to this turn: the logger mirrors every line to Logcat (via AndroidLogger)
            // and streams it into the reasoning panel; the structured callbacks (onLlmUsage,
            // the governor's onAudit) drive the stats footer, so the numbers come from the
            // agent runtime itself rather than from parsing log strings.
            val liveLines = mutableListOf<ReasoningLine>()
            var tokens = 0
            var toolCalls = 0
            val startMs = System.currentTimeMillis()
            fun statsNow() = ReasoningStats(tokens, toolCalls, System.currentTimeMillis() - startMs)
            // Records a structured AgentRun for the dedicated Runs screen while still
            // driving the live reasoning panel + Logcat via its delegate logger. The run
            // content is owned by core-agent (dev.kortex.core.observability), not this module.
            val recorder = dev.kortex.core.observability.AgentRunRecorder(
                query = agentQuery,
                sessionId = currentSessionId,
                delegate = Logger { level, tag, message, error ->
                    AndroidLogger.log(level, tag, message, error)
                    liveLines += ReasoningLine(level, tag, message)
                    setState { copy(liveReasoning = liveLines.toList(), liveStats = statsNow()) }
                },
            )
            val turnCtx = AgentContext(
                llm = provider,
                tools = tools,
                models = activeModels.toAgentModels(),
                governor = ToolGovernor(onAudit = { entry ->
                    toolCalls++
                    setState { copy(liveStats = statsNow()) }
                    recorder.audit(entry)
                }),
                approver = approver,
                onProgress = progress,
                logger = recorder.logger,
                onLlmUsage = LlmUsageListener { usage ->
                    tokens += usage.inputTokens + usage.outputTokens
                    setState { copy(liveStats = statsNow()) }
                    recorder.usage.report(usage)
                },
            )

            val result = Agent(turnCtx).ask(agentQuery, agentAttachments, history = historyMessages)
            viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    runTraceStore.save(
                        recorder.finish(result, dev.kortex.core.observability.AgentRun.Status.COMPLETED)
                    )
                }
            }
            val answer = result.messages
                .lastOrNull { it.role == Message.Role.ASSISTANT && it.content.isNotBlank() }

            val answerTurn = answer?.let { ChatTurn(it, liveLines.toList(), statsNow()) }
            setState {
                copy(
                    turns = turns + listOfNotNull(answerTurn),
                    liveReasoning = emptyList(),
                    liveStats = ReasoningStats(),
                    busy = false,
                    status = null,
                )
            }
            // Saved outside the reducer: it can rerun on contention, the save must not.
            val newTurns = currentState.turns
            val sessionId = currentSessionId
            val title = sessionTitle(agentQuery, agentAttachments)
            viewModelScope.launch { sessionRepository.save(sessionId, newTurns, fallbackTitle = title) }
        }
    }
}
