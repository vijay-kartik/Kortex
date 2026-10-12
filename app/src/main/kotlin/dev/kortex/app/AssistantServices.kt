package dev.kortex.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService

/**
 * Lets Kortex hold the android.app.role.ASSISTANT role (picked by the user under Settings ›
 * Default apps › Digital assistant app), which is needed to run App Functions on Android 16.
 * Nothing assistant-specific is built yet, so a session does nothing and a recognition request
 * gets no results. Named in res/xml/assistant_config.xml and declared in the manifest.
 */
class AssistantService : VoiceInteractionService()

class AssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = NoOpSession(this)
}

/** Closes as soon as it's shown, so invoking the assistant (e.g. long-pressing home) does nothing. */
private class NoOpSession(context: Context) : VoiceInteractionSession(context) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        finish()
    }
}

class AssistantRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent, listener: Callback) {}
    override fun onCancel(listener: Callback) {}
    override fun onStopListening(listener: Callback) {}
}
