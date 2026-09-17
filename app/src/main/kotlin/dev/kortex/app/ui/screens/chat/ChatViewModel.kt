package dev.kortex.app.ui.screens.chat

import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.lifecycle.AndroidViewModel
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
import dev.kortex.core.state.Message
import dev.kortex.core.tool.ToolGovernor
import dev.kortex.core.tool.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import dev.kortex.app.data.local.ChatSessionEntity
import dev.kortex.app.data.local.ChatSessionDao
import dev.kortex.core.observability.AgentRunStore
import dev.kortex.app.data.settings.SettingsStore
import dev.kortex.app.domain.chat.ChatTurn
import dev.kortex.app.domain.chat.ReasoningLine
import dev.kortex.app.domain.chat.ReasoningStats
import kotlinx.coroutines.flow.stateIn
import java.util.UUID
import javax.inject.Inject

data class ChatUi(
    val turns: List<ChatTurn> = emptyList(),
    /** Reasoning for the turn currently in flight, streamed live while [busy]. */
    val liveReasoning: List<ReasoningLine> = emptyList(),
    val liveStats: ReasoningStats = ReasoningStats(),
    val busy: Boolean = false,
    /** Live, human-readable status of the current step (e.g. "Tool usage: web_search"). */
    val status: String? = null,
    /** Set when a HIGH-risk tool is awaiting approval (pattern 13). */
    val pendingApproval: String? = null,
    val activeProvider: String? = null,
    val activeModel: String? = null,
)

/** Voice input (on-device dictation) lifecycle, driven by [ChatViewModel.startVoiceInput]. */
sealed interface VoiceState {
    data object Idle : VoiceState
    /** Mic is open. [partial] streams in live; [rms] is the current mic level in dB. */
    data class Listening(val partial: String = "", val elapsedMs: Long = 0, val rms: Float = 0f) : VoiceState
    /** Mic closed, waiting for the recognizer's final result. */
    data object Transcribing : VoiceState
}

/**
 * Wires the pure-Kotlin [Agent] to Compose. The [Approver] bridges the agent's
 * Human-in-the-Loop pause to a UI dialog: the agent suspends until [resolveApproval].
 * The singleton [ToolRegistry] is injected so that the MCP settings
 * screen and this ViewModel operate on the same tool set; AgentBootstrap sets it up at app start.
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    application: Application,
    private val provider: LlmProvider,
    private val tools: ToolRegistry,
    private val settingsStore: SettingsStore,
    private val sessionDao: ChatSessionDao,
    private val runTraceStore: AgentRunStore,
) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(ChatUi())
    val ui: StateFlow<ChatUi> = kotlinx.coroutines.flow.combine(
        _ui, settingsStore.activeProvider, settingsStore.activeModel
    ) { state, provider, model ->
        state.copy(activeProvider = provider, activeModel = if (provider == "ollama" || provider == "ollama-cloud") model else "gpt-4o")
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), ChatUi())

    private val _stagedAttachments = MutableStateFlow<List<dev.kortex.core.state.Attachment>>(emptyList())
    val stagedAttachments: StateFlow<List<dev.kortex.core.state.Attachment>> = _stagedAttachments.asStateFlow()

    private val _voice = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val voice: StateFlow<VoiceState> = _voice.asStateFlow()

    /** One-shot dictation error for the UI to toast; cleared via [consumeVoiceError]. */
    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()

    /** One-shot text for the composer (e.g. shared from another app); cleared via [consumeDraft]. */
    private val _draft = MutableStateFlow<String?>(null)
    val draft: StateFlow<String?> = _draft.asStateFlow()

    private var recognizer: SpeechRecognizer? = null
    private var voiceTicker: Job? = null
    private var voiceStartMs = 0L

    private var approvalGate: CompletableDeferred<Boolean>? = null

    /**
     * Saved conversations, newest first. Null until Room's first read, so History can tell
     * "still loading" from "no conversations". Kept warm briefly so switching tabs reuses it.
     */
    val sessions: StateFlow<List<ChatSessionEntity>?> = sessionDao.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private var currentSessionId: String = UUID.randomUUID().toString()
    private val json = Json { ignoreUnknownKeys = true }

    private val approver = Approver { name, args ->
        _ui.update { it.copy(pendingApproval = "$name $args") }
        CompletableDeferred<Boolean>().also { approvalGate = it }.await()
    }
    private val progress = ProgressListener { s -> _ui.update { it.copy(status = s) } }

    fun resolveApproval(approved: Boolean) {
        _ui.update { it.copy(pendingApproval = null) }
        approvalGate?.complete(approved)
        approvalGate = null
    }

    fun stageAttachment(attachment: dev.kortex.core.state.Attachment) {
        _stagedAttachments.update { it + attachment }
    }

    fun removeStagedAttachment(index: Int) {
        _stagedAttachments.update { it.filterIndexed { i, _ -> i != index } }
    }

    // ── voice input (on-device dictation via SpeechRecognizer) ──────────

    /** Opens the mic and streams live partial transcripts into [voice]. Caller must hold
     *  RECORD_AUDIO. The final transcript is staged as a voice-note attachment. */
    fun startVoiceInput() {
        if (_voice.value !is VoiceState.Idle) return
        val app = getApplication<Application>()
        if (!SpeechRecognizer.isRecognitionAvailable(app)) {
            _voiceError.value = "Speech recognition isn't available on this device."
            return
        }
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(app).also { recognizer = it }
        rec.setRecognitionListener(voiceListener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        voiceStartMs = System.currentTimeMillis()
        _voice.value = VoiceState.Listening()
        voiceTicker = viewModelScope.launch {
            while (true) {
                delay(100)
                _voice.update { cur ->
                    if (cur is VoiceState.Listening) cur.copy(elapsedMs = System.currentTimeMillis() - voiceStartMs) else cur
                }
            }
        }
        rec.startListening(intent)
    }

    /** Closes the mic; the recognizer finishes transcribing what was said. */
    fun stopVoiceInput() {
        if (_voice.value !is VoiceState.Listening) return
        _voice.value = VoiceState.Transcribing
        recognizer?.stopListening()
    }

    /** Abandons the dictation entirely — nothing is staged. */
    fun cancelVoiceInput() {
        voiceTicker?.cancel()
        recognizer?.cancel()
        _voice.value = VoiceState.Idle
    }

    fun consumeVoiceError() { _voiceError.value = null }

    private val voiceListener = object : RecognitionListener {
        override fun onRmsChanged(rmsdB: Float) {
            _voice.update { cur -> if (cur is VoiceState.Listening) cur.copy(rms = rmsdB) else cur }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                _voice.update { cur -> if (cur is VoiceState.Listening) cur.copy(partial = text) else cur }
            }
        }

        override fun onEndOfSpeech() {
            // The recognizer auto-stops on silence; results arrive in onResults.
            _voice.update { cur -> if (cur is VoiceState.Listening) VoiceState.Transcribing else cur }
        }

        override fun onResults(results: Bundle?) {
            voiceTicker?.cancel()
            val transcript = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
                .orEmpty()
            if (transcript.isBlank()) {
                _voiceError.value = "Didn't catch that — try again."
            } else {
                stageAttachment(
                    dev.kortex.core.state.Attachment(
                        mimeType = "audio/x-voice",
                        dataBase64 = "",
                        filename = "Voice message",
                        transcript = transcript,
                        durationMs = System.currentTimeMillis() - voiceStartMs,
                    )
                )
            }
            _voice.value = VoiceState.Idle
        }

        override fun onError(error: Int) {
            voiceTicker?.cancel()
            _voice.value = VoiceState.Idle
            _voiceError.value = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that — try again."
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                    "Offline English isn't installed. Add it under system Settings > Voice input."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is required."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The recognizer is busy — try again in a moment."
                else -> "Voice input failed (error $error) — try again."
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onCleared() {
        recognizer?.destroy()
        recognizer = null
        super.onCleared()
    }

    fun loadSession(sessionId: String) {
        viewModelScope.launch {
            val session = sessionDao.getById(sessionId)
            if (session != null) {
                currentSessionId = session.id
                val loadedTurns = json.decodeFromString<List<ChatTurn>>(session.turnsJson)
                _ui.update { it.copy(turns = loadedTurns, busy = false, status = null) }
            }
        }
    }

    /** Deletes a past conversation. If it's the one currently open, starts a fresh one so
     *  the chat screen isn't left pointing at a session that no longer exists. */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            sessionDao.deleteById(sessionId)
            if (sessionId == currentSessionId) startNewSession()
        }
    }

    /** Opens a fresh conversation; a [draft] is placed in the composer for the user to send. */
    fun startNewSession(draft: String? = null) {
        currentSessionId = UUID.randomUUID().toString()
        _ui.update { it.copy(turns = emptyList(), busy = false, status = null) }
        if (draft != null) _draft.value = draft
    }

    fun consumeDraft() { _draft.value = null }

    fun send(query: String) {
        val attachmentsToSend = _stagedAttachments.value
        _stagedAttachments.value = emptyList()

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
            val activeProviderName = settingsStore.activeProvider.first()
            val model = when (activeProviderName) {
                "ollama", "ollama-cloud" -> settingsStore.activeModel.first()
                else -> "gpt-4o"
            }
            // Snapshot history before appending this turn — Agent.ask() adds the query as a
            // fresh USER message itself, so including the just-added turn would send it twice.
            val historyMessages = _ui.value.turns.map { it.message }
            _ui.update {
                it.copy(
                    turns = it.turns + ChatTurn(Message(Message.Role.USER, query, attachmentsToSend)),
                    busy = true,
                    status = "thinking…",
                    liveReasoning = emptyList(),
                    activeProvider = activeProviderName,
                    activeModel = model ?: "unknown model",
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
                    _ui.update { it.copy(liveReasoning = liveLines.toList(), liveStats = statsNow()) }
                },
            )
            val turnCtx = AgentContext(
                llm = provider,
                tools = tools,
                governor = ToolGovernor(onAudit = { entry ->
                    toolCalls++
                    _ui.update { it.copy(liveStats = statsNow()) }
                    recorder.audit(entry)
                }),
                approver = approver,
                onProgress = progress,
                logger = recorder.logger,
                onLlmUsage = LlmUsageListener { usage ->
                    tokens += usage.inputTokens + usage.outputTokens
                    _ui.update { it.copy(liveStats = statsNow()) }
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

            _ui.update { cur ->
                val newTurns = cur.turns + listOfNotNull(answer?.let { ChatTurn(it, liveLines.toList(), statsNow()) })
                viewModelScope.launch {
                    val title = if (newTurns.size <= 2) agentQuery.take(40) else sessionDao.getById(currentSessionId)?.title ?: agentQuery.take(40)
                    sessionDao.upsert(
                        ChatSessionEntity(
                            id = currentSessionId,
                            title = title,
                            turnsJson = json.encodeToString(newTurns),
                            updatedAtMillis = System.currentTimeMillis()
                        )
                    )
                }
                cur.copy(
                    turns = newTurns,
                    liveReasoning = emptyList(),
                    liveStats = ReasoningStats(),
                    busy = false,
                    status = null,
                )
            }
        }
    }
}
