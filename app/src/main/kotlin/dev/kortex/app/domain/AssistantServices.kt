package dev.kortex.app.domain

import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.content.Intent
import android.os.Bundle

/** 
 * Boilerplate to satisfy the android.app.role.ASSISTANT requirements.
 * This allows the app to be selected as the default assistant, 
 * which is required to execute App Functions on Android 16.
 */

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?) = null
}

class AssistantService : VoiceInteractionService()

class AssistantRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent, listener: Callback) {}
    override fun onCancel(listener: Callback) {}
    override fun onStopListening(listener: Callback) {}
}
