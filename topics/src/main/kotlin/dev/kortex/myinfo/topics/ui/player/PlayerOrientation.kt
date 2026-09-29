package dev.kortex.myinfo.topics.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.provider.Settings
import android.view.OrientationEventListener

/**
 * Who turns the screen while the player is open (Figma: Topic videos 2j). Normally the phone does:
 * held sideways, the video goes full screen, and upright it comes back. The Full screen button and
 * back override that, to landscape or to portrait, until the phone is actually held that way; then
 * the sensor has it again, so turning the phone afterwards still works.
 *
 * With auto-rotate off there's no sensor to hand back to. Full screen then holds landscape until
 * back, which returns the screen to the user's own locked orientation.
 */
internal class PlayerOrientation(private val activity: Activity) {
    /** True for landscape, false for portrait; null while the sensor decides. */
    private var forced: Boolean? = null

    private val listener = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(degrees: Int) {
            val landscape = forced ?: return
            if (degrees == ORIENTATION_UNKNOWN || !autoRotate()) return
            if ((landscape && heldLandscape(degrees)) || (!landscape && heldPortrait(degrees))) unlock()
        }
    }

    fun start() {
        if (listener.canDetectOrientation()) listener.enable()
    }

    /** Leaving the player: the screen turns freely again. */
    fun stop() {
        listener.disable()
        if (forced != null) unlock()
    }

    fun enterFullScreen() = force(landscape = true)

    fun leaveFullScreen() {
        if (autoRotate()) force(landscape = false) else unlock()
    }

    private fun force(landscape: Boolean) {
        forced = landscape
        // The sensor variant picks whichever landscape the phone is nearer, and works with auto-rotate off.
        activity.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun unlock() {
        forced = null
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private fun autoRotate(): Boolean =
        Settings.System.getInt(activity.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    private companion object {
        // Degrees the phone is turned from upright; the gaps between the bands are deliberate slack.
        fun heldPortrait(degrees: Int) = degrees <= 30 || degrees >= 330 || degrees in 150..210
        fun heldLandscape(degrees: Int) = degrees in 60..120 || degrees in 240..300
    }
}
