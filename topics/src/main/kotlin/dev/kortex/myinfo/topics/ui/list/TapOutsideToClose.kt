package dev.kortex.myinfo.topics.ui.list

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned

/** Where the open card sits within the screen, so a tap anywhere else can close its tray. */
internal class OpenCardBounds {
    var screen: LayoutCoordinates? = null
    var card: LayoutCoordinates? = null

    /** [position] is in [screen] coordinates. Only the card's visible part counts. */
    fun contains(position: Offset): Boolean {
        val screen = screen?.takeIf { it.isAttached } ?: return false
        val card = card?.takeIf { it.isAttached } ?: return false
        return screen.localBoundingBoxOf(card).contains(position)
    }
}

/**
 * While [open], a tap outside [bounds]' card calls [onClose]. Runs on the initial pass, so that
 * tap only closes the tray: it never presses what's under it or scrolls the list.
 */
internal fun Modifier.tapOutsideToClose(bounds: OpenCardBounds, open: Boolean, onClose: () -> Unit): Modifier = composed {
    val currentOpen by rememberUpdatedState(open)
    val currentOnClose by rememberUpdatedState(onClose)
    onGloballyPositioned { bounds.screen = it }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (!currentOpen || bounds.contains(down.position)) return@awaitEachGesture
                down.consume()
                currentOnClose()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    event.changes.forEach { it.consume() }
                } while (event.changes.any { it.pressed })
            }
        }
}
