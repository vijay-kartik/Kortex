package dev.kortex.app.ui.onboarding

import androidx.compose.ui.geometry.Rect

/**
 * The system splash, held on screen until the welcome intro has drawn the mark over its icon.
 * [iconBounds] are in window pixels; the icon's 108-unit adaptive viewport fills them.
 */
class SplashHandoff(val iconBounds: Rect, private val remove: () -> Unit) {
    private var removed = false

    /** Takes the splash down. Safe to call more than once; main thread only. */
    fun release() {
        if (removed) return
        removed = true
        remove()
    }
}
