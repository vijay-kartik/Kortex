package dev.kortex.app.ui.screens.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.kortex.design.EmptyState as KortexEmptyState
import dev.kortex.app.ui.components.PulsingDot

@Composable
fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        KortexEmptyState(
            title = "Kortex is listening",
            body = "It can search the web, open pages,\ndo math, and check the time.",
            glyph = { PulsingDot(10.dp) },
        )
    }
}
