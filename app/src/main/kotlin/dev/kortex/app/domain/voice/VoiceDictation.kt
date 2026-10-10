package dev.kortex.app.domain.voice

import kotlinx.coroutines.flow.Flow

/**
 * Speech-to-text for the chat composer. One dictation runs at a time: [start] opens the mic,
 * [stop] closes it and lets the engine finish transcribing, [cancel] abandons it. Progress and
 * the outcome arrive on [events]; every dictation ends with [VoiceEvent.Final] or
 * [VoiceEvent.Failed], unless cancelled.
 */
interface VoiceDictation {
    val events: Flow<VoiceEvent>

    /** Caller must hold the microphone permission. */
    fun start()
    fun stop()
    fun cancel()

    /** Frees the engine; the instance must not be used afterwards. */
    fun release()
}

sealed interface VoiceEvent {
    /** The live transcript so far, while the mic is open. */
    data class Partial(val text: String) : VoiceEvent
    /** The current mic level in dB, while the mic is open. */
    data class Level(val rmsDb: Float) : VoiceEvent
    /** The mic closed (silence or [VoiceDictation.stop]); the final transcript follows. */
    data object Transcribing : VoiceEvent
    /** The whole transcript; blank when nothing was recognised. */
    data class Final(val transcript: String) : VoiceEvent
    /** [code] is the engine's own error code, for diagnostics when [reason] is [VoiceFailure.OTHER]. */
    data class Failed(val reason: VoiceFailure, val code: Int? = null) : VoiceEvent
}

enum class VoiceFailure {
    /** The device has no speech recognition engine. */
    UNAVAILABLE,
    /** Nothing intelligible was said before the engine gave up. */
    NO_MATCH,
    /** The offline language pack isn't installed. */
    LANGUAGE_UNAVAILABLE,
    NO_PERMISSION,
    BUSY,
    OTHER,
}
