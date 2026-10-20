package dev.kortex.app.ui.screens.chat

import dev.kortex.app.domain.chat.ChatSessionSummary
import dev.kortex.app.domain.chat.ChatTurn
import dev.kortex.app.domain.chat.ReasoningLine
import dev.kortex.app.domain.chat.ReasoningStats
import dev.kortex.core.state.Attachment

/**
 * The Chat tab, its History list and the share action. The composer's typed text stays in the
 * screen and reaches the ViewModel inside [ChatIntent.Send], so typing never waits on a state
 * round-trip.
 */
data class ChatState(
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
    /** Files and voice notes that go out with the next message. */
    val stagedAttachments: List<Attachment> = emptyList(),
    val voice: VoiceState = VoiceState.Idle,
    /**
     * Saved conversations, newest first. Null until Room's first read, so History can tell
     * "still loading" from "no conversations".
     */
    val sessions: List<ChatSessionSummary>? = null,
) {
    val showEmptyState: Boolean = turns.isEmpty() && !busy

    /** The line under the conversation while the agent works, e.g. "thinking… • ollama / gemma". */
    val busyLine: String = buildString {
        append((status ?: "working…").lowercase())
        if (activeProvider != null) {
            append(" • $activeProvider")
            if (activeModel != null) append(" / $activeModel")
        }
    }

    /** The footer naming the model, e.g. "gemma4:31b • Ollama Cloud"; null until a model is known. */
    val modelFooter: String? = activeModel?.let { model ->
        val providerName = when (activeProvider) {
            "ollama" -> "Ollama"
            "ollama-cloud" -> "Ollama Cloud"
            else -> "OpenAI"
        }
        "$model • $providerName"
    }

    /** Whether the composer offers send (rather than the mic) for [input]. */
    fun canSend(input: String): Boolean = input.isNotBlank() || stagedAttachments.isNotEmpty()
}

/** Voice input (on-device dictation) lifecycle, driven by [ChatIntent.StartVoice]. */
sealed interface VoiceState {
    data object Idle : VoiceState
    /** Mic is open. [partial] streams in live; [rms] is the current mic level in dB. */
    data class Listening(val partial: String = "", val elapsedMs: Long = 0, val rms: Float = 0f) : VoiceState
    /** Mic closed, waiting for the recognizer's final result. */
    data object Transcribing : VoiceState
}

sealed interface ChatIntent {
    data class Send(val text: String) : ChatIntent
    data class StageAttachment(val attachment: Attachment) : ChatIntent
    data class RemoveAttachment(val index: Int) : ChatIntent
    /** Opens the mic. The caller must already hold RECORD_AUDIO. */
    data object StartVoice : ChatIntent
    /** Closes the mic; the engine finishes transcribing what was said. */
    data object StopVoice : ChatIntent
    /** Abandons the dictation entirely — nothing is staged. */
    data object CancelVoice : ChatIntent
    data class ResolveApproval(val approved: Boolean) : ChatIntent
    data class LoadSession(val sessionId: String) : ChatIntent
    /** Opens a fresh conversation; a non-null [draft] is placed in the composer, not sent. */
    data class NewSession(val draft: String? = null) : ChatIntent
    data class DeleteSession(val sessionId: String) : ChatIntent
    data class RenameSession(val sessionId: String, val title: String) : ChatIntent
}

sealed interface ChatEffect {
    /** A dictation error, shown once. */
    data class ShowToast(val message: String) : ChatEffect
    /** Replaces the composer text, e.g. with text shared from another app. */
    data class FillComposer(val text: String) : ChatEffect
}
