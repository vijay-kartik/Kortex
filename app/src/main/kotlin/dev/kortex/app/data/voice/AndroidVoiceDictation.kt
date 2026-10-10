package dev.kortex.app.data.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dev.kortex.app.domain.voice.VoiceDictation
import dev.kortex.app.domain.voice.VoiceEvent
import dev.kortex.app.domain.voice.VoiceFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * On-device dictation through the platform [SpeechRecognizer], preferring the offline English
 * model. The recognizer is created lazily and reused across dictations until [release].
 * Must be driven from the main thread, as [SpeechRecognizer] requires.
 */
class AndroidVoiceDictation(private val context: Context) : VoiceDictation {

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    override val events: Flow<VoiceEvent> = _events.asSharedFlow()

    private var recognizer: SpeechRecognizer? = null

    override fun start() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _events.tryEmit(VoiceEvent.Failed(VoiceFailure.UNAVAILABLE))
            return
        }
        val rec = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        rec.setRecognitionListener(listener)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        rec.startListening(intent)
    }

    override fun stop() {
        recognizer?.stopListening()
    }

    override fun cancel() {
        recognizer?.cancel()
    }

    override fun release() {
        recognizer?.destroy()
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onRmsChanged(rmsdB: Float) {
            _events.tryEmit(VoiceEvent.Level(rmsdB))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults.firstResult()
            if (text.isNotBlank()) _events.tryEmit(VoiceEvent.Partial(text))
        }

        override fun onEndOfSpeech() {
            // The recognizer auto-stops on silence; results arrive in onResults.
            _events.tryEmit(VoiceEvent.Transcribing)
        }

        override fun onResults(results: Bundle?) {
            _events.tryEmit(VoiceEvent.Final(results.firstResult().trim()))
        }

        override fun onError(error: Int) {
            val reason = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceFailure.NO_MATCH
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> VoiceFailure.LANGUAGE_UNAVAILABLE
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceFailure.NO_PERMISSION
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceFailure.BUSY
                else -> VoiceFailure.OTHER
            }
            _events.tryEmit(VoiceEvent.Failed(reason, error))
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun Bundle?.firstResult(): String =
        this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
}
