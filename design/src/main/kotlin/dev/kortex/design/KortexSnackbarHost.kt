package dev.kortex.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The one snackbar every screen uses (Figma: Finances snackbar). Colours come from the theme's
 * inverse roles: [EdgeStrong] fill, [Ink] message, [Synapse] action.
 */
@Composable
fun KortexSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { Snackbar(it, shape = RoundedCornerShape(12.dp)) }
}
