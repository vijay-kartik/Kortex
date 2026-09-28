package dev.kortex.links.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.kortex.links.ui.create.CreateLinkRoute

/** The new-link form, as the host shows it: a full-screen overlay, pre-filled with [initialUrl] when shared in. */
@Composable
fun CreateLinkScreen(
    onBack: () -> Unit = {},
    modifier: Modifier = Modifier,
    initialUrl: String = "",
) {
    CreateLinkRoute(onBack = onBack, initialUrl = initialUrl, modifier = modifier)
}
